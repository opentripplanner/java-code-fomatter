# IntelliJ plugin

Makes **Reformat Code** (Ctrl+Alt+L / Cmd+Opt+L) and **Reformat code** on save use the formatter library, so the IDE
formats like the command line and the Maven plugin.

- Java files are always formatted by the plugin.
- JSON and Markdown files are formatted when `formats` in `.jcf/config.properties` lists them
  (see [JSON and Markdown](../docs/json-markdown.md)); otherwise the IDE's own formatter handles them.
- The rules come from the `.jcf/config.properties` files above the file, as everywhere else. Nothing is configured in
  the IDE.
- The text in the editor is formatted, not the file on disk, so unsaved changes and undo work as usual.
- If the formatter cannot format a file (for example the Java does not compile yet), a balloon shows the errors and the
  text is left unchanged.
- **Reformat a selection** is not supported by the formatter, so the IDE's own formatter handles it.

## Build

The plugin bundles the formatter library from the local Maven repository, so install that first. You need JDK 25.

```bash
mvn install                       # in the repository root: installs the formatter
cd intellij-plugin
./gradlew buildPlugin             # build/distributions/formatter-intellij-plugin-1.2.0.zip
./gradlew runIde                  # starts a sandbox IDE with the plugin to try it
```

By default Gradle downloads IntelliJ IDEA (`platformVersion` in `gradle.properties`, a large download). To use an IDE
that is already installed, pass its location:

```bash
./gradlew runIde -PlocalIde="/Applications/IntelliJ IDEA.app"
```

Install the zip in a running IDE with **Settings → Plugins → ⚙ → Install Plugin from Disk**.

## Notes

- The plugin needs an IDE that runs on a JDK with the `jdk.compiler` module (the bundled JBR has it) and is built for
  IntelliJ 2026.2 (build 262) and later.
- The build is separate from the Maven build of the other modules.
