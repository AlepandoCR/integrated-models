package pandastic.studio.models.internal.config;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.StringJoiner;

/**
 * What one reload did: nothing, or everything.
 *
 * <h2>There is no third outcome</h2>
 * A reload re-reads every file, builds fresh value objects, validates them and only then swaps the
 * reference. Anything that goes wrong on the way leaves the previous snapshot live and untouched,
 * because a half-applied reload leaves the server running on a combination of values that no file on
 * disk describes — which is strictly worse than a refused one. A refusal is a state the operator can
 * see, reason about and fix; a half-applied reload is a state nobody can reconstruct afterwards.
 *
 * <p>{@link #snapshot()} is therefore always the snapshot that is <em>live</em>, whether or not this
 * reload is why. A caller holding one of these never has to work out which.
 */
public final class ConfigReloadResult {

    private final boolean applied;
    private final @NotNull ConfigSnapshot snapshot;
    private final @NotNull List<ConfigDiagnostic> diagnostics;
    private final boolean requiresSimulationRebuild;

    private ConfigReloadResult(
            boolean applied,
            @NotNull ConfigSnapshot snapshot,
            @NotNull List<ConfigDiagnostic> diagnostics,
            boolean requiresSimulationRebuild
    ) {
        this.applied = applied;
        this.snapshot = snapshot;
        this.diagnostics = List.copyOf(diagnostics);
        this.requiresSimulationRebuild = requiresSimulationRebuild;
    }

    /**
     * A reload that was applied.
     *
     * @param previous the snapshot that was live until now
     * @param current  the snapshot that is live from now on
     * @return the result
     */
    @Contract(value = "_, _ -> new", pure = true)
    static @NotNull ConfigReloadResult applied(
            @NotNull ConfigSnapshot previous,
            @NotNull ConfigSnapshot current
    ) {
        return new ConfigReloadResult(true, current, List.of(),
                current.requiresSimulationRebuild(previous));
    }

    /**
     * A reload that was refused, with the previous snapshot still live.
     *
     * @param live        the snapshot that stays live
     * @param diagnostics why the new values were refused; must not be empty
     * @return the result
     */
    @Contract(value = "_, _ -> new", pure = true)
    static @NotNull ConfigReloadResult refused(
            @NotNull ConfigSnapshot live,
            @NotNull List<ConfigDiagnostic> diagnostics
    ) {
        if (diagnostics.isEmpty()) {
            throw new IllegalArgumentException("A refused reload has to say why");
        }
        return new ConfigReloadResult(false, live, diagnostics, false);
    }

    /** Whether the files on disk are now the values in effect. */
    public boolean applied() {
        return this.applied;
    }

    /**
     * The snapshot that is live now: the freshly loaded one when this reload was applied, and the
     * untouched previous one when it was refused.
     *
     * @return the live snapshot
     */
    public @NotNull ConfigSnapshot snapshot() {
        return this.snapshot;
    }

    /**
     * Why the reload was refused, each entry naming the file and the key at fault.
     *
     * @return the diagnostics, empty when the reload was applied
     */
    public @NotNull List<ConfigDiagnostic> diagnostics() {
        return this.diagnostics;
    }

    /**
     * Whether every live {@code PhysicsState} is now invalid and has to be rebuilt.
     *
     * <p>True exactly when an applied reload changed the substep rate: a state is built against a step
     * size and its stability bound is checked at construction, so a state built at one rate carries a
     * proof about that rate and nothing else.
     *
     * <p>This is a <em>report</em>, not a command, and nothing here rebuilds anything. The states are
     * stepped by the simulation loop, so they have to be rebuilt on the loop's own thread; doing it
     * from whichever pool thread finished the reload would race the loop. The owner of the simulations
     * notices on its own schedule by comparing {@link ConfigSnapshot#revision()} — or the substep rate
     * itself — against what it last built against. See
     * {@link ConfigSnapshot#requiresSimulationRebuild(ConfigSnapshot)}.
     *
     * @return {@code true} when simulation state is stale
     */
    public boolean requiresSimulationRebuild() {
        return this.requiresSimulationRebuild;
    }

    /**
     * One line fit to log or to hand to whoever typed the reload.
     *
     * <p>A refusal says so first and names every file and key afterwards, because the operator's next
     * action is editing a file and the list is the only thing that tells them which.
     *
     * @return the summary line
     */
    public @NotNull String summary() {
        if (this.applied) {
            return "Configuration reload applied at revision " + this.snapshot.revision()
                    + (this.requiresSimulationRebuild
                    ? "; the substep rate changed, so every simulation state has to be rebuilt."
                    : ".");
        }
        final StringJoiner joined = new StringJoiner("; ");
        for (final ConfigDiagnostic diagnostic : this.diagnostics) {
            joined.add(diagnostic.format());
        }
        return "Configuration reload refused, keeping revision " + this.snapshot.revision()
                + ": " + joined;
    }

    @Override
    public @NotNull String toString() {
        return summary();
    }
}
