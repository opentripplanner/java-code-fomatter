package org.tiger.java.code.formatter.intellij;

import com.intellij.formatting.service.AsyncDocumentFormattingService;
import com.intellij.formatting.service.AsyncFormattingRequest;
import com.intellij.formatting.service.FormattingService;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Set;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.tiger.java.code.formatter.FormatRun;

/**
 * Makes <b>Reformat Code</b> (and reformat on save) use the Java code formatter library for the files it handles:
 * Java, and JSON and Markdown where {@code formats} in {@code .jcf/config.properties} lists them. Any other file, and
 * a reformat of a selection only, is left to the IDE's own formatter.
 * <p>
 * The text of the editor buffer is formatted in-process on a background thread; the file on disk is never read or
 * written here. The config is found from the file's location, exactly as the command line and the Maven plugin do.
 */
public final class JcfFormattingService extends AsyncDocumentFormattingService {

  private static final String NAME = "Java Code Formatter";

  @Override
  public @NotNull Set<FormattingService.Feature> getFeatures() {
    return Set.of();
  }

  @Override
  public boolean canFormat(@NotNull PsiFile file) {
    Path path = pathOf(file.getVirtualFile());

    return path != null && FormatRun.handles(path);
  }

  @Override
  protected @Nullable FormattingTask createFormattingTask(@NotNull AsyncFormattingRequest request) {
    Path path = pathOf(request.getContext().getVirtualFile());

    return path == null ? null : new Task(request, path);
  }

  @Override
  protected @NotNull String getNotificationGroupId() {
    return NAME;
  }

  @Override
  protected @NotNull String getName() {
    return NAME;
  }

  /** The path of a file on the local disk; {@code null} for anything else (a diff, a jar, an in-memory file). */
  private static @Nullable Path pathOf(@Nullable VirtualFile file) {
    if (file == null || !file.isInLocalFileSystem()) {
      return null;
    }

    try {
      return Path.of(file.getPath());
    } catch (InvalidPathException e) {
      return null;
    }
  }

  private static final class Task implements FormattingTask {

    private final AsyncFormattingRequest request;
    private final Path path;

    Task(AsyncFormattingRequest request, Path path) {
      this.request = request;
      this.path = path;
    }

    @Override
    public void run() {
      FormatRun.TextResult result = FormatRun.formatText(path, request.getDocumentText());

      if (result.status() == FormatRun.Status.FAILED) {
        request.onError(NAME, String.join("\n", result.errors()));
      } else {
        request.onTextReady(result.text());
      }
    }

    @Override
    public boolean cancel() {
      // formatting a single file takes milliseconds and cannot be interrupted; the result is dropped if cancelled
      return true;
    }
  }
}
