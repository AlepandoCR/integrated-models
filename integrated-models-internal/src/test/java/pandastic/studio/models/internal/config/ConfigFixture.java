package pandastic.studio.models.internal.config;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes the partial configuration files the tests in this package read back.
 *
 * <h2>Why the fixtures are hand-written YAML</h2>
 * Because that is the only way a value ever changes in production. A generated configuration class
 * exposes accessors and no setters by design — the file is the input and the object is the output — so
 * there is no programmatic path from "I want {@code stiffnessToRate} to be 200" to a file saying so.
 * Writing the two lines an operator would write is both the honest fixture and the only one available.
 *
 * <h2>Why they are partial</h2>
 * A generated configuration keeps its declared default for every key the file does not contain, so a
 * fixture only has to name the keys under test. That is not a shortcut: it is the behaviour the reload
 * path depends on, since a fresh instance per reload is what makes a key an operator deleted go back to
 * its default instead of sticking at whatever the previous reload read.
 */
final class ConfigFixture {

    private ConfigFixture() {
    }

    /**
     * Writes {@code lines} as the entire contents of one configuration file.
     *
     * @param directory the directory the service was pointed at
     * @param file      which file to write
     * @param lines     the YAML lines, each a complete {@code key: value} entry
     */
    static void write(@NotNull Path directory, @NotNull ConfigFile file, String @NotNull ... lines) {
        final StringBuilder content = new StringBuilder();
        for (final String line : lines) {
            content.append(line).append(System.lineSeparator());
        }
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(file.fileName()), content.toString());
        } catch (final IOException exception) {
            throw new UncheckedIOException("Could not write the fixture " + file.fileName(), exception);
        }
    }

    /**
     * Replaces one configuration file with text that is not YAML at all.
     *
     * @param directory the directory the service was pointed at
     * @param file      which file to corrupt
     */
    static void corrupt(@NotNull Path directory, @NotNull ConfigFile file) {
        // A tab after a mapping key is a hard syntax error in YAML rather than a type mismatch, which
        // is what makes it a whole-file failure instead of a per-key one.
        write(directory, file, "loopRateHz: 60", "\tnot: yaml", "  also: [unclosed");
    }

    /**
     * The contents of a written configuration file.
     *
     * @param directory the directory the service was pointed at
     * @param file      which file to read
     * @return the file's text
     */
    static @NotNull String read(@NotNull Path directory, @NotNull ConfigFile file) {
        try {
            return Files.readString(directory.resolve(file.fileName()));
        } catch (final IOException exception) {
            throw new UncheckedIOException("Could not read " + file.fileName(), exception);
        }
    }
}
