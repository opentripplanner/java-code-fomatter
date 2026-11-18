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
  <groupId>org.tiger.java.code</groupId>
  <artifactId>formatter-maven-plugin</artifactId>
  <version>1-SNAPSHOT</version>
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
| `sourceRoots` | | the project's compile and test source roots and resource directories | Directories to format. JSON and Markdown files in them are formatted when `formats` in `.jcf/config.properties` lists them (see [JSON and Markdown](../docs/json-markdown.md)); add the documentation directory here to format its files. |
| `excludes` | | none | Globs, relative to each source root, of files to leave alone, e.g. `**/generated/**`. |

Source roots under the build directory (generated sources) are never formatted.

**Source roots added by other plugins** (for example with `build-helper-maven-plugin`) are only known once those
plugins have run. Bound to a lifecycle phase after `generate-sources` the goal sees them. Run directly from the
command line (`mvn jcf:format`) it does not; list them in `sourceRoots` if you need that.

## Building and testing

`./build.sh verify` builds the formatter, installs it into the local Maven repository, then builds the plugin and runs
the integration tests in `src/it` (each one is a small Maven project run against the plugin).

## Releasing

The plugin depends on the formatter library, so both have to be published. The coordinates and package are
placeholders; rename them together. Maven Central also requires the usual metadata in each pom (`url`, `licenses`,
`developers`, `scm`), source and Javadoc jars, and signed artifacts.
