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
package com.google.devtools.build.lib.exec;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.github.luben.zstd.ZstdOutputStream;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.CommandLines.ParamFileActionInput;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.actions.RunfilesTree;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.Spawns;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.concurrent.AbstractQueueVisitor;
import com.google.devtools.build.lib.concurrent.ErrorClassifier;
import com.google.devtools.build.lib.concurrent.NamedForkJoinPool;
import com.google.devtools.build.lib.exec.Protos.Digest;
import com.google.devtools.build.lib.exec.Protos.ExecLogEntry;
import com.google.devtools.build.lib.exec.Protos.Platform;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.remote.options.RemoteOptions;
import com.google.devtools.build.lib.util.io.AsynchronousMessageOutputStream;
import com.google.devtools.build.lib.util.io.MessageOutputStream;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.IORuntimeException;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.XattrProvider;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.ForkJoinPool;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;

/** A {@link SpawnLogContext} implementation that produces a log in compact format. */
public class CompactSpawnLogContext extends SpawnLogContext {

  private static final Comparator<ExecLogEntry.File> EXEC_LOG_ENTRY_FILE_COMPARATOR =
      Comparator.comparing(ExecLogEntry.File::getPath);

  private static final ForkJoinPool VISITOR_POOL =
      NamedForkJoinPool.newNamedPool(
          "execlog-directory-visitor", Runtime.getRuntime().availableProcessors());

  /** Visitor for use in {@link #visitDirectory}. */
  protected interface DirectoryChildVisitor {
    void visit(Path path) throws IOException;
  }

  private static class DirectoryVisitor extends AbstractQueueVisitor {
    private final Path rootDir;
    private final DirectoryChildVisitor childVisitor;

    private DirectoryVisitor(Path rootDir, DirectoryChildVisitor childVisitor) {
      super(
          VISITOR_POOL,
          ExecutorOwnership.SHARED,
          ExceptionHandlingMode.FAIL_FAST,
          ErrorClassifier.DEFAULT);
      this.rootDir = checkNotNull(rootDir);
      this.childVisitor = checkNotNull(childVisitor);
    }

    private void run() throws IOException, InterruptedException {
      execute(() -> visitSubdirectory(rootDir));
      try {
        awaitQuiescence(true);
      } catch (IORuntimeException e) {
        throw e.getCauseIOException();
      }
    }

    private void visitSubdirectory(Path dir) {
      try {
        for (Dirent dirent : dir.readdir(Symlinks.FOLLOW)) {
          Path child = dir.getChild(dirent.getName());
          if (dirent.getType() == Dirent.Type.DIRECTORY) {
            execute(() -> visitSubdirectory(child));
            continue;
          }
          childVisitor.visit(child);
        }
      } catch (IOException e) {
        throw new IORuntimeException(e);
      }
    }
  }

  /**
   * Visits a directory hierarchy in parallel.
   *
   * <p>Calls {@code childVisitor} for every descendant path of {@code rootDir} that isn't itself a
   * directory, following symlinks. The visitor may be concurrently called by multiple threads, and
   * must synchronize accesses to shared data.
   */
  private void visitDirectory(Path rootDir, DirectoryChildVisitor childVisitor)
      throws IOException, InterruptedException {
    new DirectoryVisitor(rootDir, childVisitor).run();
  }

  private interface ExecLogEntrySupplier {
    ExecLogEntry.Builder get() throws IOException, InterruptedException;
  }

  private final PathFragment execRoot;
  @Nullable private final RemoteOptions remoteOptions;
  private final DigestHashFunction digestHashFunction;
  private final XattrProvider xattrProvider;

  // Maps a key identifying an entry into its ID.
  // Each key is either a NestedSet.Node or the String path of a file, directory or symlink.
  // Only entries that are likely to be referenced by future entries are stored.
  // Use a specialized map for minimal memory footprint.
  @GuardedBy("this")
  private final Object2IntOpenHashMap<Object> entryMap = new Object2IntOpenHashMap<>();

  // The next available entry ID.
  @GuardedBy("this")
  int nextEntryId = 1;

