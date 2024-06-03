// Copyright 2024 The Bazel Authors. All rights reserved.
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

/**
 * Identifier for a RuleClass object including both the common ruleClass name and a unique
 * identifier for that ruleClass to disambiguate two Starlark rule classes with the same common
 * name.
 *
 * <p>TODO: b/331652164 - This should be a record
 */
@AutoValue
public abstract class RuleClassId {
  public static RuleClassId create(String name, String key) {
    return new AutoValue_RuleClassId(name, key);
  }

  public abstract String name();

  public abstract String key();
}
