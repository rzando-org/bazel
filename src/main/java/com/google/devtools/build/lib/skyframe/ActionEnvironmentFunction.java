// Copyright 2017 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.skyframe;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.bugreport.BugReport;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.skyframe.AbstractSkyKey;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Skyframe function that provides the effective value for a client environment variable. This will
 * either be the value coming from the default client environment, or the value coming from the
 * --action_env flag, if the variable's value is explicitly set.
 */
public final class ActionEnvironmentFunction implements SkyFunction {
  @Nullable
  @Override
  public SkyValue compute(SkyKey skyKey, Environment env) throws InterruptedException {
    Map<String, String> actionEnv = PrecomputedValue.ACTION_ENV.get(env);
    String key = (String) skyKey.argument();
    if (actionEnv.containsKey(key) && actionEnv.get(key) != null) {
      return new ClientEnvironmentValue(actionEnv.get(key));
    }
    return env.getValue(ClientEnvironmentFunction.key(key));
  }

  /** Returns the SkyKey to invoke this function for the environment variable {@code variable}. */
  public static Key key(String variable) {
    return Key.create(variable);
  }

  @VisibleForSerialization
  @AutoCodec
  static class Key extends AbstractSkyKey<String> {
    private static final SkyKeyInterner<Key> interner = SkyKey.newInterner();

    private Key(String arg) {
      super(arg);
    }

    private static Key create(String arg) {
      return interner.intern(new Key(arg));
    }

    @VisibleForSerialization
    @AutoCodec.Interner
    static Key intern(Key key) {
      return interner.intern(key);
    }

    @Override
    public SkyFunctionName functionName() {
      return SkyFunctions.ACTION_ENVIRONMENT_VARIABLE;
    }

    @Override
    public SkyKeyInterner<Key> getSkyKeyInterner() {
      return interner;
    }
  }

  /**
   * Returns a map of environment variable key => values, getting them from Skyframe. Returns null
   * if and only if some dependencies from Skyframe still need to be resolved.
   */
  @Nullable
  public static ImmutableMap<String, Optional<String>> getEnvironmentView(
      Environment env, Set<String> keys) throws InterruptedException {
    var skyframeKeys = keys.stream().map(ActionEnvironmentFunction::key).collect(toImmutableSet());
    SkyframeLookupResult values = env.getValuesAndExceptions(skyframeKeys);
    if (env.valuesMissing()) {
      return null;
    }

    ImmutableMap.Builder<String, Optional<String>> result =
        ImmutableMap.builderWithExpectedSize(skyframeKeys.size());
    for (SkyKey key : skyframeKeys) {
      ClientEnvironmentValue value = (ClientEnvironmentValue) values.get(key);
      if (value == null) {
        BugReport.sendBugReport(
            new IllegalStateException(
                "ClientEnvironmentValue " + key + " was missing, this should never happen"));
        return null;
      }
      result.put(key.argument().toString(), Optional.ofNullable(value.getValue()));
    }
    return result.buildOrThrow();
  }
}
