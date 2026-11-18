# Comments and Javadoc

Comments are formatted by a set of rules. Each rule changes only what it owns. Choose them with `commentRules` in
[`.jcf/config.properties`](configuration.md):

```properties
commentRules=all                  # the default
commentRules=none                 # do not touch comments
commentRules=wrap,paragraphs      # only these rules
```

| Rule | What it does |
| ---- | ------------ |
| `wrap` | Wraps Javadoc and `///` lines that are over the limit. Lines are split, never joined. |
| `paragraphs` | Every paragraph tag (`<p>`, `<P>`, `<p/>`, `<p />`, `<p>Text`, a `<p>` in the middle of a line) becomes one `<p>` on its own line. A blank line between two paragraphs becomes `<p>`. A closing `</p>`, and a `<p>` before an HTML block, are dropped. |
| `blank-lines` | One blank line before the first block tag (`@param`, ...) and before headings. Blank edges are trimmed. |
| `indent` | Continuation lines of block tags and list items get a hanging indent. |
| `fill` | Joins lines that were broken too early and refills each line up to the limit. It includes the four rules above. |
| `line-comments` | Wraps `//` comments on their own line when they are over the limit. |

With `commentRules=wrap`, nothing but over-long lines changes.

## Example

Input:

```java
/**
 * Finds the thing.<p>Second paragraph that is quite long and goes well past the column limit so it has to be wrapped somewhere.
 * @param id the id
 * @return the thing
 */
```

Result with `all` at 100 columns:

```java
/**
 * Finds the thing.
 * <p>
 * Second paragraph that is quite long and goes well past the column limit so it has to be wrapped
 * somewhere.
 *
 * @param id the id
 * @return the thing
 */
```

## Markdown Javadoc

`///` comments are wrapped and refilled in the same way.

## Trailing comments

A comment at the end of a code line moves to its own line above. This is part of the layout, not a comment rule. See
the [style guide](style.md#trailing-comments).
