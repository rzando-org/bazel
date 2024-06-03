# Copyright 2023 The Bazel Authors. All rights reserved.
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
Definition of j2objc_library rule.
"""

load(":common/cc/cc_common.bzl", "cc_common")
load(":common/cc/cc_helper.bzl", "cc_helper")
load(":common/cc/cc_info.bzl", "CcInfo")
load(":common/cc/semantics.bzl", "semantics")
load(":common/objc/compilation_support.bzl", "compilation_support")
load(":common/objc/j2objc_aspect.bzl", "j2objc_aspect")
load(":common/objc/providers.bzl", "J2ObjcEntryClassInfo", "J2ObjcMappingFileInfo")
load(":common/objc/semantics.bzl", objc_semantics = "semantics")

_MIGRATION_TAG = "__J2OBJC_LIBRARY_MIGRATION_DO_NOT_USE_WILL_BREAK__"

def _jre_deps_aspect_impl(_, ctx):
    if "j2objc_jre_lib" not in ctx.rule.attr.tags:
        fail("in jre_deps attribute of j2objc_library rule: objc_library rule '%s' is misplaced here (Only J2ObjC JRE libraries are allowed)" %
             str(ctx.label).removeprefix("@"))
    return []

jre_deps_aspect = aspect(
    implementation = _jre_deps_aspect_impl,
)

def _check_entry_classes(ctx):
    entry_classes = ctx.attr.entry_classes
    remove_dead_code = ctx.fragments.j2objc.remove_dead_code()
    if remove_dead_code and not entry_classes:
        fail("Entry classes must be specified when flag --compilation_mode=opt is on in order to perform J2ObjC dead code stripping.")

def _entry_class_provider(entry_classes, deps):
    transitive_entry_classes = [dep[J2ObjcEntryClassInfo].entry_classes for dep in deps if J2ObjcEntryClassInfo in dep]
    return J2ObjcEntryClassInfo(entry_classes = depset(entry_classes, transitive = transitive_entry_classes))

def _mapping_file_provider(deps):
    infos = [dep[J2ObjcMappingFileInfo] for dep in deps if J2ObjcMappingFileInfo in dep]
    transitive_header_mapping_files = [info.header_mapping_files for info in infos]
    transitive_class_mapping_files = [info.class_mapping_files for info in infos]
    transitive_dependency_mapping_files = [info.dependency_mapping_files for info in infos]
    transitive_archive_source_mapping_files = [info.archive_source_mapping_files for info in infos]

    return J2ObjcMappingFileInfo(
        header_mapping_files = depset([], transitive = transitive_header_mapping_files),
        class_mapping_files = depset([], transitive = transitive_class_mapping_files),
        dependency_mapping_files = depset([], transitive = transitive_dependency_mapping_files),
        archive_source_mapping_files = depset([], transitive = transitive_archive_source_mapping_files),
    )

def j2objc_library_lockdown(ctx):
    if not ctx.fragments.j2objc.j2objc_library_migration():
        return
    if _MIGRATION_TAG not in ctx.attr.tags:
        fail("j2objc_library is locked. Please do not use this rule since it will be deleted in the future.")

def _j2objc_library_impl(ctx):
    j2objc_library_lockdown(ctx)

    _check_entry_classes(ctx)

    common_variables = compilation_support.build_common_variables(
        ctx = ctx,
        toolchain = None,
        deps = ctx.attr.deps + ctx.attr.jre_deps,
        empty_compilation_artifacts = True,
        direct_cc_compilation_contexts = [dep[CcInfo].compilation_context for dep in ctx.attr.deps if CcInfo in dep],
    )

    return [
        _entry_class_provider(ctx.attr.entry_classes, ctx.attr.deps),
        _mapping_file_provider(ctx.attr.deps),
        common_variables.objc_provider,
        CcInfo(
            compilation_context = common_variables.objc_compilation_context.create_cc_compilation_context(),
            linking_context = cc_common.merge_linking_contexts(linking_contexts = common_variables.objc_linking_context.cc_linking_contexts),
        ),
    ]

j2objc_library = rule(
    _j2objc_library_impl,
    doc = """
