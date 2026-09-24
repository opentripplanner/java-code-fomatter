package org.tiger.java.code.formater;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Formatting options, loadable from a {@code .properties} file. Currently just the column at which
 * {@link LineWrapper} wraps a line.
 */
public record FormatterConfig(int wrapAtColumn) {

    public static final String FILE_NAME = ".jcf.properties";
    private static final int DEFAULT_WRAP_AT_COLUMN = 120;

    public static final FormatterConfig DEFAULT = new FormatterConfig(DEFAULT_WRAP_AT_COLUMN);

    public FormatterConfig {
        if (wrapAtColumn < 1) {
            throw new IllegalArgumentException("wrapAtColumn must be at least 1, was " + wrapAtColumn);
        }
    }

    /** Reads {@code wrapAtColumn}, falling back to {@link #DEFAULT} for anything unset. */
    public static FormatterConfig from(Properties properties) {
        String value = properties.getProperty("wrapAtColumn");
        if (value == null) {
            return DEFAULT;
        }
        try {
            return new FormatterConfig(Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("wrapAtColumn must be an integer, was '" + value + "'", e);
        }
    }

    public static FormatterConfig load(Path propertiesFile) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(propertiesFile)) {
            properties.load(in);
        }
        return from(properties);
    }

    /**
     * {@link #load(Path)}s {@value #FILE_NAME} from {@code directory} if present, else
     * {@link #DEFAULT}.
     */
    public static FormatterConfig loadFromDirectory(Path directory) throws IOException {
        Path file = directory.resolve(FILE_NAME);
        return Files.isRegularFile(file) ? load(file) : DEFAULT;
    }
}
