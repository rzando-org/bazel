// Copyright 2023 The Bazel Authors. All Rights Reserved.
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

import static com.google.common.base.Preconditions.checkState;
import static com.google.devtools.build.lib.exec.SpawnLogContext.millisToProto;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.base.Utf8;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifactType;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactRoot;
import com.google.devtools.build.lib.actions.ArtifactRoot.RootType;
import com.google.devtools.build.lib.actions.CommandLines.ParamFileActionInput;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.InputMetadataProvider;
import com.google.devtools.build.lib.actions.ParameterFile.ParameterFileType;
import com.google.devtools.build.lib.actions.RunfilesTree;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnMetrics;
import com.google.devtools.build.lib.actions.SpawnResult;
import com.google.devtools.build.lib.actions.SpawnResult.Status;
import com.google.devtools.build.lib.actions.StaticInputMetadataProvider;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.actions.util.ActionsTestUtil;
import com.google.devtools.build.lib.exec.Protos.Digest;
import com.google.devtools.build.lib.exec.Protos.EnvironmentVariable;
import com.google.devtools.build.lib.exec.Protos.File;
import com.google.devtools.build.lib.exec.Protos.Platform;
import com.google.devtools.build.lib.exec.Protos.SpawnExec;
import com.google.devtools.build.lib.exec.util.FakeActionInputFileCache;
import com.google.devtools.build.lib.exec.util.SpawnBuilder;
import com.google.devtools.build.lib.server.FailureDetails.Crash;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.TreeArtifactValue;
import com.google.devtools.build.lib.vfs.DelegateFileSystem;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileSystem;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.protobuf.util.Timestamps;
import com.google.testing.junit.testparameterinjector.TestParameter;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import org.junit.Test;

/** Base class for {@link SpawnLogContext} tests. */
public abstract class SpawnLogContextTestBase {
  protected final DigestHashFunction digestHashFunction = DigestHashFunction.SHA256;
  protected final FileSystem fs = new InMemoryFileSystem(digestHashFunction);
  protected final Path execRoot = fs.getPath("/execroot");
  protected final ArtifactRoot rootDir = ArtifactRoot.asSourceRoot(Root.fromPath(execRoot));
  protected final ArtifactRoot outputDir =
      ArtifactRoot.asDerivedRoot(execRoot, RootType.Output, "out");
  protected final ArtifactRoot middlemanDir =
      ArtifactRoot.asDerivedRoot(execRoot, RootType.Middleman, "middlemen");

  // A fake action filesystem that provides a fast digest, but refuses to compute it from the
  // file contents (which won't be available when building without the bytes).
  protected static final class FakeActionFileSystem extends DelegateFileSystem {
    FakeActionFileSystem(FileSystem delegateFs) {
      super(delegateFs);
    }

    @Override
    protected byte[] getFastDigest(PathFragment path) throws IOException {
      return super.getDigest(path);
    }

    @Override
    protected byte[] getDigest(PathFragment path) throws IOException {
      throw new UnsupportedOperationException();
    }
  }

  /** Test parameter determining whether the spawn inputs are also tool inputs. */
  protected enum InputsMode {
    TOOLS,
    NON_TOOLS;

    boolean isTool() {
      return this == TOOLS;
    }
  }

  /** Test parameter determining whether to emulate building with or without the bytes. */
  protected enum OutputsMode {
    WITH_BYTES,
    WITHOUT_BYTES;

    FileSystem getActionFileSystem(FileSystem fs) {
      return this == WITHOUT_BYTES ? new FakeActionFileSystem(fs) : fs;
    }
  }

  /** Test parameter determining whether an input/output directory should be empty. */
  enum DirContents {
    EMPTY,
    NON_EMPTY;

    boolean isEmpty() {
      return this == EMPTY;
    }
  }

  /** Test parameter determining whether an output is indirected through a symlink. */
  enum OutputIndirection {
    DIRECT,
    INDIRECT;

    boolean viaSymlink() {
      return this == INDIRECT;
    }
  }

