package org.tiger.java.code.formatter.maven;

import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.tiger.java.code.formatter.FormatRun;

/** Formats the project's Java source files in place. */
@Mojo(name = "format", defaultPhase = LifecyclePhase.PROCESS_SOURCES, threadSafe = true,
  requiresDependencyResolution = ResolutionScope.NONE)
public class FormatMojo extends AbstractFormatMojo {

  @Override
  protected FormatRun.Mode mode() {
    return FormatRun.Mode.WRITE;
  }

  @Override
  protected void conclude(FormatRun.Report report) throws MojoFailureException {
    for (FormatRun.FileResult result : report.changed()) {
      getLog().info("Formatted " + result.path());
    }

    getLog().info(
      "Java formatter: " + report.files().size() + " files, " + report.changed().size() + " formatted, "
        + (report.count(FormatRun.Status.UNCHANGED) + report.count(FormatRun.Status.CACHED)) + " already formatted"
    );
    failOnErrors(report);
  }
}
