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

package com.google.devtools.build.lib.rules.proto;

import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.packages.BuiltinRestriction;
import com.google.devtools.build.lib.packages.semantics.BuildLanguageOptions;
import com.google.devtools.build.lib.starlarkbuildapi.proto.ProtoCommonApi;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.StarlarkThread;

/** Protocol buffers support for Starlark. */
public class BazelProtoCommon implements ProtoCommonApi {
  public static final BazelProtoCommon INSTANCE = new BazelProtoCommon();

  protected BazelProtoCommon() {}

  @StarlarkMethod(
      name = "incompatible_enable_proto_toolchain_resolution",
      useStarlarkThread = true,
      documented = false)
  public boolean getDefineProtoToolchains(StarlarkThread thread) throws EvalException {
    BuiltinRestriction.failIfCalledOutsideAllowlist(thread, ImmutableSet.of());
    return thread
        .getSemantics()
        .getBool(BuildLanguageOptions.INCOMPATIBLE_ENABLE_PROTO_TOOLCHAIN_RESOLUTION);
  }
}
