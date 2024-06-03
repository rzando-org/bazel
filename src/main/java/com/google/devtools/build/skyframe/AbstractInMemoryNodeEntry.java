// Copyright 2023 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.skyframe;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Partial implementation of {@link InMemoryNodeEntry} containing behavior common to both {@link
 * IncrementalInMemoryNodeEntry} and {@link NonIncrementalInMemoryNodeEntry}. All operations on this
 * class are thread-safe.
 *
 * <p>Care was taken to provide certain compound operations to avoid certain check-then-act races.
 * That means this class is somewhat closely tied to the exact Evaluator implementation.
 *
 * <p>Consider the example with two threads working on two nodes, where one depends on the other,
 * say b depends on a. If a completes first, it's done. If it completes second, it needs to signal
 * b, and potentially re-schedule it. If b completes first, it must exit, because it will be
 * signaled (and re-scheduled) by a. If it completes second, it must signal (and re-schedule)
 * itself. However, if the Evaluator supported re-entrancy for a node, then this wouldn't have to be
 * so strict, because duplicate scheduling would be less problematic.
 *
 * <p>During its life, a node can go through states as follows:
 *
 * <ol>
 *   <li>Non-existent
 *   <li>Just created or marked as affected ({@link #isDone} is false; {@link #isDirty} is false)
 *   <li>Evaluating ({@link #isDone} is false; {@link #isDirty} is true)
 *   <li>Done ({@link #isDone} is true; {@link #isDirty} is false)
 * </ol>
 *
 * <p>The "just created" state is there to allow the {@link ProcessableGraph#createIfAbsentBatch}
 * and {@link NodeEntry#addReverseDepAndCheckIfDone} methods to be separate. All callers have to
 * call both methods in that order if they want to create a node. The second method returns the
 * NEEDS_SCHEDULING state only on the first time it was called. A caller that gets NEEDS_SCHEDULING
 * back from that call must start the evaluation of this node, while any subsequent callers must
 * not.
 *
 * <p>An entry is set to ALREADY_EVALUATING as soon as it is scheduled for evaluation. Thus, even a
 * node that is never actually built (for instance, a dirty node that is verified as clean) is in
 * the ALREADY_EVALUATING state until it is DONE.
 *
 * <p>From the DONE state, the node can go back to the "marked as affected" state.
 *
 * @param <D> the type of {@link DirtyBuildingState} used by the {@link AbstractInMemoryNodeEntry}
 *     subclass
 */
abstract class AbstractInMemoryNodeEntry<D extends DirtyBuildingState>
    implements InMemoryNodeEntry {

  private final SkyKey key;

  /** Actual data stored in this entry when it is done. */
  @Nullable protected volatile SkyValue value;

  /**
   * Tracks state of this entry while it is evaluating (either on its initial build or after being
   * marked dirty).
   */
  @Nullable protected volatile D dirtyBuildingState;

  AbstractInMemoryNodeEntry(SkyKey key) {
    this.key = checkNotNull(key);
  }

  @Override
  public final SkyKey getKey() {
    return key;
  }

  private boolean isEvaluating() {
    return dirtyBuildingState != null;
  }

  @Override
  public boolean isDone() {
    return value != null && dirtyBuildingState == null;
  }

  @Override
  public final synchronized boolean isReadyToEvaluate() {
    return !isDone()
        && isEvaluating()
        && (dirtyBuildingState.isReady(getNumTemporaryDirectDeps())
            || key.supportsPartialReevaluation());
  }

  @Override
  public final synchronized boolean hasUnsignaledDeps() {
    checkState(!isDone(), this);
    checkState(isEvaluating(), this);
    return !dirtyBuildingState.isReady(getNumTemporaryDirectDeps());
  }

  @Override
  public final boolean isDirty() {
    return !isDone();
  }

  @Override
  public synchronized boolean isChanged() {
    return !isDone() && dirtyBuildingState != null && dirtyBuildingState.isChanged();
  }

  @Override
  @Nullable
  public final synchronized SkyValue getValue() {
    checkState(isDone(), "no value until done. ValueEntry: %s", this);
    return ValueWithMetadata.justValue(value);
  }

  @Override
  @Nullable
  public final SkyValue getValueMaybeWithMetadata() {
    return value;
  }

  @Override
  @Nullable
  public final SkyValue toValue() {
    SkyValue lastBuildValue = value;
    if (lastBuildValue == null) {
      synchronized (this) {
        if (value != null) {
          lastBuildValue = value;
        } else if (dirtyBuildingState != null) {
          try {
            lastBuildValue = dirtyBuildingState.getLastBuildValue();
          } catch (InterruptedException e) {
            throw new IllegalStateException("Interruption unexpected: " + this, e);
          }
        } else {
          return null; // An evaluation was never started.
        }
      }
    }

    return lastBuildValue != null ? ValueWithMetadata.justValue(lastBuildValue) : null;
  }

  @Override
  @Nullable
  public final synchronized ErrorInfo getErrorInfo() {
    checkState(isDone(), "no errors until done. NodeEntry: %s", this);
    return ValueWithMetadata.getMaybeErrorInfo(value);
  }

  @Override
  public final synchronized void addExternalDep() {
    checkNotNull(dirtyBuildingState, this);
    dirtyBuildingState.addExternalDep();
  }

  @Override
  public final synchronized LifecycleState getLifecycleState() {
    if (isDone()) {
      return LifecycleState.DONE;
    } else if (dirtyBuildingState == null) {
      return LifecycleState.NOT_YET_EVALUATING;
    } else {
      return dirtyBuildingState.getLifecycleState();
    }
  }

  @Override
  public final synchronized List<SkyKey> getNextDirtyDirectDeps() throws InterruptedException {
    checkState(!hasUnsignaledDeps(), this);
    checkNotNull(dirtyBuildingState, this);
    checkState(dirtyBuildingState.isEvaluating(), "Not evaluating during getNextDirty? %s", this);
    return dirtyBuildingState.getNextDirtyDirectDeps();
  }

  @Override
  public final synchronized ImmutableSet<SkyKey> getAllRemainingDirtyDirectDeps()
      throws InterruptedException {
    checkNotNull(dirtyBuildingState, this);
    checkState(dirtyBuildingState.isEvaluating(), "Not evaluating for remaining dirty? %s", this);
    if (isDirty()) {
      checkState(dirtyBuildingState.getLifecycleState() == LifecycleState.REBUILDING, this);
      return dirtyBuildingState.getAllRemainingDirtyDirectDeps(/* preservePosition= */ true);
    } else {
      return ImmutableSet.of();
    }
  }

  @Override
  public synchronized void markRebuilding() {
    checkNotNull(dirtyBuildingState, this).markRebuilding();
  }

  final GroupedDeps newGroupedDeps() {
    // If the key skips batch prefetching and possibly opts into partial reevaluation, there will be
    // no environment-scoped map storing previously requested deps values after
    // `SkyFunctionEnvironment` instantiation. So tracking deps with a HashSet is worth the extra
    // memory cost for efficient query -- see SkyFunctionEnvironment.SkipsBatchPrefetch.
    // TODO: b/324948927#comment8 - (1) Determine whether to skip batch prefetch using some logic
    // other than the `SkyKey#skipBatchPrefetch()` method; (2) Consider creating and using the set
    // on demand by calling `GroupedDeps#toSet()` when `SkyFunctionEnvironment#SkipsBatchPrefetch`
    // is created. Whether calling `GroupedDeps#toSet()` introduces performance regression requires
    // some benchmarkings.
    return key.skipsBatchPrefetch() ? new GroupedDeps.WithHashSet() : new GroupedDeps();
  }

  abstract int getNumTemporaryDirectDeps();

  @Override
  public final synchronized boolean noDepsLastBuild() {
    checkState(isEvaluating(), this);
    return dirtyBuildingState.noDepsLastBuild();
  }

  @Override
  public final synchronized void removeUnfinishedDeps(Set<SkyKey> unfinishedDeps) {
    getTemporaryDirectDeps().remove(unfinishedDeps);
  }

  @Override
  public final synchronized void addSingletonTemporaryDirectDep(SkyKey dep) {
    getTemporaryDirectDeps().appendSingleton(dep);
  }

  @Override
  public final synchronized void addTemporaryDirectDepGroup(List<SkyKey> group) {
    getTemporaryDirectDeps().appendGroup(group);
  }

  @Override
  public final synchronized void addTemporaryDirectDepsInGroups(
      Set<SkyKey> deps, List<Integer> groupSizes) {
    getTemporaryDirectDeps().appendGroups(deps, groupSizes);
  }

  protected synchronized MoreObjects.ToStringHelper toStringHelper() {
    return MoreObjects.toStringHelper(this)
        .add("key", key)
        .add("value", value)
        .add("dirtyBuildingState", dirtyBuildingState);
  }

  @Override
  public final synchronized String toString() {
    return toStringHelper().toString();
  }
}
