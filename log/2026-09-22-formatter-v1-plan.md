# v1: scope-based reindentation + 120-column wrapping for JavaCodeFomatter

## Context

`java-code-fomatter` currently has no real functionality — `JavaCodeFomatter.main` just
prints "Hello, World!". The README's only stated intent is "Format Java source files
using operator precedence and scope." There were no tests, fixtures, or design docs in
the repo beyond that one sentence before this plan.

Scope settled with the user, including two revisions made while designing this:
- Format **indentation/scope only** based on existing brace nesting, plus a hard
  **120-column line width**, enforced by actually wrapping over-long lines (not just
  reporting them). General operator-precedence/parenthesization normalization and
  any other token-spacing changes are explicitly deferred to a later version.
- Parse/validate with the **JDK's own public Compiler Tree API**
  (`javax.tools`/`com.sun.source`), not a third-party parser.
- CLI: `java -m java.code.fomatter [-w|--write] <file.java> [<file.java>...]` —
  default prints the formatted result to stdout; `-w`/`--write` rewrites files in
  place.
- **No brace insertion.** The user initially wanted brace-less control-flow bodies
  (`if (x)\n    foo();`) auto-braced with AST-derived depth, then reversed that:
  v1 must only reformat what's already there. Deliberate deferral — see Known
  Limitations.
- **Testing**: one data-driven "golden file" test that discovers `Input.java` /
  `Expected.java` fixture pairs from a resources directory, so growing coverage
  later is just adding a pair of files, not writing new test code.

Two independent Plan-agent design-review passes (same reviewer, resumed) validated
the approach and caught several real correctness bugs — a text-block whitespace
semantics bug, a `}`-dedent accumulator bug, and — for wrapping — mid-operator-token
splits, numeric-literal `.` splits, and a line-comment-swallowing hazard. All fixes
are incorporated below; nothing in this plan is unreviewed guesswork.

## Design

### Pipeline (`SourceFormatter.format(String source) -> FormatResult`)

1. **Validate** the original source via `javax.tools.ToolProvider.getSystemJavaCompiler()`
   → `JavaCompiler.getTask(...)` cast to `com.sun.source.util.JavacTask` → `task.parse()`
   (parse-only, not `task.call()` — syntax errors without semantic/classpath
   resolution). Diagnostics go to a `DiagnosticCollector<JavaFileObject>`. On any
   `ERROR`-kind diagnostic, abort: return the diagnostics, touch nothing.
   - Confirmed against the installed JDK: `jdk.compiler` exports `com.sun.source.tree`
     and `com.sun.source.util` unconditionally — no `--add-exports` needed.
   - Nothing in this design needs `Trees`, `SourcePositions`, or `TreeScanner` (that
     was only for brace insertion, which is out of scope). `SyntaxValidator`'s whole
     job is `(Path | String source) -> List<Diagnostic<?>>`.
   - Needs **both** `requires java.compiler; requires jdk.compiler;` in
     `module-info.java` (`javax.tools.*` lives in `java.compiler`; `JavacTask` lives
     in `jdk.compiler`).
   - `StandardJavaFileManager.getJavaFileObjectsFromPaths(...)` for real-file input;
     a small `SimpleJavaFileObject` subclass for re-validating in-memory output.

