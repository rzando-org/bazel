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

package com.google.devtools.build.lib.bazel.repository.starlark;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.devtools.build.lib.rules.repository.RepoRecordedInput;
import com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunction.Environment.SkyKeyComputeState;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/**
 * Captures state that persists across different invocations of {@link
 * com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction}, specifically {@link
 * StarlarkRepositoryFunction}.
 *
 * <p>This class is used to hold on to a worker thread when fetching repos using a worker thread is
 * enabled. The worker thread uses a {@link SkyFunction.Environment} object acquired from the host
 * thread, and can signal the host thread to restart to get a fresh environment object.
 */
class RepoFetchingSkyKeyComputeState implements SkyKeyComputeState {

  /**
   * A semaphore with 0 or 1 permit. The worker can release a permit either when it's finished
   * (successfully or otherwise), or to indicate that the host thread should return {@code null},
   * causing a Skyframe restart. In the latter case, the worker will immediately block on {@code
   * delegateEnvQueue}, waiting for the host thread to send a fresh {@link SkyFunction.Environment}
   * over.
   */
  // A Semaphore is useful here because, crucially, releasing a permit never blocks and thus cannot
  // be interrupted.
  final Semaphore signalSemaphore = new Semaphore(0);

  /**
   * The channel for the host Skyframe thread to send fresh {@link SkyFunction.Environment} objects
   * back to the worker thread.
   */
  // We use an ArrayBlockingQueue of size 1 instead of a SynchronousQueue, so that if the worker
  // gets interrupted before the host thread restarts, the host thread doesn't hang forever.
  final BlockingQueue<SkyFunction.Environment> delegateEnvQueue = new ArrayBlockingQueue<>(1);

  /**
   * This future holds on to the worker thread in order to cancel it when necessary; it also serves
   * to tell whether a worker thread is already running.
   */
  @GuardedBy("this")
  @Nullable
  private ListenableFuture<RepositoryDirectoryValue.Builder> workerFuture = null;

  /** The executor service that manages the worker thread. */
  // We hold on to this alongside `workerFuture` because it offers a convenient mechanism to make
  // sure the worker thread has shut down (with its blocking `close()` method).
  @GuardedBy("this")
  @Nullable
  private ListeningExecutorService workerExecutorService = null;

  private final String repoName;

  /**
   * This is where the recorded inputs & values for the whole invocation is collected.
   *
   * <p>{@link com.google.devtools.build.lib.rules.repository.RepositoryDelegatorFunction} creates a
   * new map on each restart, so we can't simply plumb that in.
   */
  final Map<RepoRecordedInput, String> recordedInputValues = new TreeMap<>();

  RepoFetchingSkyKeyComputeState(String repoName) {
    this.repoName = repoName;
  }

  /**
   * Releases a permit on the {@code signalSemaphore} and immediately expect a fresh Environment
   * back. This may only be called from the worker thread.
   */
  SkyFunction.Environment signalForFreshEnv() throws InterruptedException {
    signalSemaphore.release();
    return delegateEnvQueue.take();
  }

  /**
   * Returns the worker future, or if a worker is not already running, starts a worker thread
   * running the given callable. This makes sure to release a permit on the {@code signalSemaphore}
   * when the worker finishes, successfully or otherwise. This may only be called from the host
   * Skyframe thread.
   */
  synchronized ListenableFuture<RepositoryDirectoryValue.Builder> getOrStartWorker(
      Callable<RepositoryDirectoryValue.Builder> c) {
    if (workerFuture != null) {
      return workerFuture;
    }
    // We reset the state object back to its very initial state, since the host SkyFunction may have
    // been re-entered (for example b/330892334 and
    // https://github.com/bazelbuild/bazel/issues/21238), and/or the previous worker thread may have
    // been interrupted while the host SkyFunction was inactive.
    workerExecutorService =
        MoreExecutors.listeningDecorator(
            Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("starlark-repository-" + repoName).factory()));
    signalSemaphore.drainPermits();
    delegateEnvQueue.clear();
    recordedInputValues.clear();

    // Start the worker.
    workerFuture = workerExecutorService.submit(c);
    workerFuture.addListener(signalSemaphore::release, directExecutor());
    return workerFuture;
  }

  /**
   * Closes the state object, and blocks until all pending async work is finished. The state object
   * will reset to a clean slate after this method finishes.
   */
  // This may be called from any thread, including the host Skyframe thread and the
  // high-memory-pressure listener thread.
  @Override
  public synchronized void close() {
    if (workerFuture != null) {
      workerFuture.cancel(true);
    }
    workerFuture = null;
    if (workerExecutorService != null) {
      workerExecutorService.close(); // This blocks
    }
  }
}
