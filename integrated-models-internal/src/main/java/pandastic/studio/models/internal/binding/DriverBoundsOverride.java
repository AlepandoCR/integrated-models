package pandastic.studio.models.internal.binding;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.binding.BindingOptions;
import pandastic.studio.models.api.physics.DriverBounds;
import pandastic.studio.models.internal.sim.DriverSampler;
import pandastic.studio.models.internal.sim.SimulationSettings;
import pandastic.studio.models.internal.sim.SimulationSettingsSource;

import java.util.Objects;

/**
 * The live configuration with one binding's own teleport filter substituted into it.
 *
 * <p>This is how a per-binding {@link DriverBounds} reaches the one thing that reads it. A
 * {@link DriverSampler} gets its thresholds from a {@link SimulationSettingsSource}, and
 * {@link SimulationSettings} already documents the shape of the answer: a model that needs its own
 * value is given <em>its own source</em>, not a mutable field on a snapshot the whole server shares.
 * A per-binding source is read by exactly one sampler, so the read-once contract survives intact.
 *
 * <h2>Stating nothing costs nothing</h2>
 * {@link #sourceFor} hands back the server-wide source <strong>unchanged</strong> when the binding
 * overrode nothing, which is the overwhelming majority of them. So the default path is wired exactly as
 * it was before per-binding bounds existed — same object, same single volatile read — and there is no
 * wrapper to pay for on a server where nothing uses the feature.
 *
 * <p>It is also what makes the defaulting follow a reload. A binding that stated nothing is not given
 * the configured value; it is given the configuration, so raising {@code maxStepBlocks} in
 * {@code simulation.yml} reaches it on the next sample like it always did.
 *
 * <h2>Why the derived snapshot is memoised, and against identity</h2>
 * Two separate contracts force it, and they happen to want the same thing.
 *
 * <p>The first is cost. {@link #current()} runs on a region thread once per sample per bound model, and
 * building a fresh {@link SimulationSettings} there would put an allocation on the one path in this
 * layer that is explicitly allocation-free — the sampler reuses its buffer and its differencer for
 * precisely that reason. Memoised, a steady-state call is one volatile read on the delegate, one
 * identity comparison and a return.
 *
 * <p>The second is the identity hazard that {@code SimulationSettingsSource} spells out: a source that
 * allocates per call makes {@code ModelSimulation}'s solver-identity guard fire on every step, which
 * zeroes the physics and freezes the model at its bind pose with nothing thrown and nothing logged.
 * This source is only read by the sampler today, so it is not on that path — but a source with the
 * wrong allocation behaviour is a trap for whoever wires it somewhere else later, and the fix is the
 * same code either way. Note the derived value also carries the delegate's <em>own</em> solver
 * instance, not a copy of one, so the guard could not fire on it even before the memo.
 *
 * <p>The cache key is the delegate's snapshot <em>by identity</em>, because that is exactly what a
 * reload changes: the configuration layer publishes by swapping one immutable aggregate, so "the same
 * instance as last time" is a precise statement that nothing has been reloaded.
 */
public final class DriverBoundsOverride implements SimulationSettingsSource {

    private final @NotNull SimulationSettingsSource serverWide;
    private final @NotNull BindingOptions options;

    /**
     * The last derivation, or {@code null} before the first call.
     *
     * <p>Volatile rather than plain for a hand-off rather than for a race: only the sampler reads this,
     * and a model has one sampler, so there are never two readers at once — but on a regionised server
     * the entity can move between regions and the next execution can land on a different thread, which
     * needs the write to be visible to it. Two threads racing the miss branch is harmless anyway: the
     * two derivations are equal and both carry the delegate's own solver instance.
     */
    private volatile @Nullable Derived cached;

    private DriverBoundsOverride(
            @NotNull SimulationSettingsSource serverWide,
            @NotNull BindingOptions options
    ) {
        this.serverWide = serverWide;
        this.options = options;
    }

    /**
     * The settings source one binding's sampler should read.
     *
     * @param serverWide the configuration everything else reads
     * @param options    what the binding stated about itself
     * @return {@code serverWide} itself when no bounds were stated, or a source that substitutes them
     */
    public static @NotNull SimulationSettingsSource sourceFor(
            @NotNull SimulationSettingsSource serverWide,
            @NotNull BindingOptions options
    ) {
        Objects.requireNonNull(serverWide, "serverWide");
        Objects.requireNonNull(options, "options");
        if (options.driverBounds().isEmpty()) {
            return serverWide;
        }
        return new DriverBoundsOverride(serverWide, options);
    }

    @Override
    public @NotNull SimulationSettings current() {
        // One read of the delegate, as the contract requires: a second one could straddle a reload and
        // derive a snapshot from two generations.
        final SimulationSettings live = this.serverWide.current();
        final DriverBounds resolved = this.options.resolveDriverBounds(live.driverBounds());
        if (resolved == live.driverBounds()) {
            // Nothing to substitute. Unreachable through sourceFor unless a caller overrode with the
            // very instance the configuration is already using, which is a no-op worth answering for
            // free rather than deriving a copy of.
            return live;
        }

        final Derived memo = this.cached;
        if (memo != null && memo.serverWide() == live) {
            return memo.derived();
        }

        final SimulationSettings derived = new SimulationSettings(
                live.solver(), live.simulationHz(), live.cadence(), resolved,
                live.errorLogIntervalNanos());
        this.cached = new Derived(live, derived);
        return derived;
    }

    /** What the binding stated about itself. */
    public @NotNull BindingOptions options() {
        return this.options;
    }

    @Override
    public @NotNull String toString() {
        return "DriverBoundsOverride" + this.options;
    }

    /**
     * One derivation and the snapshot it came from, so both are published by a single reference store.
     *
     * <p>Two fields would let a reader see a new source alongside the previous derivation and hand out
     * the bounds of the configuration before last.
     */
    private record Derived(
            @NotNull SimulationSettings serverWide,
            @NotNull SimulationSettings derived
    ) {
    }
}