2. **Reindent** (`ScopeReindenter`, driven by `LiteralScanner`): a purely
   character-level, comment/string/text-block-safe, brace-depth-based line
   reindenter — no AST involved.
   - `LiteralScanner` walks the raw source once and produces, for each character
     range that is a comment / char literal / string literal / text block, a
     `VerbatimSpan(start, end, Kind)` — **kind matters**, see the text-block rule
     below — plus the offsets of every non-verbatim `{`, `}`, `(`, `)`, `[`, `]`
     (the bracket pairs are needed by step 3, not this step, but come from the same
     scan). Its comment/string classification must check string-literal content
     *before* comment-start patterns, so `"http://..."` is never misread as a
     comment start.
   - Reindentation is line-by-line:
     - **Text-block spans need special handling, not "whole line inside a span →
       unchanged."** Per JLS 3.10.6, a text block's incidental whitespace is the
       *minimum* indent across every content line **and the line holding the
       closing `"""`**. That closing-delimiter line is only *partially* inside the
       span, so naively reindenting it like ordinary code changes the computed
       minimum and silently alters the string's runtime *value* while staying
       perfectly valid, parseable Java — re-validation in step 4 would not catch
       this. Fix: freeze a line's leading whitespace whenever the first
       non-whitespace character on that line falls inside a `TEXT_BLOCK` span
       (interior content or the closing `"""` itself). Other span kinds don't have
       this cross-line coupling and reindent normally.
     - For a non-frozen line: display indent = `depth * 4` spaces, **except** when
       the line starts with a run of one or more non-verbatim `}` — then display
       indent uses `depth - (count of leading closing braces)` (handles `}`, `});`,
       `} else {`, `} catch (...) {`, and chained closes like `}));`). Strip the
       line's original leading whitespace and prepend the computed indent; leave
       the rest of the line byte-for-byte unchanged.
     - Update the running `depth` for subsequent lines from the line's *actual*
       non-verbatim brace counts (`depth += opens - closes`), computed from the
       true `depth`, **not** the display-adjusted value used for `}`-led lines —
       otherwise every `}`-then-`{` line silently drops a nesting level for
       everything after it.
     - Blank/whitespace-only lines become truly empty.
   - Constants: 4-space indent, output always uses `\n` line endings. 120-column
     width is measured as `String.length()` (UTF-16 units) — non-ASCII-heavy lines
     (CJK, emoji/surrogate pairs) won't match real terminal width; acceptable v1
     simplification, documented as a limitation.

3. **Wrap long lines** (`LineWrapper`, post-pass over `ScopeReindenter`'s output,
   reusing `LiteralScanner`): for each line over 120 columns, insert line breaks at
   safe token boundaries so no segment exceeds the budget.
   - Bracket depth (`(`/`)`/`[`/`]`, tracked separately from `{`/`}`) is a running
     accumulator carried **across lines**, exactly like `ScopeReindenter`'s brace
     depth — not reset per line — so a comma on a line that continues an
     already-open, unclosed group from a previous line is still correctly judged
     "top-level" or not.
   - The candidate search range on a line is hard-truncated to
     `[line start, first non-verbatim "//" on that line)` — never search past a
     trailing line comment. Combined with `LiteralScanner`'s
     string-before-comment precedence check above, this is what prevents a break
     from ever landing inside or after a `//` comment and turning trailing comment
     text into live code on the next line.
   - **A line that is categorically unwrappable is detected up front and skipped
     entirely**, not discovered by "no candidate found": any line whose content in
     the search range is wholly inside a `TEXT_BLOCK` span (interior or the closing
     `"""` line) or wholly inside a plain string/char literal has *no* legal break
     point — inserting a newline there would change the literal's value (same JLS
     3.10.6 hazard as step 2) or isn't possible at all. Such lines are left
     untouched and reported (file:line) as an over-length line the formatter
     couldn't safely shorten.
   - Otherwise, search for a break point, walking bracket depth **outward-in**:
     starting from the shallowest depth present on the line, look for the last
     comma at that depth whose preceding segment still fits the column budget; if
     none fits, retry at the next depth in (so a lambda's own parameter-list comma,
     or a nested call's commas, are tried before giving up on commas altogether).
     Only if no depth yields a fitting comma, fall back to breaking before a
     binary/logical operator (`&&`, `||`, `==`, `+`, etc.) or before a `.` in a
     method chain, same outward-in depth order. If nothing fits, the line is left
     untouched and reported, same as the categorically-unwrappable case.
   - **Operators must be matched as real multi-character tokens, longest-match
     first** (`>>>=`, `<<=`, `>>=`, `->`, `::`, `&&`, `||`, `==`, `!=`, `<=`, `>=`,
     compound assignments, `<<`, `>>`, `>>>`, `++`, `--`, then single-char
     operators) — a naive single-character scan can split `&&`/`->`/etc. in half
     and produce illegal or meaning-changing code. A `.` candidate must additionally
     check it isn't a numeric-literal decimal point (not immediately adjacent to
     digits on both sides) before being treated as a method-chain break.
   - A bare `:` is **never** treated as a break candidate — it's ambiguous between
     ternary, enhanced-for, `case X:`, and labeled statements without an AST, and
     the readability win isn't worth the misclassification risk.
   - Generic type-argument commas (`Map<String, List<Integer>>`) are not tracked as
     a separate bracket kind (`<`/`>` isn't reliably distinguishable from
     less-than), so they read as the same depth as their enclosing call — a known
     readability-only blind spot, not a correctness bug (see Known Limitations).
   - Continuation lines are indented at the *original line's pre-dedent logical
     depth* (i.e. before any `}`-leading adjustment) `+ 4`. If a chosen break
     leaves a continuation fragment starting with a non-verbatim `}`, that
     fragment gets its own depth-1 treatment (the same rule `ScopeReindenter` uses),
     so wrapped and unwrapped `}`-led lines stay visually consistent. Any
     continuation fragment still over 120 columns is wrapped again, recursively.
   - This step only inserts whitespace/line breaks at token boundaries, so it
     cannot change program semantics *if correct*; most of its failure modes
     (mid-operator split, mid-literal split, comment-swallow) produce outright
     unparseable output, so step 4's re-validation is a real safety net here
     (unlike the text-block-value bug in step 2, which stays syntactically valid).

