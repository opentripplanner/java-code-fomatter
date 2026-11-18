package org.opentripplanner.java.code.formatter.maven;

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
import org.opentripplanner.java.code.formatter.FormatRun;

/**
 * What the {@code format} and {@code check} goals share: finding the project's Java sources, running the formatter over
 * them and reporting. The formatter's own {@code .jcf/config.properties} (found per file, up to the repository root)
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
   * The source roots to format. Default: the project's compile and test source roots, except any under the build
   * directory (generated sources).
   */
  @Parameter
  protected List<File> sourceRoots;

  /** Glob patterns, relative to each source root, of files to leave alone, e.g. {@code **}{@code /generated/**}. */
  @Parameter
  protected List<String> excludes;

  protected abstract FormatRun.Mode mode();

  protected abstract void conclude(FormatRun.Report report) throws MojoFailureException;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    if (skip) {
      getLog().info("Java formatter skipped (jcf.skip)");
      return;
    }

    List<Path> files = javaFiles();

    if (files.isEmpty()) {
      getLog().debug("No Java source files to format in " + project.getArtifactId());
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
        report.failed().size() + " Java file(s) could not be formatted, see the errors above"
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

  private List<Path> javaFiles() {
    List<Path> roots = new ArrayList<>();

    if (sourceRoots != null && !sourceRoots.isEmpty()) {
      sourceRoots.forEach(root -> roots.add(root.toPath()));
    } else {
      project.getCompileSourceRoots().forEach(root -> roots.add(Path.of(root)));

      if (includeTests) {
        project.getTestCompileSourceRoots().forEach(root -> roots.add(Path.of(root)));
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

      for (Path file : FormatRun.javaFilesUnder(absolute)) {
        Path relative = absolute.relativize(file.toAbsolutePath().normalize());

        if (excluded.stream().noneMatch(matcher -> matcher.matches(relative))) {
          files.add(file);
        }
      }
    }

    return new ArrayList<>(files);
  }
}
