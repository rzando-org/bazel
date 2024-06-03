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

"""Apple platform definitions."""

# LINT.IfChange
# This struct retains a duplicate list in ApplePlatform.PlatformType during the migration.
# TODO(b/331163027): Remove the IfChange clause once the duplicate is removed.

# Describes an Apple "platform type", such as iOS, macOS, tvOS, visionOS, or watchOS. This
# is distinct from a "platform", which is the platform type combined with one or
# more CPU architectures.
# Specific instances of this type can be retrieved by
# accessing the fields of the apple_common.platform_type:
# apple_common.platform_type.ios
# apple_common.platform_type.macos
# apple_common.platform_type.tvos
# apple_common.platform_type.watchos
# An ApplePlatform is implied from a
# platform type (for example, watchOS) together with a cpu value (for example, armv7).
PLATFORM_TYPE = struct(
    ios = "ios",
    visionos = "visionos",
    watchos = "watchos",
    tvos = "tvos",
    macos = "macos",
    catalyst = "catalyst",
)

# PLATFORM corresponds to Xcode's notion of a platform as would be found in
# Xcode.app/Contents/Developer/Platforms</code>. Each platform represents an
# Apple platform type (such as iOS or tvOS) combined with one or more related CPU
# architectures. For example, the iOS simulator platform supports x86_64
# and i386 architectures.
# More commonly, however, the apple configuration fragment has fields/methods that allow rules
# to determine the platform for which a target is being built.
def _create_platform(name, name_in_plist, platform_type, is_device):
    return struct(
        name = name,
        name_in_plist = name_in_plist,
        platform_type = platform_type,
        is_device = is_device,
    )

PLATFORM = struct(
    ios_device = _create_platform("ios_device", "iPhoneOS", PLATFORM_TYPE.ios, True),
    ios_simulator = _create_platform("ios_simulator", "iPhoneSimulator", PLATFORM_TYPE.ios, False),
    macos = _create_platform("macos", "MacOSX", PLATFORM_TYPE.macos, True),
    tvos_device = _create_platform("tvos_device", "AppleTVOS", PLATFORM_TYPE.tvos, True),
    tvos_simulator = _create_platform("tvos_simulator", "AppleTVSimulator", PLATFORM_TYPE.tvos, False),
    visionos_device = _create_platform("visionos_device", "XROS", PLATFORM_TYPE.visionos, True),
    visionos_simulator = _create_platform("visionos_simulator", "XRSimulator", PLATFORM_TYPE.visionos, False),
    watchos_device = _create_platform("watchos_device", "WatchOS", PLATFORM_TYPE.watchos, True),
    watchos_simulator = _create_platform("watchos_simulator", "WatchSimulator", PLATFORM_TYPE.watchos, False),
    catalyst = _create_platform("catalyst", "MacOSX", PLATFORM_TYPE.catalyst, True),
)
# LINT.ThenChange(//src/main/java/com/google/devtools/build/lib/rules/apple/ApplePlatform.java)
