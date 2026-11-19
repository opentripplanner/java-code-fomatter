# Development

The project is built with Maven 3.9 or newer on JDK 25 or newer. The root `pom.xml` builds two modules: `formatter` (the
engine and the command line) and `maven-plugin`.

```bash
mvn verify              # compile, test and package both modules, and run the plugin's integration tests
mvn -pl formatter test  # only the formatter's unit tests
```

## Layers of the formatter

`SourceFormatter` runs these steps in order, and finishes with the checks in [Safety](safety.md):

1. Validate the input.
2. `ImportRemover` removes unused imports, only with `removeUnusedImports=true`.
3. `SpacingNormalizer` sets the spaces between tokens on a line.
4. `ChainedBlockLayout` puts `else`, `catch` and `finally` on their own line after the `}`, unless `chainedBlocks=attached`.
5. `CommentFormatter` moves trailing comments above their line.
6. `BlankLineFormatter` adds blank lines around blocks.
7. `ScopeReindenter` indents by brace nesting.
8. `ExpressionWrapper` wraps declarations, assignments, `return`, `throw`, headers and switch arms using the javac tree.
9. `ScopeReindenter` runs again.
10. `JavadocFormatter` and `LineCommentWrapper` format comments, as chosen by `commentRules`.
11. `LineWrapper` wraps whatever is still too long, greedily.
12. Parse the result again and compare tokens (`TokenStream`).

## Tests

Tests are in `formatter/src/test/java/` and use JUnit. `FormatterGoldenFileTest` is one data-driven test over every
`formatter/src/test/resources/cases/**/<name>/{Input.java.txt,Expected.java.txt}` pair. To add coverage, add a fixture
folder, not code. Every case also gets an idempotency check: formatting `Expected.java.txt` again must change nothing.
Fixtures end in `.java.txt` so that nothing mistakes them for source code.

The cases share `formatter/src/test/resources/cases/.jcf/config.properties` (`wrapAtColumn=60`), in the same format as a
real configuration. A narrow limit keeps fixtures short.

## The Maven plugin

The plugin's integration tests in `maven-plugin/src/it` are small Maven projects run against the built plugin. They run
as part of `mvn verify`.

## The IntelliJ plugin

The plugin in `intellij-plugin/` is a separate Gradle project, not part of the Maven build. It bundles the formatter from
the local Maven repository, so run `mvn install` in the root first. Then run `./gradlew buildPlugin` or
`./gradlew runIde` in that folder. See the [plugin guide](../intellij-plugin/README.md). It formats editor text through
`FormatRun.formatText`.

## Publishing to Maven Central

Three artifacts are published as one bundle: `java-code-formatter-parent` (the parent pom), `java-code-formatter` (the
engine and command line) and `java-formatter-maven-plugin`. The root `pom.xml` lists the two modules and holds the shared metadata and the `release`
profile. The profile adds the source and javadoc jars, signs everything with GPG and uploads one bundle with the Central
Publisher plugin.

You need these once:

1. A [Sonatype Central](https://central.sonatype.com) account that is a member of the verified `org.opentripplanner`
   namespace.
2. A user token from Central, stored in `~/.m2/settings.xml` under the server id `central`. Use `${env.NAME}` values
   and 1Password (`op run`) to keep the token out of the file.
3. A GPG key, with the public key on `keyserver.ubuntu.com`.

To release:

```bash
# 1. Set the version in the three poms (mvn versions:set -DnewVersion=1.1.0 -DgenerateBackupPoms=false) and in the
#    README snippets. Commit and tag it.
# 2. Use JDK 25 and let gpg ask for the passphrase:
export JAVA_HOME=$(/usr/libexec/java_home -v 25) GPG_TTY=$(tty)

# 3. Check everything, without signing or uploading:
mvn verify
mvn -Prelease -Dgpg.skip verify

# 4. Publish:
mvn -Prelease deploy
```

The deploy uploads one bundle that you then check and publish in the Central portal. A published version cannot be
removed or changed.
