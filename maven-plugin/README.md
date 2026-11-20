# Java code formatter - Maven plugin

Formats a Maven project's Java sources, or checks that they are formatted. A thin wrapper around the formatter
library: how files are formatted comes from the formatter's `.jcf/config.properties` (found per file, up to the
repository root), not from the pom.

Requires Maven 3.9+ running on JDK 25 or newer.

## Goals

| Goal | Default phase | What it does |
| ---- | ------------- | ------------ |
| `jcf:format` | `process-sources` | Rewrites the files that are not formatted. |
| `jcf:check` | `verify` | Changes nothing; fails the build, listing the files, if any file is not formatted. |

```xml
<plugin>
  <groupId>org.opentripplanner.java.code.formatter</groupId>
  <artifactId>java-formatter-maven-plugin</artifactId>
  <version>1.2.0</version>
  <executions>
    <execution>
      <id>format</id>
      <phase>validate</phase>
      <goals>
        <goal>format</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

On a CI server, run `check` instead of `format`, for example in a profile. A file that is not valid Java fails both
goals. Lines the formatter cannot bring under the limit (a long URL, a long string) are warnings, never failures.

## Parameters

| Parameter | Property | Default | Meaning |
| --------- | -------- | ------- | ------- |
| `skip` | `jcf.skip` | `false` | Skip the goal. |
| `cache` | `jcf.cache` | `true` | Use the `.jcf/cache` of files already known to be formatted. |
| `includeTests` | `jcf.includeTests` | `true` | Also format the test source roots. |
| `sourceRoots` | | the project's compile and test source roots and resource directories | Directories to format. Replaces the defaults. |
| `includes` | | none | Extra files to format, as globs relative to the project base directory, e.g. `*.md` and `docs/**.md`. For the root pom of a multi-module build: files in submodules and the build directory are skipped. JSON and Markdown files are formatted when `formats` in `.jcf/config.properties` lists them (see [JSON and Markdown](../docs/json-markdown.md)). |
| `excludes` | | none | Globs, relative to each source root (or the base directory, for `includes`), of files to leave alone, e.g. `**/generated/**`. |

Source roots under the build directory (generated sources) are never formatted.

**Source roots added by other plugins** (for example with `build-helper-maven-plugin`) are only known once those
plugins have run. Bound to a lifecycle phase after `generate-sources` the goal sees them. Run directly from the
command line (`mvn jcf:format`) it does not; list them in `sourceRoots` if you need that.

## Building and testing

`mvn verify` from the repository root builds the formatter and the plugin and runs the integration tests in
`maven-plugin/src/it` (each one is a small Maven project run against the plugin).

## Releasing

The plugin depends on the formatter library, and both are published together with the parent pom as one bundle. See
[Publishing to Maven Central](../docs/development.md#publishing-to-maven-central).
