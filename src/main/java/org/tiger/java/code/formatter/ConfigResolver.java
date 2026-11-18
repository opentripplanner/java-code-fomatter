package org.tiger.java.code.formatter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Finds the {@link FormatterConfig} that applies to a file: the {@code .jcf/config.properties}
 * files of every directory from the outermost repository root down to the file's own directory,
 * layered so that a nearer file overrides only the keys it sets and inherits the rest. A module
 * (or a nested repository, or a git submodule) can therefore override the root's settings.
 *
 * <p>The <em>outermost repository root</em> is the topmost ancestor directory holding a
 * {@code .git} entry (a directory, or the file a submodule/worktree has). Outside any repository
 * only the file's own directory is consulted. Nothing is ever created here: configs are read only
 * where they already exist, so no {@code .jcf} folder appears in source directories.
 *
 * <p>Lookups are memoized per directory, so formatting thousands of files costs one walk per
 * directory.
 */
public final class ConfigResolver {

    private final Map<Path, Path> roots = new HashMap<>();
    private final Map<Path, Properties> layered = new HashMap<>();
    private final Map<Path, FormatterConfig> configs = new HashMap<>();

    /** The effective config for {@code file}. */
    public synchronized FormatterConfig configFor(Path file) throws IOException {
        Path dir = file.toAbsolutePath().normalize().getParent();
        FormatterConfig cached = configs.get(dir);
        if (cached != null) {
            return cached;
        }
        FormatterConfig config;
        try {
            config = FormatterConfig.from(propertiesFor(dir));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(FormatterConfig.DIR_NAME + "/" + FormatterConfig.FILE_NAME + " for "
                + dir + ": " + e.getMessage(), e);
        }
        configs.put(dir, config);
        return config;
    }

    /**
     * The outermost ancestor-or-self of {@code dir} that holds a {@code .git} entry, or {@code null}
     * if none does (not under version control).
     */
    public synchronized Path rootOf(Path dir) {
        Path absolute = dir.toAbsolutePath().normalize();
        return rootOfNormalized(absolute);
    }

    private Path rootOfNormalized(Path dir) {
        if (roots.containsKey(dir)) {
            return roots.get(dir);
        }
        Path parent = dir.getParent();
        Path root = parent == null ? null : rootOfNormalized(parent);
        if (root == null && Files.exists(dir.resolve(".git"))) {
            root = dir;
        }
        roots.put(dir, root);
        return root;
    }

    private Properties propertiesFor(Path dir) throws IOException {
        Properties cached = layered.get(dir);
        if (cached != null) {
            return cached;
        }
        Path root = rootOfNormalized(dir);
        Properties result = new Properties();
        if (root != null && !dir.equals(root)) {
            result.putAll(propertiesFor(dir.getParent()));
        }
        Path file = dir.resolve(FormatterConfig.DIR_NAME).resolve(FormatterConfig.FILE_NAME);
        if (Files.isRegularFile(file)) {
            Properties own = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                own.load(in);
            }
            result.putAll(own);
        }
        layered.put(dir, result);
        return result;
    }
}