  @Test
  public void testFileInput(@TestParameter InputsMode inputsMode) throws Exception {
    Artifact fileInput = ActionsTestUtil.createArtifact(rootDir, "file");

    writeFile(fileInput, "abc");

    SpawnBuilder spawn = defaultSpawnBuilder().withInputs(fileInput);
    if (inputsMode.isTool()) {
      spawn.withTools(fileInput);
    }

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn.build(),
        createInputMetadataProvider(fileInput),
        createInputMap(fileInput),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addInputs(
                File.newBuilder()
                    .setPath("file")
                    .setDigest(getDigest("abc"))
                    .setIsTool(inputsMode.isTool()))
            .build());
  }

  @Test
  public void testFileInputWithDirectoryContents(
      @TestParameter InputsMode inputsMode, @TestParameter DirContents dirContents)
      throws Exception {
    Artifact fileInput = ActionsTestUtil.createArtifact(rootDir, "file");

    fileInput.getPath().createDirectoryAndParents();
    if (!dirContents.isEmpty()) {
      writeFile(fileInput.getPath().getChild("file"), "abc");
    }

    SpawnBuilder spawn = defaultSpawnBuilder().withInputs(fileInput);
    if (inputsMode.isTool()) {
      spawn.withTools(fileInput);
    }

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn.build(),
        createInputMetadataProvider(fileInput),
        createInputMap(fileInput),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addAllInputs(
                dirContents.isEmpty()
                    ? ImmutableList.of()
                    : ImmutableList.of(
                        File.newBuilder()
                            .setPath("file/file")
                            .setDigest(getDigest("abc"))
                            .setIsTool(inputsMode.isTool())
                            .build()))
            .build());
  }

  @Test
  public void testDirectoryInput(
      @TestParameter InputsMode inputsMode, @TestParameter DirContents dirContents)
      throws Exception {
    Artifact dirInput = ActionsTestUtil.createArtifact(rootDir, "dir");

    dirInput.getPath().createDirectoryAndParents();
    if (!dirContents.isEmpty()) {
      writeFile(dirInput.getPath().getChild("file"), "abc");
    }

    SpawnBuilder spawn = defaultSpawnBuilder().withInputs(dirInput);
    if (inputsMode.equals(InputsMode.TOOLS)) {
      spawn.withTools(dirInput);
    }

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn.build(),
        createInputMetadataProvider(dirInput),
        createInputMap(dirInput),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addAllInputs(
                dirContents.isEmpty()
                    ? ImmutableList.of()
                    : ImmutableList.of(
                        File.newBuilder()
                            .setPath("dir/file")
                            .setDigest(getDigest("abc"))
                            .setIsTool(inputsMode.isTool())
                            .build()))
            .build());
  }

  @Test
  public void testTreeInput(
      @TestParameter InputsMode inputsMode, @TestParameter DirContents dirContents)
      throws Exception {
    SpecialArtifact treeInput =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputDir, "tree");

    treeInput.getPath().createDirectoryAndParents();
    if (!dirContents.isEmpty()) {
      writeFile(treeInput.getPath().getChild("child"), "abc");
    }

    SpawnBuilder spawn = defaultSpawnBuilder().withInputs(treeInput);
    if (inputsMode.isTool()) {
      spawn.withTools(treeInput);
    }

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn.build(),
        createInputMetadataProvider(treeInput),
        createInputMap(treeInput),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addAllInputs(
                dirContents.isEmpty()
                    ? ImmutableList.of()
                    : ImmutableList.of(
                        File.newBuilder()
                            .setPath("out/tree/child")
                            .setDigest(getDigest("abc"))
                            .setIsTool(inputsMode.isTool())
                            .build()))
            .build());
  }

  @Test
  public void testUnresolvedSymlinkInput(@TestParameter InputsMode inputsMode) throws Exception {
    Artifact symlinkInput = ActionsTestUtil.createUnresolvedSymlinkArtifact(outputDir, "symlink");

    symlinkInput.getPath().getParentDirectory().createDirectoryAndParents();
    symlinkInput.getPath().createSymbolicLink(PathFragment.create("/some/path"));

    SpawnBuilder spawn = defaultSpawnBuilder().withInputs(symlinkInput);
    if (inputsMode.isTool()) {
      spawn.withTools(symlinkInput);
    }

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn.build(),
        createInputMetadataProvider(symlinkInput),
        createInputMap(symlinkInput),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addInputs(
                File.newBuilder()
                    .setPath("out/symlink")
                    .setSymlinkTargetPath("/some/path")
                    .setIsTool(inputsMode.isTool()))
            .build());
  }

  @Test
  public void testRunfilesFileInput() throws Exception {
    Artifact runfilesInput = ActionsTestUtil.createArtifact(rootDir, "data.txt");
    Artifact runfilesMiddleman = ActionsTestUtil.createArtifact(middlemanDir, "runfiles");

    writeFile(runfilesInput, "abc");

    PathFragment runfilesRoot = outputDir.getExecPath().getRelative("foo.runfiles");
    RunfilesTree runfilesTree =
        createRunfilesTree(runfilesRoot, ImmutableMap.of("data.txt", runfilesInput));

    Spawn spawn = defaultSpawnBuilder().withInput(runfilesMiddleman).build();

    SpawnLogContext context = createSpawnLogContext();

    FakeActionInputFileCache inputMetadataProvider = new FakeActionInputFileCache();
    inputMetadataProvider.putRunfilesTree(runfilesMiddleman, runfilesTree);
    inputMetadataProvider.put(runfilesInput, FileArtifactValue.createForTesting(runfilesInput));

    context.logSpawn(
        spawn,
        inputMetadataProvider,
        createInputMap(runfilesTree),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addInputs(
                File.newBuilder().setPath("out/foo.runfiles/data.txt").setDigest(getDigest("abc")))
            .build());
  }

  @Test
  public void testRunfilesDirectoryInput(@TestParameter DirContents dirContents) throws Exception {
    Artifact runfilesMiddleman = ActionsTestUtil.createArtifact(middlemanDir, "runfiles");
    Artifact runfilesInput = ActionsTestUtil.createArtifact(rootDir, "dir");

    runfilesInput.getPath().createDirectoryAndParents();
    if (!dirContents.isEmpty()) {
      writeFile(runfilesInput.getPath().getChild("data.txt"), "abc");
    }

    PathFragment runfilesRoot = outputDir.getExecPath().getRelative("foo.runfiles");
    RunfilesTree runfilesTree =
        createRunfilesTree(runfilesRoot, ImmutableMap.of("dir", runfilesInput));

    Spawn spawn = defaultSpawnBuilder().withInput(runfilesMiddleman).build();

    SpawnLogContext context = createSpawnLogContext();

    FakeActionInputFileCache inputMetadataProvider = new FakeActionInputFileCache();
    inputMetadataProvider.putRunfilesTree(runfilesMiddleman, runfilesTree);
    inputMetadataProvider.put(runfilesInput, FileArtifactValue.createForTesting(runfilesInput));

    context.logSpawn(
        spawn,
        inputMetadataProvider,
        createInputMap(runfilesTree),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addAllInputs(
                dirContents.isEmpty()
                    ? ImmutableList.of()
                    : ImmutableList.of(
                        File.newBuilder()
                            .setPath("out/foo.runfiles/dir/data.txt")
                            .setDigest(getDigest("abc"))
                            .build()))
            .build());
  }

  @Test
  public void testRunfilesEmptyInput() throws Exception {
    Artifact runfilesMiddleman = ActionsTestUtil.createArtifact(middlemanDir, "runfiles");
    PathFragment runfilesRoot = outputDir.getExecPath().getRelative("foo.runfiles");
    HashMap<String, Artifact> mapping = new HashMap<>();
    mapping.put("__init__.py", null);
    RunfilesTree runfilesTree = createRunfilesTree(runfilesRoot, mapping);

    Spawn spawn = defaultSpawnBuilder().withInput(runfilesMiddleman).build();

    SpawnLogContext context = createSpawnLogContext();

    FakeActionInputFileCache inputMetadataProvider = new FakeActionInputFileCache();
    inputMetadataProvider.putRunfilesTree(runfilesMiddleman, runfilesTree);

    context.logSpawn(
        spawn,
        inputMetadataProvider,
        createInputMap(runfilesTree),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addInputs(File.newBuilder().setPath("out/foo.runfiles/__init__.py"))
            .build());
  }

  @Test
  public void testFilesetInput(@TestParameter DirContents dirContents) throws Exception {
    Artifact filesetInput =
        SpecialArtifact.create(
            outputDir,
            outputDir.getExecPath().getRelative("dir"),
            ActionsTestUtil.NULL_ARTIFACT_OWNER,
            SpecialArtifactType.FILESET);

    filesetInput.getPath().createDirectoryAndParents();
    if (!dirContents.isEmpty()) {
      writeFile(fs.getPath("/file.txt"), "abc");
      filesetInput
          .getPath()
          .getChild("file.txt")
          .createSymbolicLink(PathFragment.create("/file.txt"));
    }

    Spawn spawn =
        defaultSpawnBuilder()
            .withInput(filesetInput)
            // The implementation only relies on the map keys, so the value can be empty.
            .withFilesetMapping(filesetInput, ImmutableList.of())
            .build();

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn,
        createInputMetadataProvider(filesetInput),
        createInputMap(filesetInput),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addAllInputs(
                dirContents.isEmpty()
                    ? ImmutableList.of()
                    : ImmutableList.of(
                        File.newBuilder()
                            .setPath("out/dir/file.txt")
                            .setDigest(getDigest("abc"))
                            .build()))
            .build());
  }

  @Test
  public void testParamFileInput() throws Exception {
    ParamFileActionInput paramFileInput =
        new ParamFileActionInput(
            PathFragment.create("foo.params"),
            ImmutableList.of("a", "b", "c"),
            ParameterFileType.UNQUOTED,
            UTF_8);

    // Do not materialize the file on disk, which would be the case when running remotely.
    SpawnBuilder spawn = defaultSpawnBuilder().withInputs(paramFileInput);

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn.build(),
        // ParamFileActionInputs appear in the input map but not in the metadata provider.
        createInputMetadataProvider(),
        createInputMap(paramFileInput),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addInputs(File.newBuilder().setPath("foo.params").setDigest(getDigest("a\nb\nc\n")))
            .build());
  }

  @Test
  public void testFileOutput(
      @TestParameter OutputsMode outputsMode, @TestParameter OutputIndirection indirection)
      throws Exception {
    Artifact fileOutput = ActionsTestUtil.createArtifact(outputDir, "file");

    Path actualPath =
        indirection.viaSymlink()
            ? outputDir.getRoot().asPath().getChild("actual")
            : fileOutput.getPath();

    if (indirection.viaSymlink()) {
      fileOutput.getPath().getParentDirectory().createDirectoryAndParents();
      fileOutput.getPath().createSymbolicLink(actualPath);
    }

    writeFile(actualPath, "abc");

    Spawn spawn = defaultSpawnBuilder().withOutputs(fileOutput).build();

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn,
        createInputMetadataProvider(),
        createInputMap(),
        outputsMode.getActionFileSystem(fs),
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addListedOutputs("out/file")
            .addActualOutputs(File.newBuilder().setPath("out/file").setDigest(getDigest("abc")))
            .build());
  }

  @Test
  public void testFileOutputWithDirectoryContents(@TestParameter OutputsMode outputsMode)
      throws Exception {
    Artifact fileOutput = ActionsTestUtil.createArtifact(outputDir, "file");

    fileOutput.getPath().createDirectoryAndParents();
    writeFile(fileOutput.getPath().getChild("file"), "abc");

    SpawnBuilder spawn = defaultSpawnBuilder().withOutputs(fileOutput);

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn.build(),
        createInputMetadataProvider(),
        createInputMap(),
        outputsMode.getActionFileSystem(fs),
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addListedOutputs("out/file")
            .addActualOutputs(
                File.newBuilder().setPath("out/file/file").setDigest(getDigest("abc")))
            .build());
  }

  @Test
  public void testTreeOutput(
      @TestParameter OutputsMode outputsMode,
      @TestParameter DirContents dirContents,
      @TestParameter OutputIndirection indirection)
      throws Exception {
    SpecialArtifact treeOutput =
        ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputDir, "tree");

    Path actualPath =
        indirection.viaSymlink()
            ? outputDir.getRoot().asPath().getChild("actual")
            : treeOutput.getPath();

    if (indirection.viaSymlink()) {
      treeOutput.getPath().getParentDirectory().createDirectoryAndParents();
      treeOutput.getPath().createSymbolicLink(actualPath);
    }

    actualPath.createDirectoryAndParents();
    if (!dirContents.isEmpty()) {
      Path firstChildPath = actualPath.getRelative("dir1/file1");
      Path secondChildPath = actualPath.getRelative("dir2/file2");
      firstChildPath.getParentDirectory().createDirectoryAndParents();
      secondChildPath.getParentDirectory().createDirectoryAndParents();
      writeFile(firstChildPath, "abc");
      writeFile(secondChildPath, "def");
      Path emptySubdirPath = actualPath.getRelative("dir3");
      emptySubdirPath.createDirectoryAndParents();
    }

    Spawn spawn = defaultSpawnBuilder().withOutputs(treeOutput).build();

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn,
        createInputMetadataProvider(),
        createInputMap(),
        outputsMode.getActionFileSystem(fs),
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addListedOutputs("out/tree")
            .addAllActualOutputs(
                dirContents.isEmpty()
                    ? ImmutableList.of()
                    : ImmutableList.of(
                        File.newBuilder()
                            .setPath("out/tree/dir1/file1")
                            .setDigest(getDigest("abc"))
                            .build(),
                        File.newBuilder()
                            .setPath("out/tree/dir2/file2")
                            .setDigest(getDigest("def"))
                            .build()))
            .build());
  }

  @Test
  public void testTreeOutputWithInvalidType(@TestParameter OutputsMode outputsMode)
      throws Exception {
    Artifact treeOutput = ActionsTestUtil.createTreeArtifactWithGeneratingAction(outputDir, "tree");

    writeFile(treeOutput, "abc");

    SpawnBuilder spawn = defaultSpawnBuilder().withOutputs(treeOutput);

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn.build(),
        createInputMetadataProvider(),
        createInputMap(),
        outputsMode.getActionFileSystem(fs),
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(context, defaultSpawnExecBuilder().addListedOutputs("out/tree").build());
  }

  @Test
  public void testUnresolvedSymlinkOutput(@TestParameter OutputsMode outputsMode) throws Exception {
    Artifact symlinkOutput = ActionsTestUtil.createUnresolvedSymlinkArtifact(outputDir, "symlink");

    symlinkOutput.getPath().getParentDirectory().createDirectoryAndParents();
    symlinkOutput.getPath().createSymbolicLink(PathFragment.create("/some/path"));

    SpawnBuilder spawn = defaultSpawnBuilder().withOutputs(symlinkOutput);

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn.build(),
        createInputMetadataProvider(),
        createInputMap(),
        outputsMode.getActionFileSystem(fs),
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addListedOutputs("out/symlink")
            .addActualOutputs(
                File.newBuilder().setPath("out/symlink").setSymlinkTargetPath("/some/path"))
            .build());
  }

  @Test
  public void testUnresolvedSymlinkOutputWithInvalidType(@TestParameter OutputsMode outputsMode)
      throws Exception {
    Artifact symlinkOutput = ActionsTestUtil.createUnresolvedSymlinkArtifact(outputDir, "symlink");

    writeFile(symlinkOutput, "abc");

    SpawnBuilder spawn = defaultSpawnBuilder().withOutputs(symlinkOutput);

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn.build(),
        createInputMetadataProvider(),
        createInputMap(),
        outputsMode.getActionFileSystem(fs),
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(context, defaultSpawnExecBuilder().addListedOutputs("out/symlink").build());
  }

  @Test
  public void testMissingOutput(@TestParameter OutputsMode outputsMode) throws Exception {
    Artifact missingOutput = ActionsTestUtil.createArtifact(outputDir, "missing");

    SpawnBuilder spawn = defaultSpawnBuilder().withOutputs(missingOutput);

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn.build(),
        createInputMetadataProvider(),
        createInputMap(),
        outputsMode.getActionFileSystem(fs),
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(context, defaultSpawnExecBuilder().addListedOutputs("out/missing").build());
  }

  @Test
  public void testEnvironment() throws Exception {
    Spawn spawn =
        defaultSpawnBuilder().withEnvironment("SPAM", "eggs").withEnvironment("FOO", "bar").build();

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn,
        createInputMetadataProvider(),
        createInputMap(),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .addEnvironmentVariables(
                EnvironmentVariable.newBuilder().setName("FOO").setValue("bar"))
            .addEnvironmentVariables(
                EnvironmentVariable.newBuilder().setName("SPAM").setValue("eggs"))
            .build());
  }

  @Test
  public void testDefaultPlatformProperties() throws Exception {
    SpawnLogContext context = createSpawnLogContext(ImmutableMap.of("a", "1", "b", "2"));

    context.logSpawn(
        defaultSpawn(),
        createInputMetadataProvider(),
        createInputMap(),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .setPlatform(
                Platform.newBuilder()
                    .addProperties(Platform.Property.newBuilder().setName("a").setValue("1"))
                    .addProperties(Platform.Property.newBuilder().setName("b").setValue("2"))
                    .build())
            .build());
  }

  @Test
  public void testSpawnPlatformProperties() throws Exception {
    Spawn spawn =
        defaultSpawnBuilder().withExecProperties(ImmutableMap.of("a", "3", "c", "4")).build();

    SpawnLogContext context = createSpawnLogContext(ImmutableMap.of("a", "1", "b", "2"));

    context.logSpawn(
        spawn,
        createInputMetadataProvider(),
        createInputMap(),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    // The spawn properties should override the default properties.
    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .setPlatform(
                Platform.newBuilder()
                    .addProperties(Platform.Property.newBuilder().setName("a").setValue("3"))
                    .addProperties(Platform.Property.newBuilder().setName("b").setValue("2"))
                    .addProperties(Platform.Property.newBuilder().setName("c").setValue("4"))
                    .build())
            .build());
  }

  @Test
  public void testExecutionInfo(
      @TestParameter({"no-remote", "no-cache", "no-remote-cache"}) String requirement)
      throws Exception {
    Spawn spawn = defaultSpawnBuilder().withExecutionInfo(requirement, "").build();

    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        spawn,
        createInputMetadataProvider(),
        createInputMap(),
        fs,
        defaultTimeout(),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .setRemotable(!requirement.equals("no-remote"))
            .setCacheable(!requirement.equals("no-cache"))
            .setRemoteCacheable(
                !requirement.equals("no-cache")
                    && !requirement.equals("no-remote")
                    && !requirement.equals("no-remote-cache"))
            .build());
  }

  @Test
  public void testCacheHit() throws Exception {
    SpawnLogContext context = createSpawnLogContext();

    SpawnResult result = defaultSpawnResultBuilder().setCacheHit(true).build();

    context.logSpawn(
        defaultSpawn(),
        createInputMetadataProvider(),
        createInputMap(),
        fs,
        defaultTimeout(),
        result);

    closeAndAssertLog(context, defaultSpawnExecBuilder().setCacheHit(true).build());
  }

  @Test
  public void testDigest() throws Exception {
    SpawnLogContext context = createSpawnLogContext();

    Digest digest = getDigest("something");

    SpawnResult result = defaultSpawnResultBuilder().setDigest(digest).build();

    context.logSpawn(
        defaultSpawn(),
        createInputMetadataProvider(),
        createInputMap(),
        fs,
        defaultTimeout(),
        result);

    closeAndAssertLog(context, defaultSpawnExecBuilder().setDigest(digest).build());
  }

  @Test
  public void testTimeout() throws Exception {
    SpawnLogContext context = createSpawnLogContext();

    context.logSpawn(
        defaultSpawn(),
        createInputMetadataProvider(),
        createInputMap(),
        fs,
        /* timeout= */ Duration.ofSeconds(42),
        defaultSpawnResult());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder().setTimeoutMillis(Duration.ofSeconds(42).toMillis()).build());
  }

  @Test
  public void testSpawnMetrics() throws Exception {
    SpawnMetrics metrics = SpawnMetrics.Builder.forLocalExec().setTotalTimeInMs(1).build();

    SpawnLogContext context = createSpawnLogContext();

    Instant now = Instant.now();
    context.logSpawn(
        defaultSpawn(),
        createInputMetadataProvider(),
        createInputMap(),
        fs,
        defaultTimeout(),
        defaultSpawnResultBuilder().setSpawnMetrics(metrics).setStartTime(now).build());

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .setMetrics(
                Protos.SpawnMetrics.newBuilder()
                    .setTotalTime(millisToProto(1))
                    .setStartTime(Timestamps.fromDate(Date.from(now))))
            .build());
  }

  @Test
  public void testStatus() throws Exception {
    SpawnLogContext context = createSpawnLogContext();

    // SpawnResult requires a non-zero exit code and non-null failure details when the status isn't
    // successful.
    SpawnResult result =
        defaultSpawnResultBuilder()
            .setStatus(Status.NON_ZERO_EXIT)
            .setExitCode(37)
            .setFailureDetail(
                FailureDetail.newBuilder()
                    .setMessage("oops")
                    .setCrash(Crash.getDefaultInstance())
                    .build())
            .build();

    context.logSpawn(
        defaultSpawn(),
        createInputMetadataProvider(),
        createInputMap(),
        fs,
        defaultTimeout(),
        result);

    closeAndAssertLog(
        context,
        defaultSpawnExecBuilder()
            .setExitCode(37)
            .setStatus(Status.NON_ZERO_EXIT.toString())
            .build());
  }

  protected static Duration defaultTimeout() {
    return Duration.ZERO;
  }

  protected static SpawnBuilder defaultSpawnBuilder() {
    return new SpawnBuilder("cmd", "--opt");
  }

  protected static Spawn defaultSpawn() {
    return defaultSpawnBuilder().build();
  }

  protected static SpawnResult.Builder defaultSpawnResultBuilder() {
    return new SpawnResult.Builder().setRunnerName("runner").setStatus(Status.SUCCESS);
  }

  protected static SpawnResult defaultSpawnResult() {
    return defaultSpawnResultBuilder().build();
  }

  protected static SpawnExec.Builder defaultSpawnExecBuilder() {
    return SpawnExec.newBuilder()
        .addCommandArgs("cmd")
        .addCommandArgs("--opt")
        .setRunner("runner")
        .setRemotable(true)
        .setCacheable(true)
        .setRemoteCacheable(true)
        .setMnemonic("Mnemonic")
        .setTargetLabel("//dummy:label")
        .setMetrics(Protos.SpawnMetrics.getDefaultInstance());
  }

  protected static RunfilesTree createRunfilesTree(
      PathFragment root, Map<String, Artifact> mapping) {
    HashMap<PathFragment, Artifact> mappingByPath = new HashMap<>();
    for (Map.Entry<String, Artifact> entry : mapping.entrySet()) {
      mappingByPath.put(PathFragment.create(entry.getKey()), entry.getValue());
    }
    RunfilesTree runfilesTree = mock(RunfilesTree.class);
    when(runfilesTree.getExecPath()).thenReturn(root);
    when(runfilesTree.getMapping()).thenReturn(mappingByPath);
    return runfilesTree;
  }

  protected static InputMetadataProvider createInputMetadataProvider(Artifact... artifacts)
      throws Exception {
    ImmutableMap.Builder<ActionInput, FileArtifactValue> builder = ImmutableMap.builder();
    for (Artifact artifact : artifacts) {
      if (artifact.isTreeArtifact()) {
        // Emulate ActionInputMap: add both tree and children.
        TreeArtifactValue treeMetadata = createTreeArtifactValue(artifact);
        builder.put(artifact, treeMetadata.getMetadata());
        for (Map.Entry<TreeFileArtifact, FileArtifactValue> entry :
            treeMetadata.getChildValues().entrySet()) {
          builder.put(entry.getKey(), entry.getValue());
        }
      } else if (artifact.isSymlink()) {
        builder.put(artifact, FileArtifactValue.createForUnresolvedSymlink(artifact));
      } else {
        builder.put(artifact, FileArtifactValue.createForTesting(artifact));
      }
    }
    return new StaticInputMetadataProvider(builder.buildOrThrow());
  }

  protected static SortedMap<PathFragment, ActionInput> createInputMap(ActionInput... actionInputs)
      throws Exception {
    return createInputMap(null, actionInputs);
  }

  protected static SortedMap<PathFragment, ActionInput> createInputMap(
      RunfilesTree runfilesTree, ActionInput... actionInputs) throws Exception {
    ImmutableSortedMap.Builder<PathFragment, ActionInput> builder =
        ImmutableSortedMap.naturalOrder();

    if (runfilesTree != null) {
      // Emulate SpawnInputExpander: expand runfiles, replacing nulls with empty inputs.
      PathFragment root = runfilesTree.getExecPath();
      for (Map.Entry<PathFragment, Artifact> entry : runfilesTree.getMapping().entrySet()) {
        PathFragment execPath = root.getRelative(entry.getKey());
        Artifact artifact = entry.getValue();
        builder.put(execPath, artifact != null ? artifact : VirtualActionInput.EMPTY_MARKER);
      }
    }

    for (ActionInput actionInput : actionInputs) {
      if (actionInput instanceof Artifact artifact && artifact.isTreeArtifact()) {
        // Emulate SpawnInputExpander: expand to children, preserve if empty.
        TreeArtifactValue treeMetadata = createTreeArtifactValue(artifact);
        if (treeMetadata.getChildren().isEmpty()) {
          builder.put(artifact.getExecPath(), artifact);
        } else {
          for (TreeFileArtifact child : treeMetadata.getChildren()) {
            builder.put(child.getExecPath(), child);
          }
        }
      } else {
        builder.put(actionInput.getExecPath(), actionInput);
      }
    }
    return builder.buildOrThrow();
  }

  protected static TreeArtifactValue createTreeArtifactValue(Artifact tree) throws Exception {
    checkState(tree.isTreeArtifact());
    TreeArtifactValue.Builder builder = TreeArtifactValue.newBuilder((SpecialArtifact) tree);
    TreeArtifactValue.visitTree(
        tree.getPath(),
        (parentRelativePath, type, traversedSymlink) -> {
          if (type.equals(Dirent.Type.DIRECTORY)) {
            return;
          }
          TreeFileArtifact child =
              TreeFileArtifact.createTreeOutput((SpecialArtifact) tree, parentRelativePath);
          builder.putChild(child, FileArtifactValue.createForTesting(child));
        });
    return builder.build();
  }

  protected SpawnLogContext createSpawnLogContext() throws IOException, InterruptedException {
    return createSpawnLogContext(ImmutableSortedMap.of());
  }

  protected abstract SpawnLogContext createSpawnLogContext(
      ImmutableMap<String, String> platformProperties) throws IOException, InterruptedException;

  protected Digest getDigest(String content) {
    return Digest.newBuilder()
        .setHash(digestHashFunction.getHashFunction().hashString(content, UTF_8).toString())
        .setSizeBytes(Utf8.encodedLength(content))
        .setHashFunctionName(digestHashFunction.toString())
        .build();
  }

  protected static void writeFile(Artifact artifact, String contents) throws IOException {
    writeFile(artifact.getPath(), contents);
  }

  protected static void writeFile(Path path, String contents) throws IOException {
    path.getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContent(path, UTF_8, contents);
  }

  protected abstract void closeAndAssertLog(SpawnLogContext context, SpawnExec... expected)
      throws IOException, InterruptedException;
}
