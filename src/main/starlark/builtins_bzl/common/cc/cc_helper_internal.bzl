# Copyright 2024 The Bazel Authors. All rights reserved.
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
Utility functions for C++ rules that don't depend on cc_common.

Only use those within C++ implementation. The others need to go through cc_common.
"""

artifact_category = struct(
    STATIC_LIBRARY = "STATIC_LIBRARY",
    ALWAYSLINK_STATIC_LIBRARY = "ALWAYSLINK_STATIC_LIBRARY",
    DYNAMIC_LIBRARY = "DYNAMIC_LIBRARY",
    EXECUTABLE = "EXECUTABLE",
    INTERFACE_LIBRARY = "INTERFACE_LIBRARY",
    PIC_FILE = "PIC_FILE",
    INCLUDED_FILE_LIST = "INCLUDED_FILE_LIST",
    SERIALIZED_DIAGNOSTICS_FILE = "SERIALIZED_DIAGNOSTICS_FILE",
    OBJECT_FILE = "OBJECT_FILE",
    PIC_OBJECT_FILE = "PIC_OBJECT_FILE",
    CPP_MODULE = "CPP_MODULE",
    GENERATED_ASSEMBLY = "GENERATED_ASSEMBLY",
    PROCESSED_HEADER = "PROCESSED_HEADER",
    GENERATED_HEADER = "GENERATED_HEADER",
    PREPROCESSED_C_SOURCE = "PREPROCESSED_C_SOURCE",
    PREPROCESSED_CPP_SOURCE = "PREPROCESSED_CPP_SOURCE",
    COVERAGE_DATA_FILE = "COVERAGE_DATA_FILE",
    CLIF_OUTPUT_PROTO = "CLIF_OUTPUT_PROTO",
)

def should_create_per_object_debug_info(feature_configuration, cpp_configuration):
    return cpp_configuration.fission_active_for_current_compilation_mode() and \
           feature_configuration.is_enabled("per_object_debug_info")

def is_versioned_shared_library_extension_valid(shared_library_name):
    # validate against the regex "^.+\\.((so)|(dylib))(\\.\\d\\w*)+$",
    # must match VERSIONED_SHARED_LIBRARY.
    for ext in (".so.", ".dylib."):
        name, _, version = shared_library_name.rpartition(ext)
        if name and version:
            version_parts = version.split(".")
            for part in version_parts:
                if not part[0].isdigit():
                    return False
                for c in part[1:].elems():
                    if not (c.isalnum() or c == "_"):
                        return False
            return True
    return False

def is_shared_library(file):
    return file.extension in ["so", "dylib", "dll", "pyd", "wasm", "tgt", "vpi"]

def is_versioned_shared_library(file):
    # Because regex matching can be slow, we first do a quick check for ".so." and ".dylib."
    # substring before risking the full-on regex match. This should eliminate the performance
    # hit on practically every non-qualifying file type.
    if ".so." not in file.basename and ".dylib." not in file.basename:
        return False
    return is_versioned_shared_library_extension_valid(file.basename)
