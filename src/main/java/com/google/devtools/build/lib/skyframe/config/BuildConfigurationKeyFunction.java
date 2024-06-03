// Copyright 2024 The Bazel Authors. All rights reserved.
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

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.producers.BuildConfigurationKeyProducer;
import com.google.devtools.build.lib.analysis.producers.BuildConfigurationKeyProducer.ResultSink;
import com.google.devtools.build.lib.skyframe.toolchains.PlatformLookupUtil.InvalidPlatformException;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.state.Driver;
import com.google.devtools.build.skyframe.state.StateMachine;
import com.google.devtools.common.options.OptionsParsingException;
import javax.annotation.Nullable;

/** Function that returns a fully updated {@link BuildConfigurationKey}. */
public class BuildConfigurationKeyFunction implements SkyFunction {
  /**
   * {@link BuildConfigurationKeyProducer} works on a {@code Map<String, BuildOptions>}, but this
   * skyfunction only operates on a single {@link BuildOptions}, so this static key is used to
   * create that map and read the resulting {@link BuildConfigurationKey}.
   */
  private static final String BUILD_OPTIONS_MAP_SINGLETON_KEY = "key";

  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws BuildConfigurationKeyFunctionException, InterruptedException {
    // Delegate all work to BuildConfigurationKeyProducer.
    BuildConfigurationKeyValue.Key key = (BuildConfigurationKeyValue.Key) skyKey.argument();
    BuildOptions buildOptions = key.buildOptions();
    Sink sink = new Sink();
    Driver driver =
        new Driver(
            new BuildConfigurationKeyProducer(
                sink,
                /* runAfter= */ StateMachine.DONE,
                ImmutableMap.of(BUILD_OPTIONS_MAP_SINGLETON_KEY, buildOptions)));

    boolean complete = driver.drive(env);

    try {
      // Check for exceptions before returning whether to restart.
      sink.checkErrors();
      if (!complete) {
        return null;
      }

      BuildConfigurationKey buildConfigurationKey = sink.getKey();
      return BuildConfigurationKeyValue.create(buildConfigurationKey);
    } catch (OptionsParsingException e) {
      throw new BuildConfigurationKeyFunctionException(e);
    } catch (PlatformMappingException e) {
      throw new BuildConfigurationKeyFunctionException(e);
    } catch (InvalidPlatformException e) {
      throw new BuildConfigurationKeyFunctionException(e);
    }
  }

  /** Sink implementation to handle results from {@link BuildConfigurationKeyProducer}. */
  private static final class Sink implements ResultSink {
    @Nullable private ImmutableMap<String, BuildConfigurationKey> transitionedOptions;
    @Nullable private OptionsParsingException transitionError;
    @Nullable private PlatformMappingException platformMappingException;
    @Nullable private InvalidPlatformException invalidPlatformException;

    @Override
    public void acceptTransitionError(OptionsParsingException e) {
      this.transitionError = e;
    }

    @Override
    public void acceptPlatformMappingError(PlatformMappingException e) {
      this.platformMappingException = e;
    }

    @Override
    public void acceptPlatformFlagsError(InvalidPlatformException e) {
      this.invalidPlatformException = e;
    }

    @Override
    public void acceptTransitionedConfigurations(
        ImmutableMap<String, BuildConfigurationKey> transitionedOptions) {
      this.transitionedOptions = transitionedOptions;
    }

    void checkErrors()
        throws OptionsParsingException, PlatformMappingException, InvalidPlatformException {
      if (this.transitionError != null) {
        throw this.transitionError;
      }
      if (this.platformMappingException != null) {
        throw this.platformMappingException;
      }
      if (this.invalidPlatformException != null) {
        throw this.invalidPlatformException;
      }
    }

    BuildConfigurationKey getKey() {
      if (this.transitionedOptions != null) {
        return this.transitionedOptions.get(BUILD_OPTIONS_MAP_SINGLETON_KEY);
      }
      throw new IllegalStateException("No exceptions or result value found");
    }
  }

  /** Exception type for errors while creating the {@link BuildConfigurationKeyValue}. */
  public static final class BuildConfigurationKeyFunctionException extends SkyFunctionException {

    public BuildConfigurationKeyFunctionException(OptionsParsingException optionsParsingException) {
      super(optionsParsingException, Transience.PERSISTENT);
    }

    public BuildConfigurationKeyFunctionException(
        PlatformMappingException platformMappingException) {
      super(platformMappingException, Transience.PERSISTENT);
    }

    public BuildConfigurationKeyFunctionException(
        InvalidPlatformException invalidPlatformException) {
      super(invalidPlatformException, Transience.PERSISTENT);
    }
  }
}
