package org.tiger.java.code.formatter.maven;

import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.tiger.java.code.formatter.FormatRun;

/** Fails the build if any of the project's Java source files is not formatted. Changes nothing. */
@Mojo(name = "check", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true,
  requiresDependencyResolution = ResolutionScope.NONE)
public class CheckMojo extends AbstractFormatMojo {

  @Override
  protected FormatRun.Mode mode() {
    return FormatRun.Mode.CHECK;
  }

  @Override
  protected void conclude(FormatRun.Report report) throws MojoFailureException {
    failOnErrors(report);

    if (!report.changed().isEmpty()) {
      throw new MojoFailureException(
        report.changed().size() + " Java file(s) are not formatted:" + listing(report.changed())
          + "\nRun 'mvn jcf:format' to fix them."
      );
    }

    getLog().info("Java formatter: all " + report.files().size() + " files are formatted");
  }
}
