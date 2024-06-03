// Copyright 2016 The Bazel Authors. All rights reserved.
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

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Table;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.analysis.BlazeDirectories;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.bazel.bzlmod.NonRegistryOverride;
import com.google.devtools.build.lib.bazel.repository.RepositoryResolvedEvent;
import com.google.devtools.build.lib.bazel.repository.downloader.DownloadManager;
import com.google.devtools.build.lib.cmdline.BazelStarlarkContext;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.pkgcache.PathPackageLocator;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.repository.RepositoryFetchProgress;
import com.google.devtools.build.lib.rules.repository.NeedsSkyframeRestartException;
import com.google.devtools.build.lib.rules.repository.RepoRecordedInput;
import com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue;
import com.google.devtools.build.lib.rules.repository.RepositoryFunction;
import com.google.devtools.build.lib.rules.repository.WorkspaceFileHelper;
import com.google.devtools.build.lib.runtime.ProcessWrapper;
import com.google.devtools.build.lib.runtime.RepositoryRemoteExecutor;
import com.google.devtools.build.lib.skyframe.IgnoredPackagePrefixesValue;
import com.google.devtools.build.lib.skyframe.PrecomputedValue;
import com.google.devtools.build.lib.skyframe.RepositoryMappingValue;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.SyscallCache;
import com.google.devtools.build.skyframe.SkyFunction.Environment;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkSemantics;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.SymbolGenerator;

/** A repository function to delegate work done by Starlark remote repositories. */
public final class StarlarkRepositoryFunction extends RepositoryFunction {
  private final DownloadManager downloadManager;
  private double timeoutScaling = 1.0;
  private boolean useWorkers;
  @Nullable private ProcessWrapper processWrapper = null;
  @Nullable private RepositoryRemoteExecutor repositoryRemoteExecutor;
  @Nullable private SyscallCache syscallCache;

  public StarlarkRepositoryFunction(DownloadManager downloadManager) {
    this.downloadManager = downloadManager;
  }

  public void setTimeoutScaling(double timeoutScaling) {
    this.timeoutScaling = timeoutScaling;
  }

  public void setProcessWrapper(@Nullable ProcessWrapper processWrapper) {
    this.processWrapper = processWrapper;
  }

  public void setSyscallCache(SyscallCache syscallCache) {
    this.syscallCache = checkNotNull(syscallCache);
  }

  public void setUseWorkers(boolean useWorkers) {
    this.useWorkers = useWorkers;
  }

  @Override
  protected void setupRepoRootBeforeFetching(Path repoRoot) throws RepositoryFunctionException {
    // DON'T delete the repo root here if we're using a worker thread, since when this SkyFunction
    // restarts, fetching is still happening inside the worker thread.
    if (!useWorkers) {
      setupRepoRoot(repoRoot);
    }
  }

  @Override
  public void reportSkyframeRestart(Environment env, RepositoryName repoName) {
    // DON'T report a "restarting." event if we're using a worker thread, since the actual fetch
    // function run by the worker thread never restarts.
    if (!useWorkers) {
      super.reportSkyframeRestart(env, repoName);
    }
  }

  private record FetchArgs(
      Rule rule,
      Path outputDirectory,
      BlazeDirectories directories,
      Environment env,
      Map<RepoRecordedInput, String> recordedInputValues,
      SkyKey key) {
    FetchArgs toWorkerArgs(Environment env, Map<RepoRecordedInput, String> recordedInputValues) {
      return new FetchArgs(rule, outputDirectory, directories, env, recordedInputValues, key);
    }
  }

