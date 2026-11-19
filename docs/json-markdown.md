# JSON and Markdown

The formatter can also format `.json` and `.md` files, in the style of Prettier. This is off by default. Turn it on in
`.jcf/config.properties`:

```properties
formats=all
jsonWrapAtColumn=80
markdownWrapAtColumn=100
```

| Key | Default | Meaning |
| --- | ------- | ------- |
| `formats` | `java` | The file types to format: `all`, or a comma-separated list of `java`, `json` and `markdown`. A file of a type that is not listed is not touched. |
| `jsonWrapAtColumn` | `80` | The width for JSON. The indent is `indent`. |
| `markdownWrapAtColumn` | `80` | The width for Markdown. |
| `markdownProseWrap` | `always` | `always` fills paragraphs to the width. `preserve` keeps the line breaks that are in the file. |

The command line and the Maven plugin pick up `.json`, `.md` and `.markdown` files next to the Java files. The cache,
`--check`, `--write` and the exit codes work as for Java.

## JSON

- One indent step per level, and `{ "a": 1 }` and `[1, 2]` stay on one line when they fit.
- An object with a line break after its `{` in the source stays expanded. An array of several objects (or arrays) with
  more than one entry each is always expanded. Arrays of numbers are filled.
- One blank line between members is kept. Keys, strings and numbers are copied as written. The order never changes.
- `//` and `/* */` comments are kept. A comment on its own line stays above the entry that follows it, and a comment
  after an entry on the same line stays after it. A container with a comment is always expanded.
- A trailing comma is dropped.
- The result is parsed again and must be the same JSON value, with the same comments, as the input.
- A file that is not JSON (several values), or has a comment in an odd place (between a key and its value), is **not
  changed**, and a warning says so.

## Markdown

- Paragraphs are filled to the width. A paragraph is not broken inside a code span, an inline link or an HTML tag. It is
  never broken where the next line would start a list, a heading or a quote.
- `#` headings, `---` for thematic breaks, `-` for bullets (`*` for a list that follows another list), and ordered lists
  are numbered like Prettier does (`1. 1. 1.` stays, `1. 2. 3.` counts up).
- Nested content is indented to the list marker. Block quotes are formatted inside. Task list checkboxes are kept.
- Tables are aligned. Fenced code uses backticks. There is one blank line between blocks.
- `*text*` becomes `_text_` and `__text__` becomes `**text**`, when the marks stand at the edge of a word.
- Code blocks, HTML blocks, front matter, setext headings and the code inside fences are copied as written.
- The result must keep the same letters and digits, in the same order, as the input (list numbers aside). If it does not,
  the file is **not changed**, and a warning says so.

## What is different from Prettier

The output was compared with Prettier 3.9 on a few hundred Markdown and JSON files. The JSON output is the same for all
of them. The Markdown output is the same for almost all files that use plain Markdown. These things are not done:

- YAML front matter, and the code in fenced blocks (Prettier formats JSON, JavaScript and so on inside them).
- Some rare cases of Markdown (an HTML tag that spans lines, reference links with unusual labels, wide characters).
