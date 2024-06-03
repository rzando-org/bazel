// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages;

import com.google.auto.value.AutoValue;
import com.google.devtools.build.lib.cmdline.BazelModuleContext;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.RepositoryMapping;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Collection;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkThread;

/** Static utility methods pertaining to restricting Starlark method invocations */
// TODO(bazel-team): Maybe we can merge this utility class with some other existing allowlist
// helper? But it seems like a lot of existing allowlist machinery is geared toward allowlists on
// rule attributes rather than what .bzl you're in.
public final class BuiltinRestriction {

  private BuiltinRestriction() {}

  /**
   * Throws {@code EvalException} if the innermost Starlark function in the given thread's call
   * stack is not defined within the builtins repository.
   *
   * @throws NullPointerException if there is no currently executing Starlark function, or the
   *     innermost Starlark function's module is not a .bzl file
   */
  public static void failIfCalledOutsideBuiltins(StarlarkThread thread) throws EvalException {
    Label currentFile = BazelModuleContext.ofInnermostBzlOrThrow(thread).label();
    if (!currentFile.getRepository().getName().equals("_builtins")) {
      throw Starlark.errorf(
          "file '%s' cannot use private @_builtins API", currentFile.getCanonicalForm());
    }
  }

  /**
   * An entry in an allowlist that can be checked using {@link #failIfCalledOutsideAllowlist} or
   * {@link #failIfModuleOutsideAllowlist}.
   */
  @AutoValue
  public abstract static class AllowlistEntry {
    abstract String apparentRepoName();

    abstract PathFragment packagePrefix();

    static AllowlistEntry create(String apparentRepoName, PathFragment packagePrefix) {
      return new AutoValue_BuiltinRestriction_AllowlistEntry(apparentRepoName, packagePrefix);
    }

    final boolean allows(Label label, RepositoryMapping repoMapping) {
      return label.getRepository().equals(repoMapping.get(apparentRepoName()))
          && label.getPackageFragment().startsWith(packagePrefix());
    }
  }

  /**
   * Creates an {@link AllowlistEntry}. This is essentially an unresolved package identifier; that
   * is, a package identifier that has an apparent repo name in place of a canonical repo name.
   */
  public static AllowlistEntry allowlistEntry(String apparentRepoName, String packagePrefix) {
    return AllowlistEntry.create(apparentRepoName, PathFragment.create(packagePrefix));
  }

  /**
   * Throws {@code EvalException} if the innermost Starlark function in the given thread's call
   * stack is not defined within either 1) the builtins repository, or 2) a package or subpackage of
   * an entry in the given allowlist.
   *
   * @throws NullPointerException if there is no currently executing Starlark function, or the
   *     innermost Starlark function's module is not a .bzl file
   */
  public static void failIfCalledOutsideAllowlist(
      StarlarkThread thread, Collection<AllowlistEntry> allowlist) throws EvalException {
    failIfModuleOutsideAllowlist(BazelModuleContext.ofInnermostBzlOrThrow(thread), allowlist);
  }

  /**
   * Throws {@code EvalException} if the given {@link BazelModuleContext} is not within either 1)
   * the builtins repository, or 2) a package or subpackage of an entry in the given allowlist.
   */
  public static void failIfModuleOutsideAllowlist(
      BazelModuleContext moduleContext, Collection<AllowlistEntry> allowlist) throws EvalException {
    failIfLabelOutsideAllowlist(moduleContext.label(), moduleContext.repoMapping(), allowlist);
  }

  /**
   * Throws {@code EvalException} if the given {@link Label} is not within either 1) the builtins
   * repository, or 2) a package or subpackage of an entry in the given allowlist.
   */
  public static void failIfLabelOutsideAllowlist(
      Label label, RepositoryMapping repoMapping, Collection<AllowlistEntry> allowlist)
      throws EvalException {
    if (label.getRepository().getName().equals("_builtins")) {
      return;
    }
    if (allowlist.stream().noneMatch(e -> e.allows(label, repoMapping))) {
      throw Starlark.errorf("file '%s' cannot use private API", label.getCanonicalForm());
    }
  }
}