  @Nullable
  @Override
  public RepositoryDirectoryValue.Builder fetch(
      Rule rule,
      Path outputDirectory,
      BlazeDirectories directories,
      Environment env,
      Map<RepoRecordedInput, String> recordedInputValues,
      SkyKey key)
      throws RepositoryFunctionException, InterruptedException {
    var args = new FetchArgs(rule, outputDirectory, directories, env, recordedInputValues, key);
    if (!useWorkers) {
      return fetchInternal(args);
    }
    // See below (the `catch CancellationException` clause) for why there's a `while` loop here.
    while (true) {
      var state = env.getState(() -> new RepoFetchingSkyKeyComputeState(rule.getName()));
      ListenableFuture<RepositoryDirectoryValue.Builder> workerFuture =
          state.getOrStartWorker(
              () -> {
                Environment workerEnv = new RepoFetchingWorkerSkyFunctionEnvironment(state);
                setupRepoRoot(outputDirectory);
                return fetchInternal(args.toWorkerArgs(workerEnv, state.recordedInputValues));
              });
      try {
        state.delegateEnvQueue.put(env);
        state.signalSemaphore.acquire();
        if (!workerFuture.isDone()) {
          // This means that the worker is still running, and expecting a fresh Environment. Return
          // null to trigger a Skyframe restart, but *don't* shut down the worker executor.
          return null;
        }
        RepositoryDirectoryValue.Builder result = workerFuture.get();
        recordedInputValues.putAll(state.recordedInputValues);
        return result;
      } catch (ExecutionException e) {
        Throwables.throwIfInstanceOf(e.getCause(), RepositoryFunctionException.class);
        Throwables.throwIfUnchecked(e.getCause());
        throw new IllegalStateException(
            "unexpected exception type: " + e.getCause().getClass(), e.getCause());
      } catch (CancellationException e) {
        // This can only happen if the state object was invalidated due to memory pressure, in
        // which case we can simply reattempt the fetch. Show a message and continue into the next
        // `while` iteration.
        env.getListener()
            .post(
                RepositoryFetchProgress.ongoing(
                    RepositoryName.createUnvalidated(rule.getName()),
                    "fetch interrupted due to memory pressure; restarting."));
      } finally {
        if (workerFuture.isDone()) {
          // Unless we know the worker is waiting on a fresh Environment, we should *always* shut
          // down the worker executor by the time we finish executing (successfully or otherwise).
          // This ensures that 1) no background work happens without our knowledge, and 2) if the
          // SkyFunction is re-entered for any reason (for example b/330892334 and
          // https://github.com/bazelbuild/bazel/issues/21238), we know we'll need to create a new
          // worker from scratch.
          state.close();
        }
      }
    }
  }

  @Nullable
  private RepositoryDirectoryValue.Builder fetchInternal(FetchArgs args)
      throws RepositoryFunctionException, InterruptedException {
    return fetchInternal(
        args.rule,
        args.outputDirectory,
        args.directories,
        args.env,
        args.recordedInputValues,
        args.key);
  }

