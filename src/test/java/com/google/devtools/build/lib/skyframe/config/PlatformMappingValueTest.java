// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.config;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.PlatformOptions;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsParsingException;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link PlatformMappingValue}. */
@RunWith(JUnit4.class)
public final class PlatformMappingValueTest {

  private static final Label PLATFORM_ONE = Label.parseCanonicalUnchecked("//platforms:one");
  private static final Label PLATFORM_TWO =
      Label.parseCanonicalUnchecked("@dep~v1.0//platforms:two");
  private static final RepositoryMapping REPO_MAPPING =
      RepositoryMapping.create(
          ImmutableMap.of(
              "", RepositoryName.MAIN, "dep", RepositoryName.createUnvalidated("dep~v1.0")),
          RepositoryName.MAIN);
  private static final Label DEFAULT_TARGET_PLATFORM =
      Label.parseCanonicalUnchecked("@bazel_tools//tools:host_platform");

  /** Extra options for this test. */
  public static class DummyTestOptions extends FragmentOptions {
    public DummyTestOptions() {}

    @Option(
        name = "str_option",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "defVal")
    public String strOption;

    @Option(
        name = "other_str_option",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.NO_OP},
        defaultValue = "defVal")
    public String otherStrOption;
  }

  private static final ImmutableSet<Class<? extends FragmentOptions>> BUILD_CONFIG_OPTIONS =
      // PlatformOptions is required for mapping.
      ImmutableSet.of(PlatformOptions.class, DummyTestOptions.class);

  private BuildOptions createBuildOptions(String... args) throws OptionsParsingException {
    return BuildOptions.of(BUILD_CONFIG_OPTIONS, args);
  }

  private static PlatformMappingBuilder builder() {
    return new PlatformMappingBuilder();
  }

  private static class PlatformMappingBuilder {
    private final Map<Label, NativeAndStarlarkFlags> platformsToFlags = new HashMap<>();
    private final Map<NativeAndStarlarkFlags, Label> flagsToPlatforms = new HashMap<>();

    @CanIgnoreReturnValue
    public PlatformMappingBuilder addPlatform(Label platform, NativeAndStarlarkFlags flags) {
      this.platformsToFlags.put(platform, flags);
      return this;
    }

    @CanIgnoreReturnValue
    public PlatformMappingBuilder addPlatform(Label platform, String... nativeFlags) {
      return this.addPlatform(platform, createFlags(nativeFlags));
    }

    @CanIgnoreReturnValue
    public PlatformMappingBuilder addFlags(NativeAndStarlarkFlags flags, Label platform) {
      this.flagsToPlatforms.put(flags, platform);
      return this;
    }

    @CanIgnoreReturnValue
    public PlatformMappingBuilder addFlags(Label platform, String... nativeFlags) {
      return this.addFlags(createFlags(nativeFlags), platform);
    }

    public PlatformMappingValue build() {
      return new PlatformMappingValue(
          ImmutableMap.copyOf(platformsToFlags),
          ImmutableMap.copyOf(flagsToPlatforms),
          BUILD_CONFIG_OPTIONS);
    }

    private NativeAndStarlarkFlags createFlags(String... nativeFlags) {
      return NativeAndStarlarkFlags.builder()
          .nativeFlags(ImmutableList.copyOf(nativeFlags))
          .optionsClasses(BUILD_CONFIG_OPTIONS)
          .repoMapping(REPO_MAPPING)
          .build();
    }
  }

  @Test
  public void map_noMappings() throws OptionsParsingException {
    PlatformMappingValue mappingValue = builder().build();

    BuildOptions mapped = mappingValue.map(createBuildOptions());

    assertThat(mapped.get(PlatformOptions.class).platforms)
        .containsExactly(DEFAULT_TARGET_PLATFORM);
  }

  @Test
  public void map_platformToFlags() throws Exception {
    PlatformMappingValue mappingValue =
        builder().addPlatform(PLATFORM_ONE, "--str_option=one", "--other_str_option=dbg").build();

    BuildOptions modifiedOptions = createBuildOptions("--platforms=//platforms:one");

    BuildOptions mapped = mappingValue.map(modifiedOptions);

    assertThat(mapped.get(DummyTestOptions.class).strOption).isEqualTo("one");
  }

  @Test
  public void map_flagsToPlatform() throws Exception {
    PlatformMappingValue mappingValue =
        builder().addFlags(PLATFORM_ONE, "--str_option=one", "--other_str_option=dbg").build();

    BuildOptions modifiedOptions = createBuildOptions("--str_option=one", "--other_str_option=dbg");
    BuildOptions mapped = mappingValue.map(modifiedOptions);

    assertThat(mapped.get(PlatformOptions.class).platforms).containsExactly(PLATFORM_ONE);
  }

  @Test
  public void map_flagsToPlatform_checkPriority() throws Exception {
    PlatformMappingValue mappingValue =
        builder()
            .addFlags(PLATFORM_ONE, "--str_option=one", "--other_str_option=dbg")
            .addFlags(PLATFORM_TWO, "--str_option=two")
            .build();

    BuildOptions modifiedOptions = createBuildOptions("--str_option=two");

    BuildOptions mapped = mappingValue.map(modifiedOptions);

    assertThat(mapped.get(PlatformOptions.class).platforms).containsExactly(PLATFORM_TWO);
  }

  @Test
  public void map_flagsToPlatform_noneMatching() throws Exception {
    PlatformMappingValue mappingValue =
        builder().addFlags(PLATFORM_ONE, "--str_option=foo", "--other_str_option=dbg").build();

    BuildOptions modifiedOptions = createBuildOptions("--str_option=bar");

    BuildOptions mapped = mappingValue.map(modifiedOptions);

    assertThat(mapped.get(PlatformOptions.class).platforms)
        .containsExactly(DEFAULT_TARGET_PLATFORM);
  }

  @Test
  public void map_noPlatformOptions() throws Exception {
    PlatformMappingValue mappingValue = builder().build();

    // Does not contain PlatformOptions.
    BuildOptions options = BuildOptions.of(ImmutableList.of());
    assertThrows(IllegalArgumentException.class, () -> mappingValue.map(options));
  }

  @Test
  public void map_noMappingIfPlatformIsSetButNotMatching() throws Exception {
    PlatformMappingValue mappingValue =
        builder()
            // Add a mapping for a different platform.
            .addPlatform(PLATFORM_ONE, "--str_option=one", "--other_str_option=dbg")
            .build();

    BuildOptions modifiedOptions =
        createBuildOptions("--str_option=one", "--platforms=//platforms:two");
    BuildOptions mapped = mappingValue.map(modifiedOptions);

    // No change because the platform is not in the mapping.
    assertThat(modifiedOptions).isEqualTo(mapped);
  }

  @Test
  public void map_noMappingIfPlatformIsSetAndNoPlatformMapping() throws Exception {
    PlatformMappingValue mappingValue =
        builder()
            // Add a flag mapping that would match.
            .addFlags(PLATFORM_ONE, "--str_option=one")
            .build();

    BuildOptions modifiedOptions =
        createBuildOptions("--str_option=one", "--platforms=//platforms:two");

    BuildOptions mapped = mappingValue.map(modifiedOptions);

    // No change because the platform is not in the mapping.
    assertThat(modifiedOptions).isEqualTo(mapped);
  }

  @Test
  public void defaultKey() {
    PlatformMappingValue.Key key = PlatformMappingValue.Key.create(null);

    assertThat(key.getWorkspaceRelativeMappingPath())
        .isEqualTo(PlatformOptions.DEFAULT_PLATFORM_MAPPINGS);
    assertThat(key.wasExplicitlySetByUser()).isFalse();
  }

  @Test
  public void customKey() {
    PlatformMappingValue.Key key = PlatformMappingValue.Key.create(PathFragment.create("my/path"));

    assertThat(key.getWorkspaceRelativeMappingPath()).isEqualTo(PathFragment.create("my/path"));
    assertThat(key.wasExplicitlySetByUser()).isTrue();
  }
}
