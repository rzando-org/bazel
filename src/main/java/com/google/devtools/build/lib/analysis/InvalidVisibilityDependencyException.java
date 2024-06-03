// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis;

import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.PackageGroup;
import com.google.devtools.build.lib.packages.Target;

/** Indicates a visibility dependency on a {@link Target} that is not a {@link PackageGroup}. */
public final class InvalidVisibilityDependencyException extends Exception {
  private final Label label;

  public InvalidVisibilityDependencyException(Label label) {
    this.label = label;
  }

  /** Label of {@link Target} that was expected to be a {@link PackageGroup}. */
  public Label label() {
    return label;
  }
}