  // Output stream to write to.
  private final MessageOutputStream<ExecLogEntry> outputStream;

  public CompactSpawnLogContext(
      Path outputPath,
      PathFragment execRoot,
      @Nullable RemoteOptions remoteOptions,
      DigestHashFunction digestHashFunction,
      XattrProvider xattrProvider)
      throws IOException, InterruptedException {
    this.execRoot = execRoot;
    this.remoteOptions = remoteOptions;
    this.digestHashFunction = digestHashFunction;
    this.xattrProvider = xattrProvider;
    this.outputStream = getOutputStream(outputPath);

    logInvocation();
  }

  private static MessageOutputStream<ExecLogEntry> getOutputStream(Path path) throws IOException {
    // Use an AsynchronousMessageOutputStream so that compression and I/O occur in a separate
    // thread. This ensures concurrent writes don't tear and avoids blocking execution.
    return new AsynchronousMessageOutputStream<>(
        path.toString(), new ZstdOutputStream(new BufferedOutputStream(path.getOutputStream())));
  }

  private void logInvocation() throws IOException, InterruptedException {
    logEntry(
        null,
        () ->
            ExecLogEntry.newBuilder()
                .setInvocation(
                    ExecLogEntry.Invocation.newBuilder()
                        .setHashFunctionName(digestHashFunction.toString())));
  }

  @Override
  public boolean shouldPublish() {
    // The compact log is small enough to be uploaded to a remote store.
    return true;
  }

  @Override
  public void logSpawn(
      Spawn spawn,
      InputMetadataProvider inputMetadataProvider,
      SortedMap<PathFragment, ActionInput> inputMap,
      FileSystem fileSystem,
      Duration timeout,
      SpawnResult result)
      throws IOException, InterruptedException, ExecException {
    try (SilentCloseable c = Profiler.instance().profile("logSpawn")) {
      ExecLogEntry.Spawn.Builder builder = ExecLogEntry.Spawn.newBuilder();

      builder.addAllArgs(spawn.getArguments());
      builder.addAllEnvVars(getEnvironmentVariables(spawn));
      Platform platform = getPlatform(spawn, remoteOptions);
      if (platform != null) {
        builder.setPlatform(platform);
      }

      builder.setInputSetId(logInputs(spawn, inputMetadataProvider, fileSystem));
      builder.setToolSetId(logTools(spawn, inputMetadataProvider, fileSystem));

      builder.setTargetLabel(spawn.getTargetLabel().getCanonicalForm());
      builder.setMnemonic(spawn.getMnemonic());

      for (ActionInput output : spawn.getOutputFiles()) {
        Path path = fileSystem.getPath(execRoot.getRelative(output.getExecPath()));
        if (!output.isDirectory() && !output.isSymlink() && path.isFile()) {
          builder.addOutputs(
              ExecLogEntry.Output.newBuilder()
                  .setFileId(logFile(output, path, inputMetadataProvider)));
        } else if (!output.isSymlink() && path.isDirectory()) {
          // TODO(tjgq): Tighten once --incompatible_disallow_unsound_directory_outputs is gone.
          builder.addOutputs(
              ExecLogEntry.Output.newBuilder()
                  .setDirectoryId(logDirectory(output, path, inputMetadataProvider)));
        } else if (output.isSymlink() && path.isSymbolicLink()) {
          builder.addOutputs(
              ExecLogEntry.Output.newBuilder()
                  .setUnresolvedSymlinkId(logUnresolvedSymlink(output, path)));
        } else {
          builder.addOutputs(
              ExecLogEntry.Output.newBuilder().setInvalidOutputPath(output.getExecPathString()));
        }
      }

      builder.setExitCode(result.exitCode());
      if (result.status() != SpawnResult.Status.SUCCESS) {
        builder.setStatus(result.status().toString());
      }
      builder.setRunner(result.getRunnerName());
      builder.setCacheHit(result.isCacheHit());
      builder.setRemotable(Spawns.mayBeExecutedRemotely(spawn));
      builder.setCacheable(Spawns.mayBeCached(spawn));
      builder.setRemoteCacheable(Spawns.mayBeCachedRemotely(spawn));

      if (result.getDigest() != null) {
        builder.setDigest(result.getDigest().toBuilder().clearHashFunctionName().build());
      }

      builder.setTimeoutMillis(timeout.toMillis());
      builder.setMetrics(getSpawnMetricsProto(result));

      try (SilentCloseable c1 = Profiler.instance().profile("logEntry")) {
        logEntry(null, () -> ExecLogEntry.newBuilder().setSpawn(builder));
      }
    }
  }

