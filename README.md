# java-code-fomatter

Format Java source files using operator precedence and scope.

v1 formats indentation/scope only: it reindents by brace nesting and wraps
lines over 120 columns at safe token boundaries. It does not touch operator
precedence, parenthesization, or general token spacing yet - see
[Known limitations](#known-limitations).

## Requirements

- JDK 25 or newer.

## Usage

```bash
java -m org.tiger.code.fomatter <file.java> [<file.java>...]         # print formatted output to stdout
java -m org.tiger.code.fomatter -w <file.java> [<file.java>...]       # rewrite files in place
java -m org.tiger.code.fomatter --write <file.java> [<file.java>...]
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
```

| Key            | Default | Meaning                                    |
| -------------- | ------- | ------------------------------------------ |
| `wrapAtColumn` | `120`   | Column at which `LineWrapper` wraps a line |

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

Tests live in `src/test/`, wired as a Jenesis `@jenesis.test` module against
JUnit 5. `FormatterGoldenFileTest` is a single data-driven test over every
`src/test/resources/format-cases/<name>/{Input.java.txt,Expected.java.txt}`
pair: growing coverage is adding a fixture directory, not writing code. Each
case also gets a free idempotency check - formatting `Expected.java.txt` again
must be a no-op. Fixtures use a `.java.txt` extension rather than `.java`,
since Jenesis compiles any `.java` file under `src/test` as real source.

All cases share `src/test/resources/.jcf.properties` (`wrapAtColumn=60`), the
same file format and loading path as a real `.jcf.properties` - a narrower
column budget keeps fixtures short and readable.

`java build/jenesis/Make.java` compiles, tests, and packages both modules, so
running the suite needs no separate command.

## Known limitations

v1 is deliberately narrow. In particular:

- **Brace-less control-flow bodies aren't specially indented.** `if (x)\n
  foo();` has no brace to encode its nesting, so its body is indented at the
  same depth as the `if` itself - including flattening a body a human had
  already indented correctly. Adding braces automatically is a natural next
  step, deferred for now.
- **No continuation/hanging indent for a statement that fits under 120
  columns but already spans multiple lines for other reasons** - only lines
  actually over 120 columns get wrapped.
- **Generic type-argument lists (`<...>`) aren't tracked as a bracket kind**
  for wrapping, so a comma inside `Map<String, List<Integer>>` may be treated
  as the same depth as its enclosing call - a readability blind spot, not a
  correctness bug.
- **A line wholly inside a text block or string literal that's still over 120
  columns is left as-is and reported** - it cannot be safely wrapped without
  changing its value.
- `switch` case bodies aren't given special per-case indentation.
- Multi-line comment interior lines are left verbatim even if originally
  misindented.
- Column width is measured as `String.length()` (UTF-16 units), not display
  width.
- Indent width (4 spaces) is hardcoded; only the wrap column is configurable
  (see [Configuration](#configuration)).

