package pandastic.studio.models.internal.rig;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One thing the rig loader has to say about a file it read.
 *
 * <p>The loader neither logs nor throws per problem: it accumulates these and hands them back with the
 * result. That split exists for two reasons. A file is authored by a human, so one bad bone must not
 * hide the next nine — which means the loader has to keep going past a problem, and a thrown exception
 * cannot. And a loader that logs is a loader that needs a plugin, a logger and a server to be tested
 * at all, whereas one that returns its complaints as data can be tested as a function.
 *
 * <p>Deciding what to do with these is the caller's job: a startup load probably logs every one, a
 * reload command probably shows them to the operator who typed it, and a test asserts on them.
 *
 * @param severity whether the rig survived this problem
 * @param source   the file the problem was found in, for a message that can be acted on
 * @param bone     the bone at fault, as the author spelled it, or {@code null} for a whole-file problem
 * @param message  what is wrong, and what the loader did about it
 */
public record RigDiagnostic(
        @NotNull Severity severity,
        @NotNull String source,
        @Nullable String bone,
        @NotNull String message
) {

    /** How badly a problem hurt. */
    public enum Severity {

        /**
         * The rig loaded, with something clamped, dropped or defaulted. The author should still hear
         * about it: a value that was silently ignored is a value the author believes is in effect.
         */
        WARNING,

        /**
         * The rig did not load. Reserved for structural damage that leaves nothing coherent to build —
         * no bones, a cycle, a parent that does not exist, no model to point at.
         */
        ERROR
    }

    public RigDiagnostic {
        if (source.isBlank()) {
            throw new IllegalArgumentException("A diagnostic has to name the file it came from");
        }
        if (message.isBlank()) {
            throw new IllegalArgumentException("A diagnostic has to say something");
        }
    }

    /** A problem the rig survived. */
    @Contract(value = "_, _, _ -> new", pure = true)
    public static @NotNull RigDiagnostic warning(
            @NotNull String source,
            @Nullable String bone,
            @NotNull String message
    ) {
        return new RigDiagnostic(Severity.WARNING, source, bone, message);
    }

    /** A problem that cost the rig. */
    @Contract(value = "_, _, _ -> new", pure = true)
    public static @NotNull RigDiagnostic error(
            @NotNull String source,
            @Nullable String bone,
            @NotNull String message
    ) {
        return new RigDiagnostic(Severity.ERROR, source, bone, message);
    }

    /** Whether this one is why the rig failed to load. */
    public boolean isError() {
        return this.severity == Severity.ERROR;
    }

    /** One line naming the severity, the file and the bone, ready to log or print. */
    public @NotNull String format() {
        final String where = this.bone == null ? ": " : ": bone '" + this.bone + "': ";
        return "[" + this.severity + "] " + this.source + where + this.message;
    }

    @Override
    public @NotNull String toString() {
        return format();
    }
}
