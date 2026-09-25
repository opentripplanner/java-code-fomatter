# java-code-fomatter

Format Java source files using operator precedence and scope.

It reindents by brace nesting, and wraps over-long lines two ways: variable/
field declarations, plain assignments, `return` and `throw` are wrapped at
real operator-precedence boundaries (`ExpressionWrapper`); everything else
still over budget falls back to a flat, greedy token-boundary wrap
(`LineWrapper`). It does not yet touch general token spacing, or extend
precedence-aware wrapping to control-flow headers, method signatures, or
annotations - see [Known limitations](#known-limitations).

## Requirements

- JDK 25 or newer.

## Usage

```bash
java -m org.tiger.java.code.formatter <file.java> [<file.java>...]         # print formatted output to stdout
java -m org.tiger.java.code.formatter -w <file.java> [<file.java>...]       # rewrite files in place
java -m org.tiger.java.code.formatter --write <file.java> [<file.java>...]
```

The formatter first validates that a file is syntactically correct Java
(without resolving its classpath) and leaves it untouched, reporting
diagnostics, if it isn't. If a line still exceeds the configured column limit
after reindenting and wrapping and has no safe break point (e.g. it's a single
long string literal or a text-block line), it's left unchanged and reported
rather than corrupted.

### Configuration

Drop a `.jcf.properties` file in the directory you run the formatter from to
override defaults:

```properties
wrapAtColumn=100
indent=2
```

| Key            | Default | Meaning                                              |
| -------------- | ------- | ----------------------------------------------------- |
| `wrapAtColumn` | `120`   | Column at which a line gets wrapped                    |
| `indent`       | `4`     | Spaces per indent level, used everywhere (scope, wrapped lines, and each precedence-tier step) |

No file present means the defaults apply.

## Build tool

This project uses [Jenesis](https://jenesis.build/), a Java-native build tool
whose engine is vendored directly into the repo under `build/jenesis/` as
plain Java source (no wrapper binaries or background daemons).

### One-time setup

Jenesis itself is not committed yet. Bootstrap it once with:

```bash
curl -fsSL https://get.jenesis.build | bash
```

This vendors the build engine into `build/jenesis/`. Commit that directory
after running it.

### Common commands

```bash
jenesis
jenesis-exec
java build/jenesis/Make.java              # compile, test, and package
java build/jenesis/Make.java stage        # prepare a release tree
java build/jenesis/Make.java dependencies # show resolved dependencies
java build/jenesis/Make.java pin          # resolve and record dependency checksums
```

## Testing

Tests live in `src/test/java/`, wired as a Jenesis `@jenesis.test` module
against JUnit 5. `FormatterGoldenFileTest` is a single data-driven test over
every
`src/test/java/resources/format-cases/<name>/{Input.java.txt,Expected.java.txt}`
pair: growing coverage is adding a fixture directory, not writing code. Each
case also gets a free idempotency check - formatting `Expected.java.txt` again
must be a no-op. Fixtures use a `.java.txt` extension rather than `.java`,
since Jenesis compiles any `.java` file under `src/test/java` as real source.

All cases share `src/test/java/resources/.jcf.properties` (`wrapAtColumn=60`),
the same file format and loading path as a real `.jcf.properties` - a narrower
column budget keeps fixtures short and readable.

`java build/jenesis/Make.java` compiles, tests, and packages both modules, so
running the suite needs no separate command.

## Known limitations

Deliberately narrow. In particular:

- **Brace-less control-flow bodies aren't specially indented.** `if (x)\n
  foo();` has no brace to encode its nesting, so its body is indented at the
  same depth as the `if` itself - including flattening a body a human had
  already indented correctly. Adding braces automatically is a natural next
  step, deferred for now.
- **Precedence-aware wrapping only covers declarations, plain assignments,
  `return` and `throw`.** `if`/`while`/`for` conditions, method parameter and
  `throws` lists, and annotation arguments still use the older flat wrapper -
  a complex condition sitting next to a nicely-wrapped assignment can look
  formatted by two different algorithms until this is widened.
- **Multi-declarator statements** (`int a = 1, b = 2;`) aren't covered by the
  precedence-aware wrapper either - same flat-wrapper fallback.
- **Generic type-argument commas (`Map<String, List<Integer>>`) aren't tracked
  as a bracket kind** by the flat wrapper, so they may read as the same depth
  as their enclosing call - a readability blind spot, not a correctness bug.
- **A line wholly inside a text block or string literal that's still over the
  column limit is left as-is and reported** - it cannot be safely wrapped
  without changing its value.
- A wrapping decision that wouldn't actually shorten the longest resulting
  line is skipped - the statement is left as-is rather than split for no
  benefit.
- A method chain's first call is always split onto its own line
  (`a.stream()` never stays glued to the receiver); an opt-in to keep it
  glued is designed but not implemented yet.
- `switch` case bodies aren't given special per-case indentation.
- A multi-line comment's interior lines keep their indentation *relative to*
  the comment's own opening line (so a javadoc's conventional `" * "`
  alignment survives a depth change) rather than being reindented from
  scratch - a comment that was already misindented relative to its own
  opener stays that way, just shifted.
- Column width is measured as `String.length()` (UTF-16 units), not display
  width.

