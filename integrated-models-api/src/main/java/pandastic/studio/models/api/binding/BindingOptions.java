package pandastic.studio.models.api.binding;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.physics.DriverBounds;

import java.util.Objects;
import java.util.Optional;

/**
 * The things about one binding that the server-wide configuration cannot decide for it.
 *
 * <p>Everything in here is optional, and <em>absent</em> is the normal case: a binding that states
 * nothing behaves exactly as it did before this type existed, driven entirely by the operator's
 * configuration. What it buys is the ability to say "this one is different" for the cases where a single
 * server-wide number is provably the wrong shape of answer.
 *
 * <h2>The case that forced it</h2>
 * {@link DriverBounds} is a teleport filter: a per-tick position delta larger than its ceiling is
 * discarded, because feeding a teleport to the physics launches every bone in the rig at once. The
 * ceiling is configured server-wide, and it has to be low — a short player teleport is about a block and
 * a half — or the filter stops catching the thing it exists for.
 *
 * <p>But a projectile is a perfectly legal thing to bind a rig to, and an arrow at full draw covers
 * about three blocks in a tick. Against a player-sized ceiling every one of those ticks is classified as
 * a teleport, so the sampler publishes stillness forever: the model flies correctly, because the
 * renderer moves it, and its secondary motion never learns it is moving at all. No error, no log line,
 * no feathers streaming back.
 *
 * <p>Raising the server-wide value fixes the arrow and breaks everything else, because it is the same
 * number a player's teleport is measured against. A projectile and a player do not share a notion of
 * plausible speed, so the number belongs to the binding.
 *
 * <h2>Why an options object and not another {@code bind} overload</h2>
 * Driver bounds are the first per-binding property and will not be the last — a sample period, an
 * emission cadence and a gait are all server-wide today for no better reason than that nothing could
 * carry them per binding. Each one added as an overload squares the number of {@code bind} signatures
 * and forces every implementor of {@link ModelBinder} to grow a method; added here it is one more wither
 * and no existing call site changes.
 *
 * <p>Which is also why this is a class with a private constructor rather than a record. This type ships
 * in the published api jar, so its constructor is a compatibility promise, and a record's canonical
 * constructor cannot gain a component without breaking every caller that used it. Entering through
 * {@link #defaults()} and the withers means a new property is a purely additive change.
 *
 * <h2>Absent is not "the value the server has right now"</h2>
 * An absent override is stored as absent, never resolved to the configured value at bind time. The two
 * are different for as long as the server runs: a binding that stated nothing must follow a
 * configuration reload, and one that snapshotted the number at bind time would be frozen at whatever
 * was in force the moment it was created, differing from its neighbours for reasons nothing could
 * explain.
 *
 * <p>There is deliberately no third state. "Absent" means <em>the server's bounds</em> and the only
 * other thing expressible is a complete {@link DriverBounds}, both of whose ceilings its own constructor
 * has already checked are finite and positive. Nothing here can express "no filter", so a binding
 * cannot end up with an unfiltered driver however it was configured — not by a runtime check that could
 * be forgotten, but because the state does not exist.
 */
public final class BindingOptions {

    /**
     * The largest per-axis, per-tick translation the protocol can describe as movement at all, in
     * blocks: {@value}.
     *
     * <p><strong>A protocol fact, not a tunable.</strong> The relative entity-move packet encodes each
     * axis as a {@code short} in units of a 4096th of a block, so the largest delta it can carry is
     * {@code Short.MAX_VALUE / 4096} — a hair under eight blocks. Past that there is no move packet to
     * send: the server falls back to a position sync and the client snaps the entity to the new
     * coordinates instead of interpolating toward them.
     *
     * <p>That is what makes it the right ceiling on an override rather than merely a large round number.
     * A teleport filter exists to tell movement from teleportation, and at this value it agrees with the
     * client about where that line is: everything the client interpolates is movement, everything it
     * snaps is a teleport. Above it the filter would be calling a client-side snap "movement" — the
     * viewer sees the model jump while the physics is told it travelled, which is the bone wind-up the
     * server-wide bound exists to prevent, reintroduced one binding at a time.
     *
     * <p>Note the asymmetry with the configured server-wide value, which is deliberate and is this
     * project's standing rule: a file is written by a human and is sanity-checked for a misplaced
     * decimal point, whereas a value passed here was written in code by a programmer, so it is refused
     * outright rather than clamped.
     */
    public static final float RELATIVE_MOVE_LIMIT_BLOCKS = Short.MAX_VALUE / 4096F;

    /** The only instance with nothing stated, so the common case allocates nothing. */
    private static final @NotNull BindingOptions NOTHING_STATED = new BindingOptions(null);

    private final @Nullable DriverBounds driverBounds;

