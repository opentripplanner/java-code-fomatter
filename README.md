# Java code formatter

Most people do not read code like a novel, more like an encyclopedia. They scan it from top to bottom, and only then
from left to right. Therefore, scanning down the file should be fast and provide the reader with easy access to the
structure of the code. This formatter is built for that.

Scope matters, code that has the same scope should have the same indention - either all on one line or all siblings 
listed in one column. Important information goes _first_ on the line so you do not have to scan to the end to see it! 
For example operators go first on a wrapped line, so the left edge reads as a list of what is being combined. A line
that is too long is broken where the expression itself divides: at the operator that binds the weakest (`+` before `*`),
not at the last space before the column limit. Each part then gets its own line, indented one level. 

- Javadoc and comments are formatted too.
- It is fast(only changes are scanned)
- It is built to run in CI
- It is safe, and it can never change what your program does.


```java
// before: one 160-character line
String message = "Order " + order.id() + " for customer " + customer.name() + " was placed at " + clock.now() + " and is pending";

// after
String message =
  "Order "
  + order.id()
  + " for customer "
  + customer.name()
  + " was placed at "
  + clock.now()
  + " and is pending";
```

## Why use it

- **Fast.** Files run in parallel, and a content-hash cache skips files that are already formatted. A repeat check of all
  OTP's modules, about 4,300 files, takes about a second.
- **Made for builds.** `--check` fails the build when a file is not formatted. A Maven plugin and a Java API are
  included. See [Maven](maven-plugin/README.md), [Jenesis](docs/jenesis.md) and the [API](docs/api.md).
- **Format Javadoc and comments as well.** `<p>` tags, blank lines before `@param`, hanging indents and line fill are
  all handled, and each rule can be switched off. See [Comments and Javadoc](docs/comments.md).
- **One small config file.** Two keys cover most projects, and the file is layered per directory. See
  [Configuration](docs/configuration.md).

## Quick start

You need JDK 25 or newer.

```bash
# print the formatted result
java -m org.tiger.java.code.formatter src/main/java/Foo.java

# rewrite a whole tree in place
java -m org.tiger.java.code.formatter -w src

# CI: list the files that need formatting and exit 1 if there are any
java -m org.tiger.java.code.formatter --check src
```

Add `.jcf/config.properties` at the root of your repository to choose the width and indent:

```properties
wrapAtColumn=100
indent=2
```

All options are in the [command line reference](docs/cli.md).

## Maven Setup

Bind the plugin once. `format` rewrites files, `check` fails the build when a file is not formatted.

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

On a CI server use the `check` goal instead. Formatting rules come from `.jcf/config.properties`, not from the pom, so
your editor, the command line and Maven always agree. The plugin needs Maven to run on JDK 25 or newer. All
parameters are in the [Maven plugin guide](maven-plugin/README.md).

## Use it with Jenesis

