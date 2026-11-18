# Limitations

Honest list of what the formatter does not do. It is a layout formatter first.

## Spacing inside a line is not changed

The formatter does not add or remove spaces between tokens. `int   a=1,b=2;` stays as written, and so does
`if(x){`. Code that is already formatted by another tool (such as Prettier or google-java-format) has the spacing in
place, and the formatter only changes the layout. On messy input, run a spacing tool first. Normalizing the spacing is
on the list.

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
- Unused imports, import order and other static analysis are not handled. Keep a tool such as Spotless for them.
- The Maven plugin needs Maven to run on JDK 25 or newer.
