package pandastic.studio.models.internal.config;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Accumulator the mapper validates loaded values through.
 *
 * <h2>It accumulates instead of throwing</h2>
 * A file is written by a human, and a human who mistyped three values would rather hear about three
 * than find out about them one restart at a time. Every check therefore records its complaint and
 * returns, so one pass over every file produces the complete list — which is the list the operator
 * needs, because a refused reload tells them nothing about the second mistake.
 *
 * <h2>It returns the value it checked</h2>
 * So a check reads as part of the expression that uses the value
 * ({@code checks.above(PHYSICS, "minMass", config.minMass(), 0F, 1_000F)}) rather than as a statement
 * before it. The returned value is deliberately <em>not</em> clamped: a rig file clamps because the rig
 * still has to load, while a configuration file is refused as a whole, so clamping here would produce a
 * value that is then thrown away. When a check fails the caller must not use the result for anything
 * except handing it back — {@link ConfigMapper} drops out before it constructs anything.
 *
 * <p>Not thread-safe, and it does not need to be: one instance belongs to one validation pass.
 */
final class ConfigChecks {

    private final @NotNull List<ConfigDiagnostic> problems = new ArrayList<>();

    /**
     * Records a problem with one key.
     *
     * @param file    the file the key lives in
     * @param key     the key at fault
     * @param message what is wrong with it
     */
    void reject(@NotNull ConfigFile file, @NotNull String key, @NotNull String message) {
        this.problems.add(ConfigDiagnostic.of(file, key, message));
    }

    /**
     * Records a problem with a whole file.
     *
     * @param file    the file at fault
     * @param message what is wrong with it
     */
    void rejectFile(@NotNull ConfigFile file, @NotNull String message) {
        this.problems.add(ConfigDiagnostic.ofFile(file, message));
    }

    /**
     * Requires a value within an inclusive range.
     *
     * <p>NaN is tested for separately because every comparison against it is false, so the plain range
     * test would wave it straight through — and a NaN that reaches a rotation deletes the model on the
     * client rather than looking wrong.
     *
     * @param file  the file the key lives in
     * @param key   the key being checked
     * @param value the loaded value
     * @param min   smallest accepted value, inclusive
     * @param max   largest accepted value, inclusive
     * @return {@code value}, unchanged
     */
    float inRange(@NotNull ConfigFile file, @NotNull String key, float value, float min, float max) {
        if (Float.isNaN(value)) {
            reject(file, key, "is not a number");
            return value;
        }
        if (value < min || value > max) {
            reject(file, key, "must be between " + min + " and " + max + " but is " + value);
        }
        return value;
    }

    /**
     * Requires a value strictly above a floor and within a ceiling.
     *
     * <p>The strict floor is the shape most of the guards in this project need: a value that is divided
     * by, or square-rooted, or used as a denominator, is not merely small at zero — it is undefined.
     *
     * @param file          the file the key lives in
     * @param key           the key being checked
     * @param value         the loaded value
     * @param exclusiveMin  value the loaded one has to exceed
     * @param max           largest accepted value, inclusive
     * @return {@code value}, unchanged
     */
    float above(@NotNull ConfigFile file, @NotNull String key, float value, float exclusiveMin, float max) {
        if (Float.isNaN(value)) {
            reject(file, key, "is not a number");
            return value;
        }
        if (value <= exclusiveMin || value > max) {
            reject(file, key, "must be greater than " + exclusiveMin + " and at most " + max
                    + " but is " + value);
        }
        return value;
    }

    /**
     * Requires a double-precision value within an inclusive range.
     *
     * <p>The same check as the {@code float} form, and a separate overload rather than a widening of it
     * because the layer that reads these values is written in {@code double} throughout: distances are
     * compared squared, so a range is squared too and a {@code float} round trip through a squared
     * comparison loses more than the value's own last bits. A value checked in the precision it will be
     * used in is a value whose diagnostic prints the number the file says.
     *
     * @param file  the file the key lives in
     * @param key   the key being checked
     * @param value the loaded value
     * @param min   smallest accepted value, inclusive
     * @param max   largest accepted value, inclusive
     * @return {@code value}, unchanged
     */
    double inRange(@NotNull ConfigFile file, @NotNull String key, double value, double min, double max) {
        if (Double.isNaN(value)) {
            reject(file, key, "is not a number");
            return value;
        }
        if (value < min || value > max) {
            reject(file, key, "must be between " + min + " and " + max + " but is " + value);
        }
        return value;
    }

    /**
     * Requires a double-precision value strictly above a floor and within a ceiling.
     *
     * @param file         the file the key lives in
     * @param key          the key being checked
     * @param value        the loaded value
     * @param exclusiveMin value the loaded one has to exceed
     * @param max          largest accepted value, inclusive
     * @return {@code value}, unchanged
     */
    double above(
            @NotNull ConfigFile file, @NotNull String key, double value,
            double exclusiveMin, double max
    ) {
        if (Double.isNaN(value)) {
            reject(file, key, "is not a number");
            return value;
        }
        if (value <= exclusiveMin || value > max) {
            reject(file, key, "must be greater than " + exclusiveMin + " and at most " + max
                    + " but is " + value);
        }
        return value;
    }

