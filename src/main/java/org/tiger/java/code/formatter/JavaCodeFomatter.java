package org.tiger.java.code.formatter;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

public class JavaCodeFomatter {

    private JavaCodeFomatter() {
    }

    public static void main(String[] args) {
        boolean write = false;
        List<String> files = new ArrayList<>();
        for (String arg : args) {
            switch (arg) {
                case "-w", "--write" -> write = true;
                case "-h", "--help" -> {
                    printUsage(System.out);
                    return;
                }
                default -> {
                    if (arg.startsWith("-")) {
                        System.err.println("Unknown option: " + arg);
                        printUsage(System.err);
                        System.exit(2);
                    }
                    files.add(arg);
                }
            }
        }
        if (files.isEmpty()) {
            printUsage(System.err);
            System.exit(2);
        }

        FormatterConfig config;
        try {
            config = FormatterConfig.loadFromDirectory(Path.of(""));
        } catch (IOException | IllegalArgumentException e) {
            System.err.println(FormatterConfig.FILE_NAME + ": " + e.getMessage());
            System.exit(2);
            return;
        }

        boolean ok = true;
        for (String fileArg : files) {
            ok &= processFile(Path.of(fileArg), write, config);
        }
        System.exit(ok ? 0 : 1);
    }

    private static boolean processFile(Path path, boolean write, FormatterConfig config) {
        String source;
        try {
            source = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println(path + ": " + e.getMessage());
            return false;
        }

        SourceFormatter.FormatResult result;
        try {
            result = SourceFormatter.format(source, config);
        } catch (IOException e) {
            System.err.println(path + ": " + e.getMessage());
            return false;
        }

        return switch (result) {
            case SourceFormatter.ValidationFailed(var diagnostics) -> {
                for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
                    System.err.println(path + ":" + diagnostic.getLineNumber() + ": " + diagnostic.getMessage(null));
                }
                yield false;
            }
            case SourceFormatter.InternalError(var message) -> {
                System.err.println(path + ": " + message);
                yield false;
            }
            case SourceFormatter.Formatted(var text, var unwrappableLines) -> {
                for (int line : unwrappableLines) {
                    System.err.println(path + ":" + line + ": line exceeds " + config.wrapAtColumn()
                        + " columns with no safe break point; left unchanged");
                }
                if (write) {
                    try {
                        Files.writeString(path, text, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        System.err.println(path + ": " + e.getMessage());
                        yield false;
                    }
                } else {
                    System.out.print(text);
                }
                yield unwrappableLines.isEmpty();
            }
        };
    }

    private static void printUsage(PrintStream out) {
        out.println("Usage: java -m org.tiger.java.code.formatter [-w|--write] <file.java> [<file.java>...]");
        out.println("Reads " + FormatterConfig.FILE_NAME + " from the current directory if present.");
    }
}