  /**
   * Logs the inputs.
   *
   * @return the entry ID of the {@link ExecLogEntry.Set} describing the inputs, or 0 if there are
   *     no inputs.
   */
  private int logInputs(
      Spawn spawn, InputMetadataProvider inputMetadataProvider, FileSystem fileSystem)
      throws IOException, InterruptedException {

    // Add filesets as additional direct members of the top-level nested set of inputs. This
    // prevents it from being shared, but experimentally, the top-level input nested set for a spawn
    // is almost never a transitive member of other nested sets, and not recording its entry ID
    // turns out to be a very significant memory optimization.

    ImmutableList.Builder<Integer> additionalDirectoryIds = ImmutableList.builder();

    for (Artifact fileset : spawn.getFilesetMappings().keySet()) {
      // The fileset symlink tree is always materialized on disk.
      additionalDirectoryIds.add(
          logDirectory(
              fileset,
              fileSystem.getPath(execRoot.getRelative(fileset.getExecPath())),
              inputMetadataProvider));
    }

    return logNestedSet(
        spawn.getInputFiles(),
        additionalDirectoryIds.build(),
        inputMetadataProvider,
        fileSystem,
        /* shared= */ false);
  }

  /**
   * Logs the tool inputs.
   *
   * @return the entry ID of the {@link ExecLogEntry.Set} describing the tool inputs, or 0 if there
   *     are no tool inputs.
   */
  private int logTools(
      Spawn spawn, InputMetadataProvider inputMetadataProvider, FileSystem fileSystem)
      throws IOException, InterruptedException {
    return logNestedSet(
        spawn.getToolFiles(),
        ImmutableList.of(),
        inputMetadataProvider,
        fileSystem,
        /* shared= */ true);
  }

  /**
   * Logs a nested set.
   *
   * @param set the nested set
   * @param additionalDirectoryIds the entry IDs of additional {@link ExecLogEntry.Directory}
   *     entries to include as direct members
   * @param shared whether this nested set is likely to be a transitive member of other sets
   * @return the entry ID of the {@link ExecLogEntry.InputSet} describing the nested set, or 0 if
   *     the nested set is empty.
   */
  private int logNestedSet(
      NestedSet<? extends ActionInput> set,
      Collection<Integer> additionalDirectoryIds,
      InputMetadataProvider inputMetadataProvider,
      FileSystem fileSystem,
      boolean shared)
      throws IOException, InterruptedException {
    if (set.isEmpty() && additionalDirectoryIds.isEmpty()) {
      return 0;
    }

    return logEntry(
        shared ? set.toNode() : null,
        () -> {
          ExecLogEntry.InputSet.Builder builder =
              ExecLogEntry.InputSet.newBuilder().addAllDirectoryIds(additionalDirectoryIds);

          for (NestedSet<? extends ActionInput> transitive : set.getNonLeaves()) {
            checkState(!transitive.isEmpty());
            builder.addTransitiveSetIds(
                logNestedSet(
                    transitive,
                    /* additionalDirectoryIds= */ ImmutableList.of(),
                    inputMetadataProvider,
                    fileSystem,
                    /* shared= */ true));
          }

          for (ActionInput input : set.getLeaves()) {
            if (input instanceof Artifact && ((Artifact) input).isMiddlemanArtifact()) {
              RunfilesTree runfilesTree =
                  inputMetadataProvider.getRunfilesMetadata(input).getRunfilesTree();
              builder.addDirectoryIds(
                  // The runfiles symlink tree might not have been materialized on disk, so use the
                  // mapping.
                  logRunfilesDirectory(
                      runfilesTree.getExecPath(),
                      runfilesTree.getMapping(),
                      inputMetadataProvider,
                      fileSystem));
              continue;
            }

            // Filesets are logged separately.
            if (input instanceof Artifact && ((Artifact) input).isFileset()) {
              continue;
            }

            Path path = fileSystem.getPath(execRoot.getRelative(input.getExecPath()));
            if (isInputDirectory(input, path, inputMetadataProvider)) {
              builder.addDirectoryIds(logDirectory(input, path, inputMetadataProvider));
            } else if (input.isSymlink()) {
              builder.addUnresolvedSymlinkIds(logUnresolvedSymlink(input, path));
            } else {
              builder.addFileIds(logFile(input, path, inputMetadataProvider));
            }
          }

          return ExecLogEntry.newBuilder().setInputSet(builder);
        });
  }

