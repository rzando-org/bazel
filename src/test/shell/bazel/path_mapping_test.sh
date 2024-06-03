#!/bin/bash
#
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
#
# Tests path mapping support of Bazel's executors.

set -euo pipefail

# --- begin runfiles.bash initialization ---
if [[ ! -d "${RUNFILES_DIR:-/dev/null}" && ! -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
  if [[ -f "$0.runfiles_manifest" ]]; then
    export RUNFILES_MANIFEST_FILE="$0.runfiles_manifest"
  elif [[ -f "$0.runfiles/MANIFEST" ]]; then
    export RUNFILES_MANIFEST_FILE="$0.runfiles/MANIFEST"
  elif [[ -f "$0.runfiles/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
    export RUNFILES_DIR="$0.runfiles"
  fi
fi
if [[ -f "${RUNFILES_DIR:-/dev/null}/bazel_tools/tools/bash/runfiles/runfiles.bash" ]]; then
  source "${RUNFILES_DIR}/bazel_tools/tools/bash/runfiles/runfiles.bash"
elif [[ -f "${RUNFILES_MANIFEST_FILE:-/dev/null}" ]]; then
  source "$(grep -m1 "^bazel_tools/tools/bash/runfiles/runfiles.bash " \
            "$RUNFILES_MANIFEST_FILE" | cut -d ' ' -f 2-)"
else
  echo >&2 "ERROR: cannot find @bazel_tools//tools/bash/runfiles:runfiles.bash"
  exit 1
fi
# --- end runfiles.bash initialization ---

source "$(rlocation "io_bazel/src/test/shell/integration_test_setup.sh")" \
  || { echo "integration_test_setup.sh not found!" >&2; exit 1; }
source "$(rlocation "io_bazel/src/test/shell/bazel/remote_helpers.sh")" \
  || { echo "remote_helpers.sh not found!" >&2; exit 1; }
source "$(rlocation "io_bazel/src/test/shell/bazel/remote/remote_utils.sh")" \
  || { echo "remote_utils.sh not found!" >&2; exit 1; }

function set_up() {
  start_worker

  mkdir -p src/main/java/com/example
  cat > src/main/java/com/example/BUILD <<'EOF'
java_binary(
    name = "Main",
    srcs = ["Main.java"],
    deps = [":lib"],
)
java_library(
    name = "lib",
    srcs = ["Lib.java"],
)
EOF
  cat > src/main/java/com/example/Main.java <<'EOF'
package com.example;
public class Main {
  public static void main(String[] args) {
    System.out.println(Lib.getGreeting());
  }
}
EOF
  cat > src/main/java/com/example/Lib.java <<'EOF'
package com.example;
public class Lib {
  public static String getGreeting() {
    return "Hello, World!";
  }
}
EOF
}

function tear_down() {
  bazel clean >& $TEST_log
  stop_worker
}

function test_path_stripping_sandboxed() {
  if is_windows; then
    echo "Skipping test_path_stripping_sandboxed on Windows as it requires sandboxing"
    return
  fi

  cache_dir=$(mktemp -d)

  bazel run -c fastbuild \
    --disk_cache=$cache_dir \
    --experimental_output_paths=strip \
    --strategy=Javac=sandboxed \
    //src/main/java/com/example:Main &> $TEST_log || fail "run failed unexpectedly"
  expect_log 'Hello, World!'
  # JavaToolchainCompileBootClasspath, JavaToolchainCompileClasses, 1x header compilation and 2x
  # actual compilation.
  expect_log '5 \(linux\|darwin\|processwrapper\)-sandbox'
  expect_not_log 'disk cache hit'

  bazel run -c opt \
    --disk_cache=$cache_dir \
    --experimental_output_paths=strip \
    --strategy=Javac=sandboxed \
    //src/main/java/com/example:Main &> $TEST_log || fail "run failed unexpectedly"
  expect_log 'Hello, World!'
  expect_log '5 disk cache hit'
  expect_not_log '[0-9] \(linux\|darwin\|processwrapper\)-sandbox'
}

function test_path_stripping_singleplex_worker() {
  if is_windows; then
    echo "Skipping test_path_stripping_singleplex_worker on Windows as it requires sandboxing"
    return
  fi

  cache_dir=$(mktemp -d)

  bazel run -c fastbuild \
    --disk_cache=$cache_dir \
    --experimental_output_paths=strip \
    --strategy=Javac=worker \
    --worker_sandboxing \
    --noexperimental_worker_multiplex \
    //src/main/java/com/example:Main &> $TEST_log || fail "run failed unexpectedly"
  expect_log 'Hello, World!'
  # JavaToolchainCompileBootClasspath, JavaToolchainCompileClasses and header compilation.
  expect_log '3 \(linux\|darwin\|processwrapper\)-sandbox'
  # Actual compilation actions.
  expect_log '2 worker'
  expect_not_log 'disk cache hit'

  bazel run -c opt \
    --disk_cache=$cache_dir \
    --experimental_output_paths=strip \
    --strategy=Javac=worker \
    --worker_sandboxing \
    --noexperimental_worker_multiplex \
    //src/main/java/com/example:Main &> $TEST_log || fail "run failed unexpectedly"
  expect_log 'Hello, World!'
  expect_log '5 disk cache hit'
  expect_not_log '[0-9] \(linux\|darwin\|processwrapper\)-sandbox'
  expect_not_log '[0-9] worker'
}

function test_path_stripping_remote() {
  bazel run -c fastbuild \
    --experimental_output_paths=strip \
    --remote_executor=grpc://localhost:${worker_port} \
    //src/main/java/com/example:Main &> $TEST_log || fail "run failed unexpectedly"
  expect_log 'Hello, World!'
  # JavaToolchainCompileBootClasspath, JavaToolchainCompileClasses, 1x header compilation and 2x
  # actual compilation.
  expect_log '5 remote'
  expect_not_log 'remote cache hit'

  bazel run -c opt \
    --experimental_output_paths=strip \
    --remote_executor=grpc://localhost:${worker_port} \
    //src/main/java/com/example:Main &> $TEST_log || fail "run failed unexpectedly"
  expect_log 'Hello, World!'
  expect_log '5 remote cache hit'
  # Do not match "5 remote cache hit", which is expected.
  expect_not_log '[0-9] remote[^ ]'
}

function test_path_stripping_remote_multiple_configs() {
  mkdir rules
  cat > rules/defs.bzl <<'EOF'
LocationInfo = provider(fields = ["location"])

def _location_setting_impl(ctx):
    return LocationInfo(location = ctx.build_setting_value)

location_setting = rule(
    implementation = _location_setting_impl,
    build_setting = config.string(),
)

def _location_transition_impl(settings, attr):
    return {"//rules:location": attr.location}

_location_transition = transition(
    implementation = _location_transition_impl,
    inputs = [],
    outputs = ["//rules:location"],
)

def _bazelcon_greeting_impl(ctx):
    content = """
package com.example.{package};

public class Lib {{
  public static String getGreeting() {{
    return String.format("Hello, BazelCon {location}!");
  }}
}}
""".format(
        package = ctx.attr.name,
        location = ctx.attr.location,
    )
    file = ctx.actions.declare_file("Lib.java")
    ctx.actions.write(file, content)
    return [
        DefaultInfo(files = depset([file])),
    ]

bazelcon_greeting = rule(
    _bazelcon_greeting_impl,
    cfg = _location_transition,
    attrs = {
        "location": attr.string(),
    },
)
EOF
  cat > rules/BUILD << 'EOF'
load("//rules:defs.bzl", "location_setting")

location_setting(
    name = "location",
    build_setting_default = "",
)
EOF

  mkdir -p src/main/java/com/example
  cat > src/main/java/com/example/BUILD <<'EOF'
load("//rules:defs.bzl", "bazelcon_greeting")
java_binary(
    name = "Main",
    srcs = ["Main.java"],
    deps = [":lib"],
)
java_library(
    name = "lib",
    srcs = [
        ":munich",
        ":new_york",
    ],
)
bazelcon_greeting(
    name = "munich",
    location = "Munich",
)
bazelcon_greeting(
    name = "new_york",
    location = "New York",
)
EOF
  cat > src/main/java/com/example/Main.java <<'EOF'
package com.example;
public class Main {
  public static void main(String[] args) {
    System.out.println(com.example.new_york.Lib.getGreeting());
    System.out.println(com.example.munich.Lib.getGreeting());
  }
}
EOF

  bazel run -c fastbuild \
    --experimental_output_paths=strip \
    --remote_executor=grpc://localhost:${worker_port} \
    //src/main/java/com/example:Main &> $TEST_log || fail "run failed unexpectedly"
  expect_log 'Hello, BazelCon New York!'
  expect_log 'Hello, BazelCon Munich!'
  # JavaToolchainCompileBootClasspath, JavaToolchainCompileClasses, 1x header compilation and 2x
  # actual compilation.
  expect_log '5 remote'
  expect_not_log 'remote cache hit'

  bazel run -c opt \
    --experimental_output_paths=strip \
    --remote_executor=grpc://localhost:${worker_port} \
    //src/main/java/com/example:Main &> $TEST_log || fail "run failed unexpectedly"
  expect_log 'Hello, BazelCon New York!'
  expect_log 'Hello, BazelCon Munich!'
  # JavaToolchainCompileBootClasspath, JavaToolchainCompileClasses and compilation of the binary.
  expect_log '3 remote cache hit'
  # Do not match "[0-9] remote cache hit", which is expected separately.
  # Header and actual compilation of the library, which doesn't use path stripping as it would
  # result in ambiguous paths due to the multiple configs.
  expect_log '2 remote[^ ]'
}

function test_path_stripping_disabled_with_tags() {
  mkdir pkg
  cat > pkg/defs.bzl <<'EOF'
def _my_rule_impl(ctx):
    out = ctx.actions.declare_file(ctx.attr.name)
    args = ctx.actions.args()
    args.add(out)
    ctx.actions.run_shell(
         outputs = [out],
         command = "echo 'Hello, World!' > $1",
         arguments = [args],
         execution_requirements = {"supports-path-mapping": ""},
    )
    return [
        DefaultInfo(files = depset([out])),
    ]

my_rule = rule(_my_rule_impl)
EOF
  cat > pkg/BUILD << 'EOF'
load(":defs.bzl", "my_rule")

my_rule(
    name = "local_target",
    tags = ["local"],
)

my_rule(
    name = "implicitly_local_target",
    tags = [
        "no-sandbox",
        "no-remote",
    ],
)
EOF

  bazel build --experimental_output_paths=strip //pkg:all &> $TEST_log || fail "build failed unexpectedly"
}

run_suite "path mapping tests"