4. **Re-validate** the final output through the same validator (in-memory `String`)
   as a safety net against `LiteralScanner`/`LineWrapper` boundary bugs corrupting
   structure. If re-validation fails, treat it as an internal formatter bug: abort,
   report where formatting happened, and don't write/print the broken result. It
   does **not** catch the text-block-value bug from step 2 — that fix has to be
   correct in the algorithm itself.

### Files

Production code, all under `src/main/org/tiger/java/code/formater/`:
- `JavaCodeFomatter.java` — rewritten `main`: parse `-w`/`--write` + file args, run
  the pipeline per file, stdout-or-write output, exit code (0 if all files
  succeeded and had nothing left unwrapped/failed, 1 otherwise).
- `SourceFormatter.java` — orchestrator: validate → reindent → wrap → re-validate.
- `SyntaxValidator.java` — `(Path | String) -> List<Diagnostic<?>>`, per step 1.
- `ScopeReindenter.java` — step 2.
- `LineWrapper.java` — step 3.
- `LiteralScanner.java` — shared hand-rolled comment/string/char/text-block span
  scanner + non-verbatim bracket finder, used by both of the above. This is the
  highest-risk file (only engine driving output correctness, no AST fallback) —
  needs real edge-case coverage, see Testing.
- `module-info.java` — add `requires java.compiler; requires jdk.compiler;` to the
  existing `module java.code.fomatter { exports org.tiger.java.code.formater; }`.

