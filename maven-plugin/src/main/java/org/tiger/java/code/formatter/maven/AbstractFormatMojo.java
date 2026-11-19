package org.tiger.java.code.formatter.maven;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.tiger.java.code.formatter.FormatRun;

/**
 * What the {@code format} and {@code check} goals share: finding the project's sources (and, where the config asks for
 * it, its JSON and Markdown files), running the formatter over them and reporting. The formatter's own {@code .jcf/config.properties} (found per file, up to the repository root)
 * decides how files are formatted; nothing about formatting is configured here.
 */
abstract class AbstractFormatMojo extends AbstractMojo {

  private static final int MAX_LISTED_FILES = 25;

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  protected MavenProject project;

  /** Skips the goal. */
  @Parameter(property = "jcf.skip", defaultValue = "false")
  protected boolean skip;

  /** Whether to keep and use the cache of files already known to be formatted ({@code .jcf/cache}). */
  @Parameter(property = "jcf.cache", defaultValue = "true")
  protected boolean cache;

  /** Whether the project's test source roots are formatted too. */
  @Parameter(property = "jcf.includeTests", defaultValue = "true")
  protected boolean includeTests;

  /**
   * The source roots to format. Default: the project's compile and test source roots and its resource directories,
   * except any under the build directory (generated sources). JSON and Markdown files are only formatted where
   * {@code formats} in {@code .jcf/config.properties} lists them; add a directory here (for example the one with the
   * documentation) to format the files in it.
   */
  @Parameter
  protected List<File> sourceRoots;

  /**
   * Additional files to format, as glob patterns relative to the project base directory, for example
   * {@code *.md} and {@code docs/**.md}. Use this in the root pom for documentation and other files outside the source
   * roots; files that belong to a submodule are left to that module. Only file types enabled by {@code formats} in
   * {@code .jcf/config.properties} are formatted. Note that {@code **} must be used without a following slash to match
   * files directly in the directory too ({@code docs/**.md}, not {@code docs/**}{@code /*.md}).
   */
  @Parameter
  protected List<String> includes;

  /** Glob patterns, relative to each source root (or the base directory for {@code includes}), of files to leave alone, e.g. {@code **}{@code /generated/**}. */
  @Parameter
  protected List<String> excludes;

  protected abstract FormatRun.Mode mode();

  protected abstract void conclude(FormatRun.Report report) throws MojoFailureException;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    if (skip) {
      getLog().info("Formatter skipped (jcf.skip)");
      return;
    }

    List<Path> files = sourceFiles();

    if (files.isEmpty()) {
      getLog().debug("No source files to format in " + project.getArtifactId());
      return;
    }

    FormatRun.Report report = FormatRun.run(files, mode(), cache);

    for (FormatRun.FileResult result : report.files()) {
      result.errors().forEach(getLog()::error);
      result.warnings().forEach(getLog()::warn);
    }

    report.notices().forEach(getLog()::warn);
    conclude(report);
  }

  /** Fails the build if any file could not be formatted (it is not valid Java, or could not be read or written). */
  protected void failOnErrors(FormatRun.Report report) throws MojoFailureException {
    if (!report.failed().isEmpty()) {
      throw new MojoFailureException(
        report.failed().size() + " file(s) could not be formatted, see the errors above"
      );
    }
  }

  protected static String listing(List<FormatRun.FileResult> results) {
    StringBuilder text = new StringBuilder();

    for (int i = 0; i < Math.min(results.size(), MAX_LISTED_FILES); i++) {
      text.append("\n  ").append(results.get(i).path());
    }

    if (results.size() > MAX_LISTED_FILES) {
      text.append("\n  ... and ").append(results.size() - MAX_LISTED_FILES).append(" more");
    }

    return text.toString();
  }

  private List<Path> sourceFiles() {
    List<Path> roots = new ArrayList<>();

    if (sourceRoots != null && !sourceRoots.isEmpty()) {
      sourceRoots.forEach(root -> roots.add(root.toPath()));
    } else {
      project.getCompileSourceRoots().forEach(root -> roots.add(Path.of(root)));
      project.getResources().forEach(resource -> roots.add(Path.of(resource.getDirectory())));

      if (includeTests) {
        project.getTestCompileSourceRoots().forEach(root -> roots.add(Path.of(root)));
        project.getTestResources().forEach(resource -> roots.add(Path.of(resource.getDirectory())));
      }
    }

    Path buildDirectory = Path.of(project.getBuild().getDirectory()).toAbsolutePath().normalize();
    List<PathMatcher> excluded = new ArrayList<>();

    if (excludes != null) {
      excludes.forEach(glob -> excluded.add(FileSystems.getDefault().getPathMatcher("glob:" + glob)));
    }

    Set<Path> files = new LinkedHashSet<>();

    for (Path root : roots) {
      Path absolute = root.toAbsolutePath().normalize();

      if (absolute.startsWith(buildDirectory) || !absolute.toFile().isDirectory()) {
        continue;
      }

      for (Path file : FormatRun.formattableFilesUnder(absolute)) {
        Path relative = absolute.relativize(file.toAbsolutePath().normalize());

        if (excluded.stream().noneMatch(matcher -> matcher.matches(relative))) {
          files.add(file);
        }
      }
    }

    if (includes != null && !includes.isEmpty()) {
      Path base = project.getBasedir().toPath().toAbsolutePath().normalize();
      List<PathMatcher> included = new ArrayList<>();
      includes.forEach(glob -> included.add(FileSystems.getDefault().getPathMatcher("glob:" + glob)));
      List<Path> moduleDirectories = project.getModules().stream().map(module -> base.resolve(module).normalize()).toList();

      for (Path file : FormatRun.formattableFilesUnder(base)) {
        Path absolute = file.toAbsolutePath().normalize();
        Path relative = base.relativize(absolute);

        if (
          !absolute.startsWith(buildDirectory)
            && moduleDirectories.stream().noneMatch(absolute::startsWith)
            && included.stream().anyMatch(matcher -> matcher.matches(relative))
            && excluded.stream().noneMatch(matcher -> matcher.matches(relative))
        ) {
          files.add(file);
        }
      }
    }

    return new ArrayList<>(files);
  }
}
