package pandastic.studio.models.internal.config;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One reason a configuration reload was refused.
 *
 * <h2>Why there is no severity here</h2>
 * {@link pandastic.studio.models.internal.rig.RigDiagnostic} has one, because a rig file clamps and
 * keeps loading: a rig author's mistake costs them one bone, and refusing the whole file over it would
 * cost them the model. A configuration file is different in kind. Its values are read by an
 * asynchronous loop tens of times a second, every one of them is a bound something else is validated
 * against, and a reload that applies all but one file leaves the server running on a combination
 * no file on disk describes. So there is exactly one severity, it is fatal to the reload, and the
 * previous snapshot stays live — see {@link ConfigService#reload()}.
 *
 * @param file    the file the problem is in, so the message says where to go and edit
 * @param key     the key at fault, or {@code null} for a problem with the file as a whole
 * @param message what is wrong, phrased so that it reads after the key name
 */
public record ConfigDiagnostic(
        @NotNull ConfigFile file,
        @Nullable String key,
        @NotNull String message
) {

    public ConfigDiagnostic {
        if (message.isBlank()) {
            throw new IllegalArgumentException("A diagnostic has to say something");
        }
        if (key != null && key.isBlank()) {
            throw new IllegalArgumentException("A diagnostic naming a key has to name a real one");
        }
    }

    /**
     * A problem with one key.
     *
     * @param file    the file the key lives in
     * @param key     the key at fault
     * @param message what is wrong with it
     * @return the diagnostic
     */
    @Contract(value = "_, _, _ -> new", pure = true)
    public static @NotNull ConfigDiagnostic of(
            @NotNull ConfigFile file,
            @NotNull String key,
            @NotNull String message
    ) {
        return new ConfigDiagnostic(file, key, message);
    }

    /**
     * A problem with a whole file: it could not be read, or its syntax is broken.
     *
     * @param file    the file at fault
     * @param message what is wrong with it
     * @return the diagnostic
     */
    @Contract(value = "_, _ -> new", pure = true)
    public static @NotNull ConfigDiagnostic ofFile(@NotNull ConfigFile file, @NotNull String message) {
        return new ConfigDiagnostic(file, null, message);
    }

    /** One line naming the file and the key, ready to log or show to whoever typed the reload. */
    public @NotNull String format() {
        return this.key == null
                ? this.file.fileName() + ": " + this.message
                : this.file.fileName() + ": " + this.key + ": " + this.message;
    }

    @Override
    public @NotNull String toString() {
        return format();
    }
}