This project is built with [Jenesis](https://jenesis.build/), and the formatter is a plain Java module. You can call it
from a test, so `java build/jenesis/Make.java` fails when a source file is not formatted:

```java
@Test
void sourcesAreFormatted() {
  var report = FormatRun.run(FormatRun.javaFilesUnder(Path.of("src")), FormatRun.Mode.CHECK, true);

  assertTrue(report.failed().isEmpty() && report.changed().isEmpty(), "run the formatter with -w");
}
```

See [Jenesis](docs/jenesis.md) for the full setup.

## How it differs from google-java-format and Prettier

All three give good results. They differ in a few places that you notice every day. The code below is real output from
google-java-format 1.36.1 and prettier-java 2.10.3 (prettier 3.9.6, width 100, 2 spaces), the versions
OpenTripPlanner uses.

**A long condition.** Prettier ends the line with `&&`. google-java-format keeps `if (` glued to the first operand.
This formatter puts every operand in the same column, and the operator first.

```java
// this formatter
if (
  order.isPaid()
  && customer.hasVerifiedEmail()
  && !customer.isBlocked()
  && order.total() > customer.creditLimit()
) {

// google-java-format
if (order.isPaid()
    && customer.hasVerifiedEmail()
    && !customer.isBlocked()
    && order.total() > customer.creditLimit()) {

// Prettier
if (
  order.isPaid() &&
  customer.hasVerifiedEmail() &&
  !customer.isBlocked() &&
  order.total() > customer.creditLimit()
) {
```

**A long `case`.** Prettier explodes the labels, one per line, and the body ends up glued to the last label. This
formatter keeps the labels as you wrote them and puts `->` on its own line, so the long body stands out.

```java
// this formatter
case NEW, PENDING_PAYMENT, PENDING_SHIPMENT, PENDING_CONFIRMATION_FROM_CUSTOMER
  -> "Order is still waiting for something to happen";

// google-java-format
case NEW, PENDING_PAYMENT, PENDING_SHIPMENT, PENDING_CONFIRMATION_FROM_CUSTOMER ->
    "Order is still waiting for something to happen";

// Prettier
case
  NEW,
  PENDING_PAYMENT,
  PENDING_SHIPMENT,
  PENDING_CONFIRMATION_FROM_CUSTOMER -> "Order is still waiting for something to happen";
```

**Trailing comments.** Prettier and google-java-format leave `int count = 0; // retries` where it is. This formatter
moves the comment to its own line above (tool markers such as `NOSONAR` stay), so that a long line never pushes the
comment off the screen.

**Blank lines.** This formatter always puts one blank line before and after a multi-line block (`if`, `for`,
`switch`, `try`, methods, types). The other two do not add them.

**Javadoc.** Prettier does not touch Javadoc, so `<p>` in the middle of a line and a 130-character line stay as
they are. google-java-format and this formatter both rewrite it. They differ in the paragraph tag: google-java-format
writes `<p>Text` on one line after a blank line, this formatter writes `<p>` alone on its line.

```java
// this formatter
/**
 * Finds the thing.
 * <p>
 * Second paragraph that is quite long and goes well past the column limit so it has to be wrapped
 * somewhere.
 *
 * @param id the id
 */

// google-java-format
/**
 * Finds the thing.
 *
 * <p>Second paragraph that is quite long and goes well past the column limit so it has to be
 * wrapped somewhere.
 *
 * @param id the id
 */
```

**Where it is the same.** All three put each call of a long method chain on its own line. The only difference is
the first call: google-java-format keeps `customers.stream()` together, the other two split it.

The full sample, with all three outputs side by side, is in [the comparison](docs/comparison.md), together with an
honest list of what this formatter does *not* do yet.

## Status

The formatter replaces Prettier for the Java layout of a project that is already formatted. It does **not** yet
normalize the spacing inside a line (`int   a=1,b=2;` stays as written), so on messy input run a spacing tool first.
Markdown, JSON and GraphQL are not formatted. Static analysis, such as removing unused imports, is left to other tools
(Spotless and Checkstyle). See [Limitations](docs/limitations.md).

## Documentation

| Document | What is in it |
| -------- | ------------- |
| [Command line](docs/cli.md) | Options, exit codes, output and the cache |
| [Configuration](docs/configuration.md) | `.jcf/config.properties`, layering, all keys |
| [Style guide](docs/style.md) | Every layout rule, with examples |
| [Comments and Javadoc](docs/comments.md) | The comment rules and how to switch them |
| [Safety](docs/safety.md) | Validation, the token check, what happens on failure |
| [Maven plugin](maven-plugin/README.md) | Goals, parameters, source roots, releasing |
| [Jenesis](docs/jenesis.md) | Running the formatter in a Jenesis build |
| [Java API](docs/api.md) | `FormatRun` and `SourceFormatter` |
| [Comparison](docs/comparison.md) | Side by side with google-java-format and Prettier |
| [Limitations](docs/limitations.md) | What it does not do yet |
| [Development](docs/development.md) | Build, tests and fixtures |