  @Nullable
  private RepositoryDirectoryValue.Builder fetchInternal(
      Rule rule,
      Path outputDirectory,
      BlazeDirectories directories,
      Environment env,
      Map<RepoRecordedInput, String> recordedInputValues,
      SkyKey key)
      throws RepositoryFunctionException, InterruptedException {

    String defInfo = RepositoryResolvedEvent.getRuleDefinitionInformation(rule);
    env.getListener().post(new StarlarkRepositoryDefinitionLocationEvent(rule.getName(), defInfo));

    StarlarkCallable function = rule.getRuleClassObject().getConfiguredTargetFunction();
    if (declareEnvironmentDependencies(recordedInputValues, env, getEnviron(rule)) == null) {
      return null;
    }
    StarlarkSemantics starlarkSemantics = PrecomputedValue.STARLARK_SEMANTICS.get(env);
    if (env.valuesMissing()) {
      return null;
    }

    PathPackageLocator packageLocator = PrecomputedValue.PATH_PACKAGE_LOCATOR.get(env);
    if (env.valuesMissing()) {
      return null;
    }

    boolean enableBzlmod = starlarkSemantics.getBool(BuildLanguageOptions.ENABLE_BZLMOD);
    @Nullable RepositoryMapping mainRepoMapping;
    String ruleClass =
        rule.getRuleClassObject().getRuleDefinitionEnvironmentLabel().getUnambiguousCanonicalForm()
            + "%"
            + rule.getRuleClass();
    if (NonRegistryOverride.BOOTSTRAP_RULE_CLASSES.contains(ruleClass)) {
      // Avoid a cycle.
      mainRepoMapping = null;
    } else if (enableBzlmod || !isWorkspaceRepo(rule)) {
      var mainRepoMappingValue =
          (RepositoryMappingValue)
              env.getValue(RepositoryMappingValue.KEY_FOR_ROOT_MODULE_WITHOUT_WORKSPACE_REPOS);
      if (mainRepoMappingValue == null) {
        return null;
      }
      mainRepoMapping = mainRepoMappingValue.getRepositoryMapping();
    } else {
      mainRepoMapping = rule.getPackage().getRepositoryMapping();
    }

    IgnoredPackagePrefixesValue ignoredPackagesValue =
        (IgnoredPackagePrefixesValue) env.getValue(IgnoredPackagePrefixesValue.key());
    if (env.valuesMissing()) {
      return null;
    }
    ImmutableSet<PathFragment> ignoredPatterns = checkNotNull(ignoredPackagesValue).getPatterns();

    try (Mutability mu = Mutability.create("Starlark repository")) {
      StarlarkThread thread =
          StarlarkThread.create(
              mu, starlarkSemantics, /* contextDescription= */ "", SymbolGenerator.create(key));
      thread.setPrintHandler(Event.makeDebugPrintHandler(env.getListener()));
      var repoMappingRecorder = new Label.RepoMappingRecorder();
      // For repos defined in Bzlmod, record any used repo mappings in the marker file.
      // Repos defined in WORKSPACE are impossible to verify given the chunked loading (we'd have to
      // record which chunk the repo mapping was used in, and ain't nobody got time for that).
      if (!isWorkspaceRepo(rule)) {
        repoMappingRecorder.mergeEntries(
            rule.getRuleClassObject().getRuleDefinitionEnvironmentRepoMappingEntries());
        thread.setThreadLocal(Label.RepoMappingRecorder.class, repoMappingRecorder);
      }

      new BazelStarlarkContext(BazelStarlarkContext.Phase.LOADING, () -> mainRepoMapping)
          .storeInThread(thread); // "fetch"

      StarlarkRepositoryContext starlarkRepositoryContext =
          new StarlarkRepositoryContext(
              rule,
              packageLocator,
              outputDirectory,
              ignoredPatterns,
              env,
              ImmutableMap.copyOf(clientEnvironment),
              downloadManager,
              timeoutScaling,
              processWrapper,
              starlarkSemantics,
              repositoryRemoteExecutor,
              syscallCache,
              directories);

      if (starlarkRepositoryContext.isRemotable()) {
        // If a rule is declared remotable then invalidate it if remote execution gets
        // enabled or disabled.
        PrecomputedValue.REMOTE_EXECUTION_ENABLED.get(env);
      }

      // Since restarting a repository function can be really expensive, we first ensure that
      // all label-arguments can be resolved to paths.
      try {
        starlarkRepositoryContext.enforceLabelAttributes();
      } catch (NeedsSkyframeRestartException e) {
        // Missing values are expected; just restart before we actually start the rule
        return null;
      }

      // This rule is mainly executed for its side effect. Nevertheless, the return value is
      // of importance, as it provides information on how the call has to be modified to be a
      // reproducible rule.
      //
      // Also we do a lot of stuff in there, maybe blocking operations and we should certainly make
      // it possible to return null and not block but it doesn't seem to be easy with Starlark
      // structure as it is.
      Object result;
      boolean fetchSuccessful = false;
      try (SilentCloseable c =
          Profiler.instance()
              .profile(ProfilerTask.STARLARK_REPOSITORY_FN, () -> rule.getLabel().toString())) {
        result =
            Starlark.call(
                thread,
                function,
                /*args=*/ ImmutableList.of(starlarkRepositoryContext),
                /*kwargs=*/ ImmutableMap.of());
        fetchSuccessful = true;
      } finally {
        if (starlarkRepositoryContext.ensureNoPendingAsyncTasks(
            env.getListener(), fetchSuccessful)) {
          if (fetchSuccessful) {
            throw new RepositoryFunctionException(
                new EvalException(
                    "Pending asynchronous work after repository rule finished running"),
                Transience.PERSISTENT);
          }
        }
      }

      RepositoryResolvedEvent resolved =
          new RepositoryResolvedEvent(
              rule, starlarkRepositoryContext.getAttr(), outputDirectory, result);
      if (resolved.isNewInformationReturned()) {
        env.getListener().handle(Event.debug(resolved.getMessage()));
        env.getListener().handle(Event.debug(defInfo));
      }

      // Modify marker data to include the files/dirents/env vars used by the rule's implementation
      // function.
      recordedInputValues.putAll(starlarkRepositoryContext.getRecordedFileInputs());
      recordedInputValues.putAll(starlarkRepositoryContext.getRecordedDirentsInputs());
      recordedInputValues.putAll(starlarkRepositoryContext.getRecordedDirTreeInputs());
      recordedInputValues.putAll(
          Maps.transformValues(
              starlarkRepositoryContext.getRecordedEnvVarInputs(), v -> v.orElse(null)));

      for (Table.Cell<RepositoryName, String, RepositoryName> repoMappings :
          repoMappingRecorder.recordedEntries().cellSet()) {
        recordedInputValues.put(
            new RepoRecordedInput.RecordedRepoMapping(
                repoMappings.getRowKey(), repoMappings.getColumnKey()),
            repoMappings.getValue().getName());
      }

      env.getListener().post(resolved);
    } catch (NeedsSkyframeRestartException e) {
      // A dependency is missing, cleanup and returns null
      try {
        if (outputDirectory.exists()) {
          outputDirectory.deleteTree();
        }
      } catch (IOException e1) {
        throw new RepositoryFunctionException(e1, Transience.TRANSIENT);
      }
      return null;
    } catch (EvalException e) {
      env.getListener()
          .handle(
              Event.error(
                  "An error occurred during the fetch of repository '"
                      + rule.getName()
                      + "':\n   "
                      + e.getMessageWithStack()));
      env.getListener()
          .handle(Event.info(RepositoryResolvedEvent.getRuleDefinitionInformation(rule)));

      throw new RepositoryFunctionException(
          new AlreadyReportedRepositoryAccessException(e), Transience.TRANSIENT);
    }

    if (!outputDirectory.isDirectory()) {
      throw new RepositoryFunctionException(
          new IOException(rule + " must create a directory"), Transience.TRANSIENT);
    }

    // Make sure the fetched repo has a boundary file.
    if (!WorkspaceFileHelper.isValidRepoRoot(outputDirectory)) {
      if (outputDirectory.isSymbolicLink()) {
        // The created repo is actually just a symlink to somewhere else (think local_repository).
        // In this case, we shouldn't try to create the repo boundary file ourselves, but report an
        // error instead.
        throw new RepositoryFunctionException(
            new IOException(
                "No MODULE.bazel, REPO.bazel, or WORKSPACE file found in " + outputDirectory),
            Transience.TRANSIENT);
      }
      // Otherwise, we can just create an empty REPO.bazel file.
      try {
        FileSystemUtils.createEmptyFile(outputDirectory.getRelative(LabelConstants.REPO_FILE_NAME));
        if (starlarkSemantics.getBool(BuildLanguageOptions.ENABLE_WORKSPACE)) {
          FileSystemUtils.createEmptyFile(
              outputDirectory.getRelative(LabelConstants.WORKSPACE_FILE_NAME));
        }
      } catch (IOException e) {
        throw new RepositoryFunctionException(e, Transience.TRANSIENT);
      }
    }

    return RepositoryDirectoryValue.builder().setPath(outputDirectory);
  }

  @SuppressWarnings("unchecked")
  private static ImmutableSet<String> getEnviron(Rule rule) {
    return ImmutableSet.copyOf((Iterable<String>) rule.getAttr("$environ"));
  }

  @Override
  protected boolean isLocal(Rule rule) {
    return (Boolean) rule.getAttr("$local");
  }

  @Override
  protected boolean isConfigure(Rule rule) {
    return (Boolean) rule.getAttr("$configure");
  }

  /**
   * Static method to determine if for a starlark repository rule {@code isConfigure} holds true. It
   * also checks that the rule is indeed a Starlark rule so that this class is the appropriate
   * handler for the given rule. As, however, only Starklark rules can be configure rules, this
   * method can also be used as a universal check.
   */
  public static boolean isConfigureRule(Rule rule) {
    return rule.getRuleClassObject().isStarlark() && ((Boolean) rule.getAttr("$configure"));
  }

  @Nullable
  @Override
  public Class<? extends RuleDefinition> getRuleDefinition() {
    return null; // unused so safe to return null
  }

  public void setRepositoryRemoteExecutor(RepositoryRemoteExecutor repositoryRemoteExecutor) {
    this.repositoryRemoteExecutor = repositoryRemoteExecutor;
  }
}