<p> This rule uses <a href="https://github.com/google/j2objc">J2ObjC</a> to translate Java source
files to Objective-C, which then can be used used as dependencies of objc_library and objc_binary
rules. Detailed information about J2ObjC itself can be found at  <a href="http://j2objc.org">the
J2ObjC site</a>
</p>
<p>Custom J2ObjC transpilation flags can be specified using the build flag
<code>--j2objc_translation_flags</code> in the command line.
</p>
<p>Please note that the translated files included in a j2objc_library target will be
compiled using the default compilation configuration, the same configuration as for the sources of
an objc_library rule with no compilation options specified in attributes.
</p>
<p>Plus, generated code is de-duplicated at target level, not source level. If you have two
different Java targets that include the same Java source files, you may see a duplicate symbol error
at link time. The correct way to resolve this issue is to move the shared Java source files into a
separate common target that can be depended upon.
</p>
    """,
    attrs = {
        "deps": attr.label_list(
            allow_rules = ["j2objc_library", "java_library", "java_import", "java_proto_library"],
            aspects = [j2objc_aspect],
            doc = """
A list of <code>j2objc_library</code>, <code>java_library</code>,
<code>java_import</code> and <code>java_proto_library</code> targets that contain
Java files to be transpiled to Objective-C.
<p>All <code>java_library</code> and <code>java_import</code> targets that can be reached
transitively through <code>exports</code>, <code>deps</code> and <code>runtime_deps</code>
will be translated and compiled, including files generated by Java annotation processing.
There is no support for code>java_import</code> targets with no <code>srcjar</code>
specified.
</p>
<p>The J2ObjC translation works differently depending on the type of source Java source
files included in the transitive closure. For each .java source files included in
<code>srcs</code> of <code>java_library</code>, a corresponding .h and .m source file
will be generated. For each source jar included in <code>srcs</code> of
<code>java_library</code> or <code>srcjar</code> of <code>java_import</code>, a
corresponding .h and .m source file will be generated with all the code for that jar.
</p>
<p>Users can import the J2ObjC-generated header files in their code. The import paths for
these files are the root-relative path of the original Java artifacts. For example,
<code>//some/package/foo.java</code> has an import path of <code>some/package/foo.h</code>
and <code>//some/package/bar.srcjar</code> has <code>some/package/bar.h</code
</p>
<p>
If proto_library rules are in the transitive closure of this rule, J2ObjC protos will also
be generated, compiled and linked-in at the binary level. For proto
<code>//some/proto/foo.proto</code>, users can reference the generated code using import
path <code>some/proto/foo.j2objc.pb.h</code>.
</p>""",
        ),
        "entry_classes": attr.string_list(doc = """
The list of Java classes whose translated ObjC counterparts will be referenced directly
by user ObjC code. This attribute is required if flag <code>--j2objc_dead_code_removal
</code> is on. The Java classes should be specified in their canonical names as defined by
<a href="http://docs.oracle.com/javase/specs/jls/se8/html/jls-6.html#jls-6.7">the Java
Language Specification.</a>
When flag <code>--j2objc_dead_code_removal</code> is specified, the list of entry classes
will be collected transitively and used as entry points to perform dead code analysis.
Unused classes will then be removed from the final ObjC app bundle."""),
        "jre_deps": attr.label_list(
            allow_rules = ["objc_library"],
            aspects = [jre_deps_aspect],
            doc = """
The list of additional JRE emulation libraries required by all Java code translated by this
<code>j2objc_library</code> rule. Only core JRE functionality is linked by default.""",
        ),
    },
    cfg = objc_semantics.apple_crosstool_transition,
    fragments = ["apple", "cpp", "j2objc", "objc"] + semantics.additional_fragments(),
    toolchains = cc_helper.use_cpp_toolchain(),
    provides = [CcInfo, J2ObjcEntryClassInfo, J2ObjcMappingFileInfo],
)