  /**
   * Logs a file.
   *
   * @param input the input representing the file.
   * @param path the path to the file, which must have already been verified to be of the correct
   *     type.
   * @return the entry ID of the {@link ExecLogEntry.File} describing the file.
   */
  private int logFile(ActionInput input, Path path, InputMetadataProvider inputMetadataProvider)
      throws IOException, InterruptedException {
    checkState(!(input instanceof VirtualActionInput.EmptyActionInput));

    return logEntry(
        // A ParamFileActionInput is never shared between spawns.
        input instanceof ParamFileActionInput ? null : input.getExecPathString(),
        () -> {
          ExecLogEntry.File.Builder builder = ExecLogEntry.File.newBuilder();

          builder.setPath(input.getExecPathString());

          Digest digest =
              computeDigest(
                  input,
                  path,
                  inputMetadataProvider,
                  xattrProvider,
                  digestHashFunction,
                  /* includeHashFunctionName= */ false);

          builder.setDigest(digest);

          return ExecLogEntry.newBuilder().setFile(builder);
        });
  }

  /**
   * Logs a directory.
   *
   * <p>This may be either a source directory, a fileset or an output directory. For runfiles,
   * {@link #logRunfilesDirectory} must be used instead.
   *
   * @param input the input representing the directory.
   * @param root the path to the directory, which must have already been verified to be of the
   *     correct type.
   * @return the entry ID of the {@link ExecLogEntry.Directory} describing the directory.
   */
  private int logDirectory(
      ActionInput input, Path root, InputMetadataProvider inputMetadataProvider)
      throws IOException, InterruptedException {
    return logEntry(
        input.getExecPathString(),
        () ->
            ExecLogEntry.newBuilder()
                .setDirectory(
                    ExecLogEntry.Directory.newBuilder()
                        .setPath(input.getExecPathString())
                        .addAllFiles(
                            expandDirectory(root, /* pathPrefix= */ null, inputMetadataProvider))));
  }

  /**
   * Logs a runfiles directory.
   *
   * <p>We can't use {@link #logDirectory} because the runfiles symlink tree might not have been
   * materialized on disk. We must follow the mappings to the actual location of the artifacts.
   *
   * @param root the path to the runfiles directory
   * @param mapping a map from runfiles-relative path to the underlying artifact, or null for an
   *     empty file
   * @return the entry ID of the {@link ExecLogEntry.Directory} describing the directory.
   */
  private int logRunfilesDirectory(
      PathFragment root,
      Map<PathFragment, Artifact> mapping,
      InputMetadataProvider inputMetadataProvider,
      FileSystem fileSystem)
      throws IOException, InterruptedException {
    return logEntry(
        root.getPathString(),
        () -> {
          ExecLogEntry.Directory.Builder builder =
              ExecLogEntry.Directory.newBuilder().setPath(root.getPathString());

          for (Map.Entry<PathFragment, Artifact> entry : mapping.entrySet()) {
            String runfilesPath = entry.getKey().getPathString();
            Artifact input = entry.getValue();

            if (input == null) {
              // Empty file.
              builder.addFiles(ExecLogEntry.File.newBuilder().setPath(runfilesPath));
              continue;
            }

            Path path = fileSystem.getPath(execRoot.getRelative(input.getExecPath()));

            if (isInputDirectory(input, path, inputMetadataProvider)) {
              builder.addAllFiles(expandDirectory(path, runfilesPath, inputMetadataProvider));
              continue;
            }

            Digest digest =
                computeDigest(
                    input,
                    path,
                    inputMetadataProvider,
                    xattrProvider,
                    digestHashFunction,
                    /* includeHashFunctionName= */ false);

            builder.addFiles(
                ExecLogEntry.File.newBuilder().setPath(runfilesPath).setDigest(digest));
          }

          return ExecLogEntry.newBuilder().setDirectory(builder);
        });
  }

