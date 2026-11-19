# Limitations

Honest list of what the formatter does not do. It is a layout formatter first.

## Spacing inside a line is only partly normalized

The formatter sets the spaces between tokens on one line: `int   a=1,b=2;` becomes `int a = 1, b = 2;`, `if(x){`
becomes `if (x) {`, `foo (a , b)` becomes `foo(a, b)`, and `x->x+1` becomes `x -> x + 1`. It never changes a gap that
has a line break in it, a gap next to a comment, or the inside of a string or text block.

It does not use a parser for this, so a gap is left as written where the tokens alone cannot say what it should be:

- `<` and `>`: `a<b` could be a comparison or the start of generics. `a>b` and `a<b` stay as written.
- `-` or `+` after `)`: `(int)-x` could be a cast or a subtraction.
- The `:` of a `case` label, a `default` label, a statement label and `assert`: `case 1 :` stays as written.
- The gap before `;` or `)` after a `;` in a `for` header, so both `for (;;)` and `for (a; b; )` are kept.
- A `{` or `}` next to an array initializer's items: `{1, 2 }` keeps its space before `}`.

## Multi-declarator statements

`int a = 1, b = 2;` is not split into two statements.

## Brace-less bodies

The formatter does not add braces. The body of `if (x)` without braces keeps the indentation that it has, because
there is no brace to say how deep it is.

## Method signatures and annotations

Long parameter lists, `throws` lists and annotation arguments are wrapped by a simpler, greedy wrapper at the column
limit. They are not wrapped by operator precedence the way declarations, assignments, `return`, `throw` and control-flow
headers are, so the result can look different from the lines around it:

```java
public static Result compute(String firstArgument, String secondArgument,
  int thirdArgument, Option fourth) throws IOException, InterruptedException {
```

## Lines that cannot be wrapped

A line that is wholly inside a text block, a string literal or a URL, and that is still too long, is left unchanged and
reported as a warning.

## Other

- A wrapping step that would not make the longest line shorter is skipped.
- The width is measured in UTF-16 units, not in display columns.
- Only Java is formatted. Markdown, JSON and GraphQL are not.
- Import order and other static analysis are not handled. Keep a tool such as Spotless for them. Unused imports are removed only with `removeUnusedImports=true`, by name: an import stays if its name appears anywhere in the file, so a static import of an overloaded name that is used for only one overload is kept. Wildcard imports are never removed.
- The Maven plugin needs Maven to run on JDK 25 or newer.
