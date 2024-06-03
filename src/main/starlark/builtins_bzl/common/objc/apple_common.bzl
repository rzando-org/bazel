# Copyright 2024 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License(**kwargs): Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing(**kwargs): software
# distributed under the License is distributed on an "AS IS" BASIS(**kwargs):
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND(**kwargs): either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Legacy apple_common module"""

load(":common/objc/apple_env.bzl", "apple_host_system_env", "target_apple_env")
load(":common/objc/apple_platform.bzl", "PLATFORM", "PLATFORM_TYPE")
load(":common/objc/apple_toolchain.bzl", "apple_toolchain")
load(":common/objc/linking_support.bzl", "AppleDebugOutputsInfo", "AppleDynamicFrameworkInfo", "AppleExecutableBinaryInfo", "linking_support")
load(":common/objc/objc_info.bzl", "ObjcInfo")
load(":common/xcode/providers.bzl", "XcodeVersionInfo", "XcodeVersionPropertiesInfo")

native_apple_common = _builtins.internal.apple_common

apple_common = struct(
    apple_toolchain = lambda: apple_toolchain,
    platform_type = PLATFORM_TYPE,
    platform = PLATFORM,
    XcodeProperties = XcodeVersionPropertiesInfo,
    XcodeVersionConfig = XcodeVersionInfo,
    Objc = ObjcInfo,
    AppleDynamicFramework = AppleDynamicFrameworkInfo,
    AppleExecutableBinary = AppleExecutableBinaryInfo,
    AppleDebugOutputs = AppleDebugOutputsInfo,
    apple_host_system_env = apple_host_system_env,
    target_apple_env = target_apple_env,
    new_objc_provider = ObjcInfo,
    new_dynamic_framework_provider = lambda **kwargs: AppleDynamicFrameworkInfo(**kwargs),
    new_executable_binary_provider = lambda **kwargs: AppleExecutableBinaryInfo(**kwargs),
    link_multi_arch_binary = linking_support.link_multi_arch_binary,
    link_multi_arch_static_library = linking_support.link_multi_arch_static_library,
    dotted_version = lambda version: native_apple_common.dotted_version(version),
)
