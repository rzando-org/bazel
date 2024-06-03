Project: /_project.yaml
Book: /_book.yaml
keywords: product:Bazel,Bzlmod,vendor

{# disableFinding("vendoring") #}
{# disableFinding("Vendoring") #}
{# disableFinding("vendored") #}
{# disableFinding("repo") #}

# Vendor Mode

Vendor mode is a feature of Bzlmod that lets you create a local copy of
external dependencies. This is useful for offline builds, or when you want to
control the source of an external dependency.

## Enable vendor mode {:#enable-vendor-mode}

You can enable vendor mode by specifying `--vendor_dir` flag.

For example, by adding it to your `.bazelrc` file:

```none
# Enable vendor mode with vendor directory under <workspace>/vendor_src
common --vendor_dir=vendor_src
```

The vendor directory can be either a relative path to your workspace root or an
absolute path.

## Vendor a specific external repository {:#vendor-specific-repository}

You can use the `vendor` command with the `--repo` flag to specify which repo
to vendor, it accepts both [canonical repo
name](/external/overview#canonical-repo-name) and [apparent repo
name](/external/overview#apparent-repo-name).

For example, running:

```none
bazel vendor --vendor_dir=vendor_src --repo=@rules_cc
```

or

```none
bazel vendor --vendor_dir=vendor_src --repo=@@rules_cc~
```

will both get rules_cc to be vendored under
`<workspace root>/vendor_src/rules_cc~`.

## Vendor external dependencies for given targets {:#vendor-target-dependencies}

To vendor all external dependencies required for building given target patterns,
you can run `bazel vendor <target patterns>`.

For example

```none
bazel vendor --vendor_dir=vendor_src //src/main:hello-world //src/test/...
```

will vendor all repos required for building the `//src/main:hello-world` target
and all targets under `//src/test/...` with the current configuration.

Under the hood, it's doing a `bazel build --nobuild` command to analyze the
target patterns, therefore build flags could be applied to this command and
affect the result.

## Vendor all external dependencies {:#vendor-all-dependencies}

To vendor all repos in your transitive external dependencies graph, you can
run:

```none
bazel vendor --vendor_dir=vendor_src
```

Note that vendoring all dependencies has a few **disadvantages**:

-   Fetching all repos, including those introduced transitively, can be time-consuming.
-   The vendor directory can become very large.
-   Some repos may fail to fetch if they are not compatible with the current platform or environment.

Therefore, consider vendoring for specific targets first.

## Configure vendor mode with VENDOR.bazel {:#configure-vendor-mode}

You can control how given repos are handled with the VENDOR.bazel file located
under the vendor directory.

There are two directives available, both accepting a list of
[canonical repo names](/external/overview#canonical-repo-name) as arguments:

- `ignore()`: to completely ignore a repository from vendor mode.
- `pin()`: to pin a repository to its current vendored source as if there is a
  `--override_repository` flag for this repo. Bazel will NOT update the vendored
  source for this repo while running the vendor command unless it's unpinned.
  The user can modify and maintain the vendored source for this repo manually.

For example

```python
ignore("@@rules_cc~")
pin("@@bazel_skylib~")
```

With this configuration

-   Both repos will be excluded from subsequent vendor commands.
-   Repo `bazel_skylib` will be overridden to the source located under the
    vendor directory.
-   The user can safely modify the vendored source of `bazel_skylib`.
-   To re-vendor `bazel_skylib`, the user has to disable the pin statement
    first.

Note: Repository rules with
[`local`](/rules/lib/globals/bzl#repository_rule.local) or
[`configure`](/rules/lib/globals/bzl#repository_rule.configure) set to true are
always excluded from vendoring.

## Understand how vendor mode works {:#how-vendor-mode-works}

Bazel fetches external dependencies of a project under `$(bazel info
output_base)/external`. Vendoring external dependencies means copying out
relevant files and directories to a given vendor directory and use the vendored
source for later builds.

The content being vendored includes:

-   The repo directory
-   The repo marker file

During a build, if the vendored marker file is up-to-date or the repo is
pinned in the VENDOR.bazel file, then Bazel uses the vendored source by creating
a symlink to it under `$(bazel info output_base)/external` instead of actually
running the repository rule. Otherwise, a warning is printed and Bazel will
fallback to fetching the latest version of the repo.

Note: Bazel assumes the vendored source is not changed by users unless the repo
is pinned in the VENDOR.bazel file. If a user does change the vendored source
without pinning the repo, the changed vendored source will be used, but it will
be overwritten if its existing marker file is
outdated and the repo is vendored again.