  /**
   * Expands a directory.
   *
   * @param root the path to the directory
   * @param pathPrefix a prefix to prepend to each child path
   * @return the list of files transitively contained in the directory
   */
  private List<ExecLogEntry.File> expandDirectory(
      Path root, @Nullable String pathPrefix, InputMetadataProvider inputMetadataProvider)
      throws IOException, InterruptedException {
    ArrayList<ExecLogEntry.File> files = new ArrayList<>();
    visitDirectory(
        root,
        (child) -> {
          String childPath = pathPrefix != null ? pathPrefix + "/" : "";
          childPath += child.relativeTo(root).getPathString();

          Digest digest =
              computeDigest(
                  /* input= */ null,
                  child,
                  inputMetadataProvider,
                  xattrProvider,
                  digestHashFunction,
                  /* includeHashFunctionName= */ false);

          ExecLogEntry.File file =
              ExecLogEntry.File.newBuilder().setPath(childPath).setDigest(digest).build();

          synchronized (files) {
            files.add(file);
          }
        });

    Collections.sort(files, EXEC_LOG_ENTRY_FILE_COMPARATOR);

    return files;
  }

  /**
   * Logs an unresolved symlink.
   *
   * @param input the input representing the unresolved symlink.
   * @param path the path to the unresolved symlink, which must have already been verified to be of
   *     the correct type.
   * @return the entry ID of the {@link ExecLogEntry.UnresolvedSymlink} describing the unresolved
   *     symlink.
   */
  private int logUnresolvedSymlink(ActionInput input, Path path)
      throws IOException, InterruptedException {
    return logEntry(
        input.getExecPathString(),
        () ->
            ExecLogEntry.newBuilder()
                .setUnresolvedSymlink(
                    ExecLogEntry.UnresolvedSymlink.newBuilder()
                        .setPath(input.getExecPathString())
                        .setTargetPath(path.readSymbolicLink().getPathString())));
  }

  /**
   * Ensures an entry is written to the log and returns its assigned ID.
   *
   * <p>If an entry with the same non-null key was previously added to the log, its recorded ID is
   * returned. Otherwise, the entry is computed, assigned an ID, and written to the log.
   *
   * @param key the key, or null if the ID shouldn't be recorded
   * @param supplier called to compute the entry; may cause other entries to be logged
   * @return the entry ID
   */
  @CanIgnoreReturnValue
  private synchronized int logEntry(@Nullable Object key, ExecLogEntrySupplier supplier)
      throws IOException, InterruptedException {
    try (SilentCloseable c = Profiler.instance().profile("logEntry/synchronized")) {
      if (key == null) {
        // No need to check for a previously added entry.
        int id = nextEntryId++;
        outputStream.write(supplier.get().setId(id).build());
        return id;
      }

      checkState(key instanceof NestedSet.Node || key instanceof String);

      // Check for a previously added entry.
      int id = entryMap.getOrDefault(key, 0);
      if (id != 0) {
        return id;
      }

      // Compute a fresh entry and log it.
      // The following order of operations is crucial to ensure that this entry is preceded by any
      // entries it references, which in turn ensures the log can be parsed in a single pass.
      ExecLogEntry.Builder entry = supplier.get();
      id = nextEntryId++;
      entryMap.put(key, id);
      outputStream.write(entry.setId(id).build());
      return id;
    }
  }

  @Override
  public void close() throws IOException {
    outputStream.close();
  }
}
