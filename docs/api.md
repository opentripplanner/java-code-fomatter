# Java API

The same engine that runs the command line and the Maven plugin is available as a library. The module is
`org.tiger.java.code.formatter` and it needs `java.compiler` and `jdk.compiler`.

## FormatRun

```java
FormatRun.Report report = FormatRun.run(files, FormatRun.Mode.CHECK, /* useCache */ true);

report.changed();  // files that are not formatted (CHECK) or were rewritten (WRITE)
report.failed();   // files that are not valid Java, or could not be read or written
report.notices();  // run-level warnings
report.files();    // one FormatRun.FileResult per file
```

| Member | Meaning |
| ------ | ------- |
| `Mode.PRINT` | Compute the result. The text is on `FileResult.formattedText()`. |
| `Mode.WRITE` | Rewrite files that changed. |
| `Mode.CHECK` | Write nothing. |
| `Status` | `CHANGED`, `UNCHANGED`, `CACHED` or `FAILED` per file. |
| `FileResult` | `path()`, `status()`, `errors()`, `warnings()`, `formattedText()`. |
| `FormatRun.javaFilesUnder(Path)` | The Java files under a directory (git aware). |
| `FormatRun.changedJavaFiles(Path)` | The files that git reports as changed or untracked. |
| `FormatRun.formattableFilesUnder(Path)`, `changedFormattableFiles(Path)` | The same, for Java, JSON and Markdown files. |
| `FormatRun.handles(Path)` | Whether the formatter formats this file: Java, or JSON or Markdown enabled by `formats`. |
| `FormatRun.formatText(Path, String)` | Formats text that is not on disk (an editor buffer). The path picks the file type and the config. Returns a `TextResult`: `status()`, `text()` (`null` when `FAILED`), `errors()`, `warnings()`. No cache is used. |
| `DocumentFormatter.format(kind, text, config)` | Formats a JSON or Markdown string. Returns `Formatted` or `Skipped`. |

`run` finds the configuration for each file itself, with the layering described in
[Configuration](configuration.md), and it uses the same cache as the command line.

## SourceFormatter

To format a string, call `SourceFormatter.format(source, config)`. It throws `IOException` on a source it cannot format.
A `FormatterConfig` comes from `FormatterConfig.load(path)`, `FormatterConfig.from(properties)` or
`FormatterConfig.DEFAULT`. `SourceFormatter.sameProgram(before, after)` tells
you whether two sources have the same tokens, which is the check described in [Safety](safety.md).

## The command line as a function

`JavaCodeFomatter.run(String[] args, PrintStream out, PrintStream err)` runs the command line and returns the exit
code, without calling `System.exit`. It is useful in tests.
