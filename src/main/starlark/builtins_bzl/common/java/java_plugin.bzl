# Copyright 2021 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""
Definition of java_plugin rule.
"""

load(":common/java/android_lint.bzl", "android_lint_subrule")
load(":common/java/basic_java_library.bzl", "basic_java_library", "construct_defaultinfo")
load(":common/java/java_info.bzl", "JavaPluginInfo")
load(":common/java/java_library.bzl", "JAVA_LIBRARY_ATTRS", "JAVA_LIBRARY_IMPLICIT_ATTRS")
load(":common/java/java_semantics.bzl", "semantics")
load(":common/rule_util.bzl", "merge_attrs")

def bazel_java_plugin_rule(
        ctx,
        srcs = [],
        data = [],
        generates_api = False,
        processor_class = "",
        deps = [],
        plugins = [],
        resources = [],
        javacopts = [],
        neverlink = False,
        proguard_specs = [],
        add_exports = [],
        add_opens = []):
    """Implements java_plugin rule.

    Use this call when you need to produce a fully fledged java_plugin from
    another rule's implementation.

    Args:
      ctx: (RuleContext) Used to register the actions.
      srcs: (list[File]) The list of source files that are processed to create the target.
      data: (list[File]) The list of files needed by this plugin at runtime.
      generates_api: (bool) This attribute marks annotation processors that generate API code.
      processor_class: (str) The processor class is the fully qualified type of
        the class that the Java compiler should use as entry point to the annotation processor.
      deps: (list[Target]) The list of other libraries to be linked in to the target.
      plugins: (list[Target]) Java compiler plugins to run at compile-time.
      resources: (list[File]) A list of data files to include in a Java jar.
      javacopts: (list[str]) Extra compiler options for this library.
      neverlink: (bool) Whether this library should only be used for compilation and not at runtime.
      proguard_specs: (list[File]) Files to be used as Proguard specification.
      add_exports: (list[str]) Allow this library to access the given <module>/<package>.
      add_opens: (list[str]) Allow this library to reflectively access the given <module>/<package>.
    Returns:
      (list[provider]) A list containing DefaultInfo, JavaInfo,
        InstrumentedFilesInfo, OutputGroupsInfo, ProguardSpecProvider providers.
    """
    target, base_info = basic_java_library(
        ctx,
        srcs,
        deps,
        [],  # runtime_deps
        plugins,
        [],  # exports
        [],  # exported_plugins
        resources,
        [],  # resource_jars
        [],  # classpath_resources
        javacopts,
        neverlink,
        proguard_specs = proguard_specs,
        add_exports = add_exports,
        add_opens = add_opens,
    )
    java_info = target.pop("JavaInfo")

    # Replace JavaInfo with JavaPluginInfo
    target["JavaPluginInfo"] = JavaPluginInfo(
        runtime_deps = [java_info],
        processor_class = processor_class if processor_class else None,  # ignore empty string (default)
        data = data,
        generates_api = generates_api,
    )
    target["DefaultInfo"] = construct_defaultinfo(
        ctx,
        base_info.files_to_build,
        base_info.runfiles,
        neverlink,
    )
    target["OutputGroupInfo"] = OutputGroupInfo(**base_info.output_groups)

    return target

def _proxy(ctx):
    return bazel_java_plugin_rule(
        ctx,
        ctx.files.srcs,
        ctx.files.data,
        ctx.attr.generates_api,
        ctx.attr.processor_class,
        ctx.attr.deps,
        ctx.attr.plugins,
        ctx.files.resources,
        ctx.attr.javacopts,
        ctx.attr.neverlink,
        ctx.files.proguard_specs,
        ctx.attr.add_exports,
        ctx.attr.add_opens,
    ).values()

JAVA_PLUGIN_ATTRS = merge_attrs(
    JAVA_LIBRARY_ATTRS,
    {
        "generates_api": attr.bool(doc = """
This attribute marks annotation processors that generate API code.
<p>If a rule uses an API-generating annotation processor, other rules
depending on it can refer to the generated code only if their
compilation actions are scheduled after the generating rule. This
attribute instructs Bazel to introduce scheduling constraints when
--java_header_compilation is enabled.
<p><em class="harmful">WARNING: This attribute affects build
performance, use it only if necessary.</em></p>
        """),
        "processor_class": attr.string(doc = """
The processor class is the fully qualified type of the class that the Java compiler should
use as entry point to the annotation processor. If not specified, this rule will not
contribute an annotation processor to the Java compiler's annotation processing, but its
runtime classpath will still be included on the compiler's annotation processor path. (This
is primarily intended for use by
<a href="https://errorprone.info/docs/plugins">Error Prone plugins</a>, which are loaded
from the annotation processor path using
<a href="https://docs.oracle.com/javase/8/docs/api/java/util/ServiceLoader.html">
java.util.ServiceLoader</a>.)
       """),
        "output_licenses": attr.license() if hasattr(attr, "license") else attr.string_list(),
    },
    remove_attrs = ["runtime_deps", "exports", "exported_plugins"],
)

JAVA_PLUGIN_IMPLICIT_ATTRS = JAVA_LIBRARY_IMPLICIT_ATTRS

java_plugin = rule(
    _proxy,
    doc = """
<p>
  <code>java_plugin</code> defines plugins for the Java compiler run by Bazel. The
  only supported kind of plugins are annotation processors. A <code>java_library</code> or
  <code>java_binary</code> rule can run plugins by depending on them via the <code>plugins</code>
  attribute. A <code>java_library</code> can also automatically export plugins to libraries that
  directly depend on it using
  <code><a href="${link java_library.exported_plugins}">exported_plugins</a></code>.
</p>

<h4 id="java_plugin_implicit_outputs">Implicit output targets</h4>
    <ul>
      <li><code><var>libname</var>.jar</code>: A Java archive.</li>
    </ul>

<p>
  Arguments are identical to <a href="${link java_library}"><code>java_library</code></a>, except
  for the addition of the <code>processor_class</code> argument.
</p>
    """,
    attrs = merge_attrs(
        JAVA_PLUGIN_ATTRS,
        JAVA_PLUGIN_IMPLICIT_ATTRS,
    ),
    provides = [JavaPluginInfo],
    outputs = {
        "classjar": "lib%{name}.jar",
        "sourcejar": "lib%{name}-src.jar",
    },
    fragments = ["java", "cpp"],
    toolchains = [semantics.JAVA_TOOLCHAIN],
    subrules = [android_lint_subrule],
)
