package pandastic.studio.models.api.vehicle;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Everything a {@link VehiclePilot} is told about the step it is deciding.
 *
 * <h2>Why a value of plain numbers and not the entity</h2>
 * Because a pilot decides and does not act, and the only way to make that structural rather than
 * documented is to hand it nothing it could act through. There is no world here, no entity and no
 * location — the same argument {@link pandastic.studio.models.api.ai.AiPoint} makes about a Bukkit
 * {@code Location}: a triple of doubles carries no permission to read a region and needs none, so a
 * pilot built against this is pure arithmetic and is exercisable with no server.
 *
 * <p>{@link #vehicle()} is in here anyway, and deliberately only for the questions that are safe from
 * any thread — {@link Vehicle#getSeating()}, {@link Vehicle#getRig()}, {@link Vehicle#getState()},
 * identity. It is what lets one pilot instance serve several vehicles and still tell them apart.
 *
 * <h2>The position is read, the heading is decided</h2>
 * {@link #x()}, {@link #y()} and {@link #z()} are the root entity's real position, read from the world at
 * the top of this step — so a vehicle that was pushed, blocked by a wall or relocated by somebody else is
 * described by where it actually is. The heading, by contrast, comes from {@link #state()}, which is this
 * layer's own authority over which way the vehicle is pointing and the value the solver is about to
 * integrate. Taking the heading from the entity instead would make a pilot steer against a yaw the
 * renderer had already been handed something else for.
 *
 * <h2>{@code ticks} is measured, and is what makes a pilot need no tuning</h2>
 * The same figure the vehicle itself integrates this step: the elapsed time since the previous step, in
 * server ticks, after the operator's clamp. It is <em>not</em> the control period, because the platform
 * drops missed iterations of a repeating task rather than replaying them.
 *
 * <p>It matters more here than it looks. A pilot holds down whole keys — there is no fractional throttle
 * in a {@link RiderIntent} — so the smallest correction it can ask for is one step's worth of full input,
 * and that quantity is {@code rate × ticks}. Every threshold {@link PilotSteering} uses is that product,
 * which is why that class holds no configured number at all. A pilot handed a nominal period instead
 * would quantise its own dead bands against a step size the server was not actually running at, and would
 * oscillate under load.
 *
 * @param vehicle the vehicle being steered. Safe to ask the any-thread questions of; nothing else
 * @param x       the root entity's world x, in blocks, as read at the top of this step
 * @param y       the root entity's world y, in blocks. Carried although {@link PilotSteering} ignores it:
 *                a kind with free vertical motion is described by it, and a context that omitted it could
 *                not be extended to one
 * @param z       the root entity's world z, in blocks
 * @param state   how the vehicle is moving, including the heading the solver will integrate from
 * @param profile how the vehicle drives, which is where every rate a threshold is derived from lives
 * @param ticks   server ticks this step covers, measured and clamped. Never negative; {@code 0} on the
 *                first step of a vehicle's life and on any step following one the loop skipped
 */
public record PilotContext(
        @NotNull Vehicle vehicle,
        double x,
        double y,
        double z,
        @NotNull VehicleState state,
        @NotNull VehicleProfile profile,
        float ticks
) {

    /**
     * Refuses a context nothing could decide from.
     *
     * <p>A non-finite coordinate is the one worth checking: it reaches the pilot as a target offset,
     * comes back as a NaN comparison that is false in both directions, and shows up as an autonomous
     * vehicle that holds no key and gives no reason. A negative tick count is refused for the reason
     * {@link VehicleSolver#advance} refuses one — it is a clock that went backwards, not a short step.
     */
    public PilotContext {
        Objects.requireNonNull(vehicle, "vehicle");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(profile, "profile");
        require(x, "x");
        require(y, "y");
        require(z, "z");
        if (!Float.isFinite(ticks) || ticks < 0F) {
            throw new IllegalArgumentException("ticks must be finite and not negative but was " + ticks);
        }
    }

    /** The heading the solver will integrate from, in the degrees the server measures yaw in. */
    public float headingDegrees() {
        return this.state.yawDegrees();
    }

    /**
     * Whether this step has any elapsed time to decide over.
     *
     * <p>A pilot with none has no authority to compare a threshold against and the solver will produce no
     * displacement either way, so the honest answer for such a step is {@link RiderIntent#NONE}. Offered
     * as a question rather than left to each pilot to remember.
     */
    public boolean hasElapsed() {
        return this.ticks > 0F;
    }

    private static void require(double coordinate, @NotNull String name) {
        if (!Double.isFinite(coordinate)) {
            throw new IllegalArgumentException(name + " must be finite but was " + coordinate);
        }
    }
}
