# Configuration

Put a `.jcf/config.properties` file in your project:

```properties
wrapAtColumn=100
indent=2
```

No file means the defaults. The formatter reads configuration files that exist. It never creates a `.jcf` folder in a
source directory (only the cache is created, at the repository root).

| Key | Default | Meaning |
| --- | ------- | ------- |
| `wrapAtColumn` | `120` | The column at which a line is wrapped. |
| `indent` | `4` | Spaces per indent level. It is used for scopes, wrapped lines and every wrapping step. |
| `commentRules` | `all` | Which comment rules are on: `all`, `none`, or a comma-separated list. See [Comments](comments.md). |
| `chainedBlocks` | `newline` | `newline` puts `else`, `else if`, `catch` and `finally` on their own line after the `}`. `attached` keeps `} else {`. See [Style guide](style.md). |
| `formats` | `java` | The file types to format: `java`, `json`, `markdown` or `all`. See [JSON and Markdown](json-markdown.md). |
| `jsonWrapAtColumn` | `80` | The width for JSON files. |
| `markdownWrapAtColumn` | `80` | The width for Markdown files. |
| `markdownProseWrap` | `always` | `always` or `preserve` (keep the line breaks in paragraphs). |

Column width is counted in UTF-16 units (`String.length()`), not in display width.

## Layering

The configuration is found **per file**. The formatter reads the `.jcf/config.properties` of every directory from the
file's own directory up to the *outermost repository root*. The root is the topmost ancestor that has a `.git` entry,
which is a directory, or the file that a submodule or a worktree has. A file that is nearer to the source overrides
only the keys that it sets, and the other keys come from further up.

```
repo/
├── .git
├── .jcf/config.properties          wrapAtColumn=100  indent=2
├── app/
│   └── src/...                     uses 100 / 2
└── legacy/
    ├── .jcf/config.properties      wrapAtColumn=120      (indent stays 2)
    └── src/...                     uses 120 / 2
```

A submodule inherits the settings of the repository that contains it. Outside a repository, only the file's own
directory counts.

## The same config everywhere

The command line, the Maven plugin and the Java API all read the same files. Your build and the command that you run
in the terminal therefore always give the same answer.
