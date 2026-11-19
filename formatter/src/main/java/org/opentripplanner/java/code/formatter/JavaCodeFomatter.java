package org.opentripplanner.java.code.formatter;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The command line entry point. Arguments are files and/or directories (every {@code .java}, {@code .json} and {@code .md} file under them; in a
 * git repository, the tracked and untracked-but-not-ignored ones). See {@link FormatRun} for what a run does.
 * <p>
 * Exit codes: 0 when every file was formatted or is already formatted (and, with {@code --check}, none would change); 1
 * when a file failed (not valid Java, unreadable) or, with {@code --check}, when a file would change; 2 for a usage
 * error. Lines the formatter cannot bring under the limit are reported as warnings and never fail a run.
 */
public class JavaCodeFomatter {

  private JavaCodeFomatter() {
  }

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  /** Runs the formatter; returns the process exit code (see the class description). */
  public static int run(String[] args, PrintStream out, PrintStream err) {
    boolean write = false;
    boolean check = false;
    boolean changedOnly = false;
    boolean useCache = true;
    boolean verbose = false;
    List<String> targets = new ArrayList<>();

    for (String arg : args) {
      switch (arg) {
        case "-w", "--write" -> write = true;
        case "--check" -> check = true;
        case "--changed" -> changedOnly = true;
        case "--no-cache" -> useCache = false;
        case "-v", "--verbose" -> verbose = true;
        case "-h", "--help" -> {
          printUsage(out);
          return 0;
        }
        default -> {
          if (arg.startsWith("-")) {
            err.println("Unknown option: " + arg);
            printUsage(err);
            return 2;
          }

          targets.add(arg);
        }
      }
    }

    if (write && check) {
      err.println("--write and --check cannot be used together");
      return 2;
    }

    if (targets.isEmpty() && !changedOnly) {
      printUsage(err);
      return 2;
    }

    FormatRun.Mode mode = check ? FormatRun.Mode.CHECK : write ? FormatRun.Mode.WRITE : FormatRun.Mode.PRINT;
    FormatRun.Report report = FormatRun.run(collectFiles(targets, changedOnly), mode, useCache);

    for (FormatRun.FileResult result : report.files()) {
      result.errors().forEach(err::println);
      result.warnings().forEach(err::println);

      if (mode == FormatRun.Mode.PRINT && result.formattedText() != null) {
        out.print(result.formattedText());
      } else if (check && result.status() == FormatRun.Status.CHANGED) {
        out.println("would reformat: " + result.path());
      }
    }

    report.notices().forEach(notice -> err.println("warning: " + notice));

    if (verbose) {
      long changed = report.count(FormatRun.Status.CHANGED);
      err.println(report.files().size() + " files: " + changed + (check ? " would be reformatted, " : " formatted, ")
        + report.count(FormatRun.Status.UNCHANGED) + " already formatted, " + report.count(FormatRun.Status.CACHED)
        + " skipped (cached), " + report.count(FormatRun.Status.FAILED) + " failed");
    }

    boolean failed = !report.failed().isEmpty() || check && !report.changed().isEmpty();

    return failed ? 1 : 0;
  }

  private static List<Path> collectFiles(List<String> targets, boolean changedOnly) {
    Set<Path> files = new LinkedHashSet<>();

    for (String target : targets) {
      Path path = Path.of(target);

      if (Files.isDirectory(path)) {
        files.addAll(FormatRun.formattableFilesUnder(path));
      } else {
        files.add(path);
      }
    }

    if (changedOnly) {
      files.addAll(FormatRun.changedFormattableFiles(Path.of("")));
    }

    return new ArrayList<>(files);
  }

  private static void printUsage(PrintStream out) {
    out.println("Usage: java -m org.opentripplanner.java.code.formatter [options] <file|directory>...");
    out.println("  -w, --write     rewrite files in place (default: print the formatted text)");
    out.println("      --check     list the files that would change and exit 1 if there are any; write nothing");
    out.println("      --changed   also format the files git reports as changed or untracked");
    out.println("      --no-cache  ignore and do not update the " + FormatterConfig.DIR_NAME + "/cache");
    out.println("  -v, --verbose   print a summary line");
    out.println("Reads " + FormatterConfig.DIR_NAME + "/" + FormatterConfig.FILE_NAME
      + " from each file's directory up to the outermost repository root; nearer files override.");
  }
}