    /**
     * Requires an integer value within an inclusive range.
     *
     * @param file  the file the key lives in
     * @param key   the key being checked
     * @param value the loaded value
     * @param min   smallest accepted value, inclusive
     * @param max   largest accepted value, inclusive
     * @return {@code value}, unchanged
     */
    int inRange(@NotNull ConfigFile file, @NotNull String key, int value, int min, int max) {
        if (value < min || value > max) {
            reject(file, key, "must be between " + min + " and " + max + " but is " + value);
        }
        return value;
    }

    /**
     * Requires one value not to exceed another, possibly in a different file.
     *
     * <p>Both sides are named, because the operator edited one of them and the fix might be in either.
     * Cross-file checks are the reason the files are published as a single snapshot: an invariant
     * spanning two files cannot be upheld by validating them one at a time.
     *
     * @param lowerFile  the file the smaller value lives in
     * @param lowerKey   the key of the smaller value
     * @param lower      the value that must not exceed the other
     * @param upperFile  the file the larger value lives in
     * @param upperKey   the key of the larger value
     * @param upper      the value that must not be exceeded
     */
    void atMost(
            @NotNull ConfigFile lowerFile, @NotNull String lowerKey, float lower,
            @NotNull ConfigFile upperFile, @NotNull String upperKey, float upper
    ) {
        if (Float.isNaN(lower) || Float.isNaN(upper) || lower <= upper) {
            return;
        }
        reject(lowerFile, lowerKey, "must not exceed " + describe(upperFile, upperKey) + ", which is "
                + upper + ", but is " + lower);
    }

    /**
     * Requires one value to stay strictly below another, possibly in a different file.
     *
     * @param lowerFile  the file the smaller value lives in
     * @param lowerKey   the key of the smaller value
     * @param lower      the value that must stay below the other
     * @param upperFile  the file the larger value lives in
     * @param upperKey   the key of the larger value
     * @param upper      the value that must be exceeded
     */
    void below(
            @NotNull ConfigFile lowerFile, @NotNull String lowerKey, float lower,
            @NotNull ConfigFile upperFile, @NotNull String upperKey, float upper
    ) {
        if (Float.isNaN(lower) || Float.isNaN(upper) || lower < upper) {
            return;
        }
        reject(lowerFile, lowerKey, "must stay below " + describe(upperFile, upperKey) + ", which is "
                + upper + ", but is " + lower);
    }

    /**
     * Requires a text value to say something.
     *
     * <p>{@code null} is tested for and is not paranoia: a generated configuration reads a text key
     * through {@code getString}, which answers {@code null} for a key that is present with an empty
     * value. {@code title:} on its own line is an edit an operator can make, and the complaint has to
     * name the key rather than arrive as a {@link NullPointerException} from somewhere downstream.
     *
     * <p>Returns the empty string for a rejected value rather than {@code null}, so the caller can hand
     * it straight back without a second null check — {@link ConfigMapper} drops out before it constructs
     * anything, so the substituted value is never used for anything.
     *
     * @param file  the file the key lives in
     * @param key   the key being checked
     * @param value the loaded value
     * @return {@code value}, or the empty string when it was rejected
     */
    @NotNull String text(@NotNull ConfigFile file, @NotNull String key, @Nullable String value) {
        if (value == null || value.isBlank()) {
            reject(file, key, "must not be blank: it is a label a player reads");
            return "";
        }
        return value;
    }

    /**
     * Requires a text value to be present, allowing it to be blank.
     *
     * <p>For the keys where emptying the key is how an operator turns a line off. Only {@code null} is
     * refusable, and for the reason {@link #text} documents.
     *
     * @param file  the file the key lives in
     * @param key   the key being checked
     * @param value the loaded value
     * @return {@code value}, or the empty string when it was {@code null}
     */
    @NotNull String optionalText(@NotNull ConfigFile file, @NotNull String key, @Nullable String value) {
        if (value == null) {
            reject(file, key, "must not be empty-with-no-value; use '' to omit it");
            return "";
        }
        return value;
    }

    /** Whether every check so far passed. */
    boolean isEmpty() {
        return this.problems.isEmpty();
    }

    /** Everything that failed, in the order it was checked. */
    @NotNull List<ConfigDiagnostic> toList() {
        return List.copyOf(this.problems);
    }

    /**
     * Names a key, qualifying it with its file only when that is not the file being complained about.
     *
     * @param file the file the referenced key lives in
     * @param key  the referenced key
     * @return the reference, as it should read inside a message
     */
    private static @NotNull String describe(@NotNull ConfigFile file, @NotNull String key) {
        return key + " in " + file.fileName();
    }
}
