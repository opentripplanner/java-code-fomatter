# Style guide

These are the layout rules. Each one was chosen on purpose, and each example uses `wrapAtColumn=100`, `indent=2`.

## Indentation

Code is indented by brace nesting, one `indent` per level. A wrapped line is indented one level more than the
statement it belongs to. There are no tabs and no alignment to an opening bracket.

## Operators go first

A long expression is wrapped at the weakest operator. The operator starts the new line, so the left edge reads as a
list of operands:

```java
return
  isActive
  && hasVerifiedEmail
  && !isBlocked;

String message =
  "Order "
  + order.id()
  + " was placed";
```

In a declaration or a `return` the expression starts on the line below the `=` or the `return`. A long string after
`return` or `throw` is the exception: it stays on its line, because a break would not make it shorter.

## Method chains

A chain that does not fit puts every call on its own line, one level in. The first call is split from the receiver:

```java
return customers
  .stream()
  .filter(c -> c.isActive())
  .toList();
```

## Lambdas

A lambda with an expression body does **not** hug the opening bracket. The opener, the lambda and the closer each get
a line:

```java
items.map(
  item -> compute(item.first(), item.second(), item.third())
)
```

## Control flow

An `if`, `while` or `for` header that is too long wraps as `(`, the content one level deeper, and `)`:

```java
if (
  order.isPaid()
  && customer.hasVerifiedEmail()
) {
```

A `for (Type v : expr)` stays on one line if it fits. If not, it is split after the `:`.

## Switch

A `case ... -> body` arm that is too long puts `->` on its own line, one level under the last label line, even when the
body is a long string. That marks the long line. A block body (`-> {`) never breaks. Labels wrap only when the labels
alone are too long, and labels that you already split are left as you wrote them.

## Generics

The formatter never breaks inside a type argument list (`<...>`). A generic type that is too long is a hint to
refactor, not something to wrap.

## Blank lines

- One blank line **before and after** every multi-line block with braces: types, methods, initializers, `if`, `for`,
  `while`, `do`, `switch`, `try` and `synchronized`.
- The blank line goes above the attached comment, Javadoc or annotations.
- None after `{`, at the start of a file, after a `case` label, or before `}`.
- Blocks without braces, one-line blocks, lambdas, anonymous classes and bodiless declarations are left out.

## Trailing comments

`code; // note` becomes the comment on its own line above, at the same indent. Tool markers stay where they are:
`NOSONAR`, `NOPMD`, `CHECKSTYLE`, `noinspection`, `nosemgrep`, `lgtm`, `nolint` and `$NON-NLS`. In `} else { // note`
the comment moves inside the new block.

## Comments are never dropped

A statement, header or arm that contains a comment in a place the formatter cannot rewrite safely is left as written.

See [Comments and Javadoc](comments.md) for the comment rules.
