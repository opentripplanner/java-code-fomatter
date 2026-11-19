# Safety

A formatter that rewrites a whole code base must not change what the code does. Three checks protect you.

## 1. The input must be valid Java

Before anything else the file is parsed (without resolving a classpath). If it has a syntax error, the file is left
alone and the errors are reported. The exit code is 1.

## 2. The output is parsed again

The result is parsed too. If a step produced invalid Java, nothing is written.

## 3. The program is compared token by token

The formatter splits the input and the result into tokens, ignoring whitespace and comments. String and character
literals, text blocks and numbers are compared exactly. If the two token lists differ, nothing is written and the file
is reported as an internal error:

```
formatting changed the program (...); this is a formatter bug, not a problem with the input
```

With `removeUnusedImports=true`, the input of this comparison is the source with its unused imports removed, so the
only change to the tokens that is allowed is the removal of whole import statements.

Such a message is a bug in the formatter. Please report it with the input file.

The check runs on every file, every time. It was also run on all 4,299 Java files of OpenTripPlanner with no
difference.

## What the check does not cover

Comments can change. The comment rules reflow Javadoc and wrap `//` lines, and a trailing comment moves above its
statement. The formatter never drops a comment: a statement that contains a comment in a place it cannot move safely
is left as written. Turn the rules off with [`commentRules=none`](comments.md) if you want comments to stay as they
are.

## Lines that cannot be fixed

A line that is over the limit and has no safe place to break is left unchanged and reported as a warning. It does not
fail a run.