Test code (new `src/test/` source root, see Testing section for how it's wired):
- `src/test/module-info.java` — a `@jenesis.test java.code.fomatter`-tagged test
  module requiring `java.code.fomatter` plus JUnit Jupiter.
- `src/test/org/tiger/java/code/formater/FormatterGoldenFileTest.java` — the single
  data-driven test described below.
- `src/test/resources/format-cases/<case-name>/{Input.java,Expected.java}` — one
  directory per fixture; this is what grows over time.

### Known, deliberately accepted v1 limitations (document in README)

- **Brace-less control-flow bodies are not specially indented**, and a brace-less
  body a human already indented correctly can be *flattened* to its header's depth,
  since nothing encodes its nesting without an actual brace. Direct, explicit cost
  of deferring brace insertion.
- No continuation/hanging indent for a long statement that already fits under 120
  columns but spans multiple lines for other reasons — only over-120 lines get
  wrapped.
- Generic type-argument lists (`<...>`) aren't tracked as a bracket kind for
  wrapping purposes, so their commas may be treated as the same depth as their
  enclosing call/expression — a readability blind spot, not a correctness bug.
- A line that's wholly inside a text block or string literal and still over 120
  columns is left as-is and reported — it categorically cannot be safely wrapped.
- `switch` case bodies aren't given special per-case indentation.
- Multi-line comment interior lines are left verbatim even if originally
  misindented.
- Column width is `String.length()` (UTF-16 units), not display width.
- Hardcoded 4-space indent and 120-column width, no configuration.

## Testing

Data-driven golden-file test, as the user asked for — one test, many fixture pairs,
so coverage grows by adding files, not code:

- **`FormatterGoldenFileTest`** is a JUnit 5 `@ParameterizedTest` sourced from a
  `@MethodSource` that scans `format-cases/` on the test-resources classpath and
  yields one case per subdirectory containing both `Input.java` and `Expected.java`
  (display name = the subdirectory name, so failures are easy to identify). Test
  body per case: `SourceFormatter.format(Input.java's text)` must equal
  `Expected.java`'s text exactly; additionally, `format(Expected.java's text)` must
  equal itself (idempotency — formatting already-formatted code is a no-op), which
  this loop gets for every fixture at no extra authoring cost.
- Two additional, hand-written tests (not part of the golden-file loop, since their
  assertions aren't "equals this text"): one asserting a syntactically invalid file
  is rejected with diagnostics and left untouched, one asserting an unwrappable
  over-length line (inside a text block) is left untouched and reported rather than
  corrupted.
- **Jenesis wiring**: tag `src/test/module-info.java` with `@jenesis.test
  java.code.fomatter` (module variant of the main module, per the help text at
  `build/jenesis/Project.java:680`); Jenesis infers `framework=junit-platform` from
  the resolved JUnit dependency. Add the JUnit Jupiter dependency via `@jenesis.pin`
  on that test module-info — exact coordinates/checksums to be resolved with
  `java build/jenesis/Make.java pin` during implementation rather than guessed here.
  `java build/jenesis/Make.java` (the default target) already compiles, tests, and
  packages per the README, so no new invocation is needed to run the suite.
- **Seed fixtures to create immediately** (each earns its keep by catching a
  specific bug class found during design review):
  - `nested-blocks` — baseline multi-level reindent, no edge cases.
  - `text-block-preserved` — a text block whose exact string *value* must survive;
    write the test assertion to also confirm the compiled value is byte-identical,
    not just eyeballed formatting, since this is the one bug class re-validation
    can't catch.
  - `else-catch-chain` — `} else {` / `} catch (...) {` at 2+ nesting levels, to
    catch the accumulator-uses-display-depth bug.
  - `chained-close` — a `}));`-style line, to check leading-close-count dedent.
  - `comment-and-string-literal-scanning` — a line comment containing `/*`, a
    string literal containing `//` and one shaped like a URL, escaped quotes in a
    text block, an escaped single-quote char literal.
  - `line-wrap-comma` — an over-120 call/argument list wrapped at a top-level
    comma.
  - `line-wrap-operator-fallback` — an over-120 boolean/arithmetic expression with
    no usable top-level comma, wrapped at an operator, confirming multi-char
    operators (`&&`, `->`, etc.) are never split mid-token.
  - `line-wrap-nested-comma-fallback` — an over-120 line where the shallowest depth
    has no fitting comma (e.g. a long lambda argument), confirming the outward-in
    retry picks the lambda's own parameter comma rather than jumping straight to
    an operator split.
  - `line-wrap-trailing-comment` — an over-120 line with a trailing `//` comment,
    confirming the break never lands inside or after it.
  - `line-wrap-brace-continuation` — a wrapped line whose continuation fragment
    starts with `}`, confirming it gets the same dedent as an unwrapped `}`-led
    line.
