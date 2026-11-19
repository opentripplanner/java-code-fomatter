package org.opentripplanner.java.code.formatter;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * A set of "already formatted" fingerprints, kept in {@code <root>/.jcf/cache/<code>.set}. A fingerprint is a hash of a
 * file's content together with the config it was formatted under, so a file is recorded only when formatting it again
 * changes nothing (a fixed point). A later run skips every file whose content and config hash is in the set: no parse,
 * no formatting.
 * <p>
 * The set file is named after a hash of the formatter's own code, so any change to the formatter starts a fresh cache
 * and the old file is deleted on the next save. The set is written once, at the end of a run, to a temporary file that
 * is then renamed into place. The cache folder ignores itself ({@code .gitignore} containing {@code *}), so it needs no
 * entry in anyone's {@code .gitignore}.
 */
final class FormatCache {

  record Key(long high, long low) {
  }

  private final Path directory;
  private final Path file;
  /** Fingerprint to the lines (1-based) that had no safe break point when it was formatted; usually empty. */
  private final Map<Key, int[]> known = new ConcurrentHashMap<>();
  private final Map<Key, int[]> added = new ConcurrentHashMap<>();

  private FormatCache(Path directory, String codeFingerprint) {
    this.directory = directory;
    this.file = directory.resolve(codeFingerprint + ".set");
  }

  /** Opens (reading, if present) the cache under {@code root/.jcf/cache}; nothing is created yet. */
  static FormatCache open(Path root) {
    FormatCache cache = new FormatCache(root.resolve(FormatterConfig.DIR_NAME).resolve("cache"), codeFingerprint());
    cache.load();
    return cache;
  }

  /** The fingerprint of {@code content} formatted under {@code config}. */
  static Key key(byte[] content, FormatterConfig config) {
    MessageDigest digest = sha256();
    digest.update(ByteBuffer.allocate(8).putInt(config.wrapAtColumn()).putInt(config.indent()).array());
    digest.update(String.join(",", config.commentRules()).getBytes(StandardCharsets.UTF_8));
    FormatterConfig.Documents documents = config.documents();
    digest.update(ByteBuffer.allocate(9).putInt(documents.jsonWrapAtColumn()).putInt(documents.markdownWrapAtColumn())
      .put((byte) (documents.preserveProse() ? 1 : 0)).array());
    digest.update(String.join(",", documents.formats()).getBytes(StandardCharsets.UTF_8));
    digest.update((byte) (config.chainedBlocksOnNewLine() ? 1 : 0));
    digest.update((byte) (config.removeUnusedImports() ? 1 : 0));
    digest.update(content);
    ByteBuffer hash = ByteBuffer.wrap(digest.digest());
    return new Key(hash.getLong(), hash.getLong());
  }

  /** The unwrappable lines recorded for {@code key}, or {@code null} if it is not cached. */
  int[] lookup(Key key) {
    return known.get(key);
  }

  /** Records {@code key} as a fixed point, with the lines that still had no safe break point. */
  void add(Key key, int[] unwrappableLines) {
    if (!known.containsKey(key)) {
      added.put(key, unwrappableLines);
    }
  }

  boolean hasNewEntries() {
    return !added.isEmpty();
  }

  private void load() {
    if (!Files.isRegularFile(file)) {
      return;
    }

    try {
      ByteBuffer bytes = ByteBuffer.wrap(Files.readAllBytes(file));

      while (bytes.remaining() >= 20) {
        Key key = new Key(bytes.getLong(), bytes.getLong());
        int[] lines = new int[bytes.getInt()];

        for (int i = 0; i < lines.length; i++) {
          lines[i] = bytes.getInt();
        }

        known.put(key, lines);
      }
    } catch (IOException e) {
      // an unreadable cache is just an empty one
      known.clear();
    }
  }

  /** Writes the merged set and removes sets left behind by other versions of the formatter. */
  void save() throws IOException {
    if (added.isEmpty()) {
      return;
    }

    Files.createDirectories(directory);
    Path ignore = directory.resolve(".gitignore");

    if (!Files.exists(ignore)) {
      Files.writeString(ignore, "*\n", StandardCharsets.UTF_8);
    }

    Map<Key, int[]> all = new java.util.HashMap<>(known);
    all.putAll(added);
    int size = 0;

    for (int[] lines : all.values()) {
      size += 20 + lines.length * 4;
    }

    ByteBuffer out = ByteBuffer.allocate(size);
    all.forEach((key, lines) -> {
      out.putLong(key.high()).putLong(key.low()).putInt(lines.length);

      for (int line : lines) {
        out.putInt(line);
      }
    });
    Path temp = Files.createTempFile(directory, "write", ".tmp");
    Files.write(temp, out.array());
    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

    try (DirectoryStream<Path> stale = Files.newDirectoryStream(directory, "*.set")) {
      for (Path other : stale) {
        if (!other.equals(file)) {
          Files.deleteIfExists(other);
        }
      }
    }

    known.putAll(added);
    added.clear();
  }

  /** A hash of the formatter's own code (its jar, or its class files when run from a directory). */
  private static String codeFingerprint() {
    MessageDigest digest = sha256();

    try {
      Path location = Path.of(FormatCache.class.getProtectionDomain().getCodeSource().getLocation().toURI());

      if (Files.isDirectory(location)) {
        try (Stream<Path> walk = Files.walk(location)) {
          for (Path path : (Iterable<Path>) walk.filter(Files::isRegularFile).sorted()::iterator) {
            digest.update(location.relativize(path).toString().getBytes(StandardCharsets.UTF_8));
            digest.update(Files.readAllBytes(path));
          }
        }
      } else {
        digest.update(Files.readAllBytes(location));
      }
    } catch (IOException | URISyntaxException | RuntimeException e) {
      // never reuse a cache
      digest.update(("unknown-" + System.nanoTime()).getBytes(StandardCharsets.UTF_8));
    }

    StringBuilder hex = new StringBuilder();
    byte[] hash = digest.digest();

    for (int i = 0; i < 8; i++) {
      hex.append(String.format("%02x", hash[i]));
    }

    return hex.toString();
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
