package pandastic.studio.models.internal.rig;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.rig.Rig;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

/**
 * What came out of reading one rig file: the rig, or nothing, and everything the loader noticed on
 * the way.
 *
 * <p>Also what {@link RigFactory} answers with, for the same two reasons: a derivation can fail for
 * reasons that are the model's rather than the caller's, and a caller logging the outcome should not have
 * to care whether the rig came from a file or from the model itself.
 *
 * <p>The diagnostics are part of the result rather than a side effect, because they outnumber the
 * outcome in usefulness. A rig that loaded is only interesting until it misbehaves, at which point the
 * three values that got clamped are the whole explanation — and a loader that had logged them to a
 * file nobody kept would have thrown that away.
 */
public final class RigLoadResult {

    private final @Nullable Rig rig;
    private final @NotNull List<RigDiagnostic> diagnostics;

    private RigLoadResult(@Nullable Rig rig, @NotNull List<RigDiagnostic> diagnostics) {
        this.rig = rig;
        this.diagnostics = List.copyOf(diagnostics);
    }

    /** A rig that loaded, warnings and all. */
    @Contract(value = "_, _ -> new", pure = true)
    static @NotNull RigLoadResult success(
            @NotNull Rig rig,
            @NotNull List<RigDiagnostic> diagnostics
    ) {
        return new RigLoadResult(rig, diagnostics);
    }

    /** A rig that did not load. Its diagnostics carry at least one {@link RigDiagnostic.Severity#ERROR}. */
    @Contract(value = "_ -> new", pure = true)
    static @NotNull RigLoadResult failure(@NotNull List<RigDiagnostic> diagnostics) {
        return new RigLoadResult(null, diagnostics);
    }

    /** Whether a rig came out of this load. */
    public boolean isSuccess() {
        return this.rig != null;
    }

    /** The loaded rig, or empty if the file was too broken to build one. */
    public @NotNull Optional<Rig> rig() {
        return Optional.ofNullable(this.rig);
    }

    /**
     * The loaded rig, for a caller that treats a broken rig file as fatal.
     *
     * @throws IllegalStateException if the rig failed to load, with every error in the message
     */
    public @NotNull Rig rigOrThrow() {
        if (this.rig == null) {
            final StringJoiner joined = new StringJoiner("; ");
            for (final RigDiagnostic diagnostic : diagnostics(RigDiagnostic.Severity.ERROR)) {
                joined.add(diagnostic.format());
            }
            throw new IllegalStateException("The rig did not load: " + joined);
        }
        return this.rig;
    }

    /** Everything the loader noticed, in the order it noticed it. */
    public @NotNull List<RigDiagnostic> diagnostics() {
        return this.diagnostics;
    }

    /** Everything the loader noticed at exactly {@code severity}. */
    public @NotNull List<RigDiagnostic> diagnostics(@NotNull RigDiagnostic.Severity severity) {
        final List<RigDiagnostic> filtered = new ArrayList<>();
        for (final RigDiagnostic diagnostic : this.diagnostics) {
            if (diagnostic.severity() == severity) {
                filtered.add(diagnostic);
            }
        }
        return List.copyOf(filtered);
    }

    /** Whether the loader had to clamp, drop or default anything. */
    public boolean hasWarnings() {
        return !diagnostics(RigDiagnostic.Severity.WARNING).isEmpty();
    }

    /**
     * The bone names this rig expects to find in the model, lower-cased and root-first.
     *
     * <p>Handed out so a caller can check a rig against a real model <em>before</em> anything spawns.
     * The loader itself deliberately never tries: it has no model to ask, and a rig file is readable
     * long before the renderer has loaded its assets.
     *
     * @return an unmodifiable set, empty if the rig failed to load
     */
    public @NotNull Set<String> expectedBoneNames() {
        return this.rig == null ? Set.of() : RigLoader.expectedBoneNames(this.rig);
    }

    @Override
    public @NotNull String toString() {
        return "RigLoadResult["
                + (this.rig == null ? "failed" : this.rig.toString())
                + ", " + this.diagnostics.size() + " diagnostics]";
    }
}
