// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.util.Fingerprint;
import javax.annotation.Nullable;

/**
 * An action that depends on a set of inputs and creates a single output file whenever it runs. This
 * is useful for bundling up a bunch of dependencies that are shared between individual targets in
 * the action graph; for example generated header files.
 */
@Immutable
public final class MiddlemanAction extends AbstractAction {
  public static final String MIDDLEMAN_MNEMONIC = "Middleman";

  /** The runfiles tree this middleman stands for. */
  private final RunfilesTree runfilesTree;

  public MiddlemanAction(
      ActionOwner owner,
      RunfilesTree runfilesTree,
      NestedSet<Artifact> inputs,
      ImmutableSet<Artifact> outputs) {
    super(owner, inputs, outputs);

    this.runfilesTree = runfilesTree;
    Preconditions.checkArgument(Iterables.getOnlyElement(outputs).isMiddlemanArtifact(), outputs);
  }

  public RunfilesTree getRunfilesTree() {
    return runfilesTree;
  }

  @Override
  public ActionResult execute(ActionExecutionContext actionExecutionContext) {
    throw new IllegalStateException("MiddlemanAction should never be executed");
  }

  @Override
  protected void computeKey(
      ActionKeyContext actionKeyContext,
      @Nullable ArtifactExpander artifactExpander,
      Fingerprint fp) {
    // TODO(bazel-team): Need to take middlemanType into account here.
    // Only the set of inputs matters, and the dependency checker is
    // responsible for considering those.
  }

  /**
   * Returns the type of the middleman.
   */
  @Override
  public MiddlemanType getActionType() {
    return MiddlemanType.RUNFILES_MIDDLEMAN;
  }

  @Nullable
  @Override
  protected String getRawProgressMessage() {
    return null; // users don't really want to know about Middlemen.
  }

  @Override
  public String prettyPrint() {
    return "runfiles for " + Label.print(getOwner().getLabel());
  }

  @Override
  public String getMnemonic() {
    return MIDDLEMAN_MNEMONIC;
  }

  @Override
  public boolean mayInsensitivelyPropagateInputs() {
    return true;
  }

  @Override
  @Nullable
  public PlatformInfo getExecutionPlatform() {
    // Middleman actions do not execute actual actions, and therefore have no execution platform.
    return null;
  }

  @Override
  public ImmutableMap<String, String> getExecProperties() {
    // Middleman actions do not execute actual actions, and therefore have no execution properties.
    return ImmutableMap.of();
  }
}