    private BindingOptions(@Nullable DriverBounds driverBounds) {
        this.driverBounds = driverBounds;
    }

    /**
     * Options that state nothing, so every property comes from the server's configuration.
     *
     * <p>What {@link ModelBinder#bind(pandastic.studio.models.api.rig.Rig, org.bukkit.entity.Entity)}
     * passes, and the starting point for every other combination. A shared instance: it holds no state
     * worth copying.
     */
    @Contract(pure = true)
    public static @NotNull BindingOptions defaults() {
        return NOTHING_STATED;
    }

    /**
     * Options whose only statement is a teleport filter of this binding's own.
     *
     * <p>The one-liner for the case this type was written for, so a caller binding a rig to a projectile
     * does not have to start from {@link #defaults()} to say the single thing it needs to.
     *
     * @param bounds the per-tick deltas this binding treats as real movement
     * @return the options
     * @throws IllegalArgumentException if either ceiling exceeds {@link #RELATIVE_MOVE_LIMIT_BLOCKS}
     */
    @Contract(value = "_ -> new", pure = true)
    public static @NotNull BindingOptions ofDriverBounds(@NotNull DriverBounds bounds) {
        Objects.requireNonNull(bounds, "bounds");
        return NOTHING_STATED.withDriverBounds(bounds);
    }

    /**
     * This binding's own teleport filter, or empty for the server's.
     *
     * <p>Empty is the normal answer and does not mean "unfiltered"; see the class documentation for why
     * the two are not the same state.
     */
    @Contract(pure = true)
    public @NotNull Optional<DriverBounds> driverBounds() {
        return Optional.ofNullable(this.driverBounds);
    }

    /**
     * These options with {@code bounds} as this binding's teleport filter.
     *
     * <p>A ceiling may be set as low as a caller likes — a decoration that should never react to its
     * owner's movement is a real thing to want, and a tiny ceiling is how you ask for it. Only the upper
     * end is refused, because past {@link #RELATIVE_MOVE_LIMIT_BLOCKS} the filter stops being one.
     *
     * @param bounds the per-tick deltas to treat as real movement, or {@code null} to go back to the
     *               server's configured bounds
     * @return a copy with the filter replaced
     * @throws IllegalArgumentException if either ceiling exceeds {@link #RELATIVE_MOVE_LIMIT_BLOCKS}
     */
    @Contract(value = "_ -> new", pure = true)
    public @NotNull BindingOptions withDriverBounds(@Nullable DriverBounds bounds) {
        if (bounds != null) {
            requireWithinProtocolLimit(bounds.maxStepBlocks(), "maxStepBlocks");
            requireWithinProtocolLimit(bounds.maxVerticalStepBlocks(), "maxVerticalStepBlocks");
        }
        return new BindingOptions(bounds);
    }

    /**
     * The bounds this binding will actually be sampled against.
     *
     * <p>The defaulting rule, stated once so that no caller and no implementation has to restate it:
     * whatever was given here if anything was, and the server's otherwise. The parameter is
     * {@code @NotNull} on purpose — the fallback is not optional, which is the other half of why a
     * binding cannot end up unfiltered.
     *
     * <p>Returns {@code serverWide} <em>by identity</em> when nothing was stated, which is what lets a
     * caller detect that no substitution is needed and keep the sampling path free of the allocation a
     * derived snapshot would cost per sample.
     *
     * @param serverWide the configured bounds in force right now
     * @return this binding's bounds
     */
    @Contract(pure = true)
    public @NotNull DriverBounds resolveDriverBounds(@NotNull DriverBounds serverWide) {
        Objects.requireNonNull(serverWide, "serverWide");
        return this.driverBounds == null ? serverWide : this.driverBounds;
    }

    private static void requireWithinProtocolLimit(float ceiling, @NotNull String name) {
        if (ceiling > RELATIVE_MOVE_LIMIT_BLOCKS) {
            throw new IllegalArgumentException(
                    name + " is " + ceiling + " blocks per tick, which is past the "
                            + RELATIVE_MOVE_LIMIT_BLOCKS + " a relative move packet can carry. The"
                            + " client snaps a delta that large instead of interpolating it, so calling"
                            + " it movement would drive the physics with motion nobody sees and launch"
                            + " every bone in the rig — which is the failure the filter exists to"
                            + " prevent. Use " + RELATIVE_MOVE_LIMIT_BLOCKS + " to trust the client's"
                            + " own idea of a teleport, or a real speed for what is being bound: an"
                            + " arrow at full draw covers about three blocks in a tick.");
        }
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof BindingOptions options
                && Objects.equals(this.driverBounds, options.driverBounds);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(this.driverBounds);
    }

    @Override
    public @NotNull String toString() {
        return "BindingOptions[driverBounds="
                + (this.driverBounds == null ? "server-wide" : this.driverBounds) + "]";
    }
}
