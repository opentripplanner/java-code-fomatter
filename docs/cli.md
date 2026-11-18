# Command line

```bash
java -m org.opentripplanner.java.code.formatter [options] <file.java|directory>...
```

The formatter is a Java module. Put the jar on the module path (`-p formatter.jar`) if it is not already there. A
directory means every `.java` file under it. In a git repository that is the tracked files and the untracked files that
are not ignored. Files run in parallel.

| Option | Meaning |
| ------ | ------- |
| *(none)* | Print the formatted text to standard output. Nothing is written. |
| `-w`, `--write` | Rewrite the files that are not formatted. A file that is already formatted is not touched. |
| `--check` | Write nothing. Print `would reformat: <file>` for each file that would change. |
| `--changed` | Also take the files that git reports as modified or untracked. Needs git. |
| `--no-cache` | Do not read or update the cache. |
| `-v`, `--verbose` | Print one summary line. |
| `-h`, `--help` | Print the usage. |

`--write` and `--check` cannot be combined.

## Exit codes

| Code | Meaning |
| ---- | ------- |
| `0` | Every file is formatted. With `--check`, no file would change. |
| `1` | A file failed (not valid Java, or it could not be read or written). With `--check`, also when a file would change. |
| `2` | Usage error. |

A line that is still too long and has no safe place to break (a long URL or a long string) is a **warning**. It never
sets exit code 1. Otherwise a build would fail for ever on a line that cannot be fixed.

## Examples

```bash
# format what you changed, before a commit
java -m org.opentripplanner.java.code.formatter -w --changed

# the same check that CI runs
java -m org.opentripplanner.java.code.formatter --check -v src
```

## The cache

A run records every file that it finds formatted (a hash of the content and its effective configuration) in
`<repository root>/.jcf/cache`. A later run skips those files without parsing them. After an edit, only the edited
files are formatted again.

- The cache is keyed by content, so it survives a branch switch.
- It is thrown away when the formatter itself changes.
- The folder contains a `.gitignore` with `*`, so you do not need to ignore it.
- It is safe to delete at any time. `--no-cache` ignores it.
- In `--check` mode the cache is only used to record files that are already formatted.
