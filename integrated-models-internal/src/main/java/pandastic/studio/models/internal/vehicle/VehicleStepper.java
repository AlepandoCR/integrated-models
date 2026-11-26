package pandastic.studio.models.internal.vehicle;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.render.EmissionCadence;
import pandastic.studio.models.api.vehicle.MoveKind;
import pandastic.studio.models.api.vehicle.RiderIntent;
import pandastic.studio.models.api.vehicle.VehicleMove;
import pandastic.studio.models.api.vehicle.VehicleProfile;
import pandastic.studio.models.api.vehicle.VehicleSolver;
import pandastic.studio.models.api.vehicle.VehicleState;
import pandastic.studio.models.api.vehicle.VehicleSteering;

import java.util.Objects;

/**
 * One control step, decided and not applied: the whole of a vehicle's per-tick thinking as a pure
 * function.
 *
 * <h2>Why deciding and applying are separated</h2>
 * Because applying touches the world and deciding does not, and the half that contains every judgement
 * is the half that does not. Everything a vehicle can get wrong lives here — how much elapsed time to
 * trust, whether the rider's keys mean turn or slide, how fast the result may be, whether the resulting
 * displacement is small enough to write in place, whether the vehicle has stopped — and all of it is a
 * function from four values to one record. {@link VehicleImpl} is then a dispatcher: it reads a clock,
 * calls this, and performs the one operation the answer names.
 *
 * <p>That split is what makes the riskiest feature in the project testable without a server. A test
 * drives this with a scripted elapsed time and a scripted bitfield and asserts on the plan, including
 * the teleport decision, which is the thing most expensive to get wrong and least visible when it is.
 *
 * <h2>Elapsed time is measured, never assumed</h2>
 * A repeating task's period is a request. The scheduler drops missed iterations rather than replaying
 * them, so after any stall one step sees the whole gap — and a step that multiplies its nominal period by
 * one instead of asking the clock makes every vehicle on a loaded server travel a distance that depends
 * on the load. So the elapsed nanoseconds come in from outside and are converted here, with the gap
 * clamped by configuration rather than trusted: integrating a five-second gap faithfully puts a vehicle
 * through whatever was in the way, and losing the distance is the lesser failure.
 *
 * <h2>Resting is a fixed point, deliberately</h2>
 * Friction is geometric, so a coasting vehicle approaches zero speed and never arrives. Left alone it
 * writes a position every tick forever for a displacement no client could resolve. So a coasting vehicle
 * whose speed has fallen below the configured rest threshold is snapped to a stop, which makes
 * {@link Plan#isIdle()} stable rather than almost-true — and only while the rider is asking for nothing,
 * because snapping a vehicle that is being driven would stop it from ever starting if its acceleration
 * were below the threshold.
 */
public final class VehicleStepper {

    /**
     * Nanoseconds in one server tick.
     *
     * <p><strong>A protocol fact, derived rather than restated.</strong> The server and the client run
     * the same tick rate — that is what makes a display entity consume its pending-update flags once per
     * server tick's worth of time — and {@link EmissionCadence#CLIENT_TICK_HZ} is where this project
     * already declares it. Deriving from it means the two cannot drift apart, and an operator cannot be
     * offered a key whose only effect would be to make every speed in {@code vehicle.yml} mean something
     * other than what it says.
     */
    static final long NANOS_PER_TICK = 1_000_000_000L / EmissionCadence.CLIENT_TICK_HZ;

    private VehicleStepper() {
    }

    /**
     * What this step should do, for a vehicle with no terrain reaction.
     *
     * <p>The shape this method had before the terrain layer, kept because most of what a vehicle does is
     * decided without reference to what it is driving over, and a caller with nothing to say about the
     * ground should not have to invent a sample. It plans against {@link TerrainAttitude#LEVEL} and a
     * reading that was never taken, which produces a plan whose terrain motion is inert.
     *
     * @param state        where the vehicle is in its own motion
     * @param intent       the keys the rider is holding; {@link RiderIntent#NONE} when there is no rider
     * @param profile      how this vehicle drives
     * @param settings     the clamps and thresholds in force, read once for the whole step
     * @param elapsedNanos nanoseconds since the previous step, from a monotonic clock. Zero or negative
     *                     yields a plan that does nothing, which is the honest answer to "no time has
     *                     passed" and the right one for the first step after a vehicle is created
     * @return the resolved steering, the decided move, and how it has to be applied, for a host whose
     *         in-place move works — see the eight-argument form for why that has to be said
     */
    @Contract(value = "_, _, _, _, _ -> new", pure = true)
    public static @NotNull Plan plan(
            @NotNull VehicleState state,
            @NotNull RiderIntent intent,
            @NotNull VehicleProfile profile,
            @NotNull VehicleSettings settings,
            long elapsedNanos
    ) {
        // A freshly constructed sample is an unavailable one, which is exactly the right input here: no
        // column was read, so nothing is armed and nothing is cancelled.
        return plan(state, intent, profile, settings, elapsedNanos,
                TerrainAttitude.LEVEL, new TerrainSample());
    }

    /**
     * What this step should do.
     *
     * <h2>The order inside one step, and why the terrain comes after the motion</h2>
     * The ceilings in a {@link pandastic.studio.models.api.vehicle.TerrainResponse} are scaled by the
     * input actually achieved — the yaw rate reached, the speed change made — so the terrain reaction is
     * a function of the motion this step decided and not of the keys that asked for it. Reversed, the
     * lean would be taken from what the rider wanted rather than from what the vehicle did, and a vehicle
     * holding a turn against its own speed limit would lean as though it were turning at full rate.
     *
     * <h2>The vertical rise, and why it decides nothing about the kind of move</h2>
     * A rise is folded into the move's {@code deltaY} <strong>before</strong> the kind is taken, so a
     * vehicle that is only climbing — no horizontal displacement at all — still gets a
     * {@link MoveKind#LOCAL} write rather than being judged idle. But the permission to climb is decided
     * <em>before</em> the terrain is consulted, from the horizontal displacement plus the largest rise
     * this step could possibly produce, so the answer cannot be withdrawn afterwards. A climb that was
     * granted and then taken back would have to be pushed into the attitude again, and a rise applied in
     * part and remembered in full is how a vehicle ends up sunk into the block it climbed.
     *
     * @param state        where the vehicle is in its own motion
     * @param intent       the keys the rider is holding; {@link RiderIntent#NONE} when there is no rider
     * @param profile      how this vehicle drives, including its kind and its terrain ceilings
     * @param settings     the clamps and thresholds in force, read once for the whole step
     * @param elapsedNanos nanoseconds since the previous step, from a monotonic clock
     * @param attitude     how the model was sitting at the end of the previous step
     * @param terrain      what the owning region read of the ground under and ahead of the vehicle, or an
     *                     unavailable reading
     * @return the resolved steering, the decided move, how it has to be applied, and what the terrain
     *         does to the model, for a host whose in-place move works
     */
    @Contract(value = "_, _, _, _, _, _, _ -> new", pure = true)
    public static @NotNull Plan plan(
            @NotNull VehicleState state,
            @NotNull RiderIntent intent,
            @NotNull VehicleProfile profile,
            @NotNull VehicleSettings settings,
            long elapsedNanos,
            @NotNull TerrainAttitude attitude,
            @NotNull TerrainSample terrain
    ) {
        // An available in-place move, which is what a caller with no host to ask is really saying: the
        // threshold in the settings is then the only thing that decides between the two ways of moving,
        // which is the shape this method had before the capability was threaded through it.
        return plan(state, intent, profile, settings, elapsedNanos, attitude, terrain, true);
    }

    /**
     * What this step should do on a host that may or may not be able to write a displacement in place.
     *
     * <h2>Why the host's capability is an input to the decision and not only to the application</h2>
     * Because the <em>climb</em> depends on it. A rise is a vertical displacement spread over several
     * steps, and the asynchronous teleport this layer falls back to applies an absolute position with no
     * interpolation and <strong>drops the vertical component on purpose</strong> — so a rise that goes
     * through it snaps however carefully it was spread. The permission to climb is therefore refused for
     * a step whose write is going to be a relocation.
     *
     * <p>That refusal used to be computed from the displacement alone, which made it a promise this
     * method could not keep: a host with no Mojang-mapped adapter has <em>no</em> in-place move, so every
     * displacement is a relocation, and a climb granted against the threshold was then teleported and
     * discarded one call later. It was harmless only because the discard happened to be total — the
     * relocation dropped the rise and cancelled the armed climb — which is a defect being masked by
     * another, not a design. With {@code localMoveAvailable} in hand the answer is the one the contract
     * states, and {@link Plan#kind()} is the kind the write will <em>actually</em> be rather than the
     * kind the displacement would deserve on a server that could perform it.
     *
     * <p>See {@link #plan(VehicleState, RiderIntent, VehicleProfile, VehicleSettings, long,
     * TerrainAttitude, TerrainSample)} for the rest of the ordering, which is unchanged.
     *
     * @param state              where the vehicle is in its own motion
     * @param intent             the keys the rider is holding
     * @param profile            how this vehicle drives, including its kind and its terrain ceilings
     * @param settings           the clamps and thresholds in force, read once for the whole step
     * @param elapsedNanos       nanoseconds since the previous step, from a monotonic clock
     * @param attitude           how the model was sitting at the end of the previous step
     * @param terrain            what the owning region read of the ground under and ahead of the vehicle
     * @param localMoveAvailable whether the host can apply a displacement in place at all, which is
     *                           {@link VehicleHost#supportsLocalMove()} and is a property of the server's
     *                           adapter rather than of this step
     * @return the resolved steering, the decided move, how it has to be applied, and what the terrain
     *         does to the model
     */
    @Contract(value = "_, _, _, _, _, _, _, _ -> new", pure = true)
    public static @NotNull Plan plan(
            @NotNull VehicleState state,
            @NotNull RiderIntent intent,
            @NotNull VehicleProfile profile,
            @NotNull VehicleSettings settings,
            long elapsedNanos,
            @NotNull TerrainAttitude attitude,
            @NotNull TerrainSample terrain,
            boolean localMoveAvailable
    ) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(attitude, "attitude");
        Objects.requireNonNull(terrain, "terrain");

        final VehicleSteering steering = VehicleSteering.of(intent, profile.controlScheme());
        final float ticks = elapsedTicks(elapsedNanos, settings.maxStepTicks());
        final VehicleMove advanced = VehicleSolver.advance(state, steering, profile, ticks);
        final VehicleMove settled = settle(advanced, steering, settings.restSpeedBlocksPerTick());

        final float maxLocal = settings.maxLocalStepBlocks();
        final MoveKind horizontal = reachable(settled.kind(maxLocal), localMoveAvailable);
        // Three terms, and the first is not implied by the second. A host with no in-place move whose
        // step has no horizontal displacement at all has a horizontal kind of NONE, not TELEPORT — so
        // without the capability term a parked vehicle on an adapterless server would be granted a climb
        // that the relocation below it then throws away.
        final boolean climbPermitted = localMoveAvailable
                && horizontal != MoveKind.TELEPORT
                && mayCarryRise(settled, maxLocal, attitude, profile);

        final TerrainSolver.Motion motion = TerrainSolver.respond(
                attitude, terrain, profile, state, settled.next(), ticks, climbPermitted);

        final VehicleMove move = motion.hasRise()
                ? new VehicleMove(settled.deltaX(), motion.rise(), settled.deltaZ(),
                        settled.yawDegrees(), settled.next())
                : settled;
        return new Plan(
                steering, move, reachable(move.kind(maxLocal), localMoveAvailable), ticks, motion);
    }

    /**
     * {@code kind}, with an in-place write downgraded to a relocation on a host that cannot make one.
     *
     * <p>The one place the host's capability enters the decision, so that {@link Plan#kind()} names the
     * operation that will actually happen. {@link MoveKind#NONE} is untouched: a step with nothing to
     * write needs no mechanism to write it with, and promoting it to a relocation would make every parked
     * vehicle on an adapterless server pay for a teleport transaction a tick.
     */
    @Contract(pure = true)
    private static @NotNull MoveKind reachable(@NotNull MoveKind kind, boolean localMoveAvailable) {
        return !localMoveAvailable && kind == MoveKind.LOCAL ? MoveKind.TELEPORT : kind;
    }

    /**
     * Whether this step's horizontal displacement has room for the largest rise the terrain could ask
     * for.
     *
     * <p>Asked before the terrain is consulted, so the permission is a precondition rather than a
     * decision that might be reversed; see {@link #plan}. The bound is the greater of a climb already in
     * flight and the profile's whole step height, which is every rise a single step can produce.
     *
     * <p>About the threshold only. Whether the host can perform an in-place write at all is the caller's
     * other term; see the three-term condition this feeds.
     */
    private static boolean mayCarryRise(
            @NotNull VehicleMove settled,
            float maxLocalStepBlocks,
            @NotNull TerrainAttitude attitude,
            @NotNull VehicleProfile profile
    ) {
        final float ceiling = Math.max(
                attitude.climbRemainingBlocks(), profile.terrain().stepHeightBlocks());
        return MoveKind.forDelta(settled.deltaX(), ceiling, settled.deltaZ(), maxLocalStepBlocks)
                != MoveKind.TELEPORT;
    }

    /**
     * {@code elapsedNanos} as a tick count, clamped.
     *
     * @param elapsedNanos nanoseconds since the previous step
     * @param maxStepTicks the configured ceiling
     * @return the elapsed ticks, never negative and never above the ceiling
     */
    static float elapsedTicks(long elapsedNanos, float maxStepTicks) {
        if (elapsedNanos <= 0L) {
            // A monotonic clock should not go backwards, and the first step of a vehicle's life has
            // nothing to difference against. Both answer "no time has passed", which the solver turns
            // into a move that goes nowhere rather than into a guess.
            return 0F;
        }
        // Divided as a double before narrowing: a float nanosecond count above a few million has
        // consecutive values hundreds of nanoseconds apart, which at a 50 ms tick would quantise the
        // jitter this measurement exists to capture.
        final float ticks = (float) ((double) elapsedNanos / (double) NANOS_PER_TICK);
        return Math.min(ticks, maxStepTicks);
    }

    /**
     * {@code advanced}, with a coasting vehicle below the rest threshold snapped to a dead stop.
     *
     * <p>The heading is kept. A vehicle that has stopped has not forgotten which way it is pointing, and
     * zeroing the heading here would spin every parked vehicle on the server to due south.
     *
     * @param advanced the solver's answer
     * @param steering what the rider is asking for
     * @param restSpeedBlocksPerTick the threshold below which a coasting vehicle has stopped
     * @return the move to apply
     */
    private static @NotNull VehicleMove settle(
            @NotNull VehicleMove advanced,
            @NotNull VehicleSteering steering,
            float restSpeedBlocksPerTick
    ) {
        if (!steering.isNeutral() || !advanced.next().isAtRest(restSpeedBlocksPerTick)) {
            return advanced;
        }
        return VehicleMove.still(
                new VehicleState(0F, 0F, advanced.next().yawDegrees()));
    }

    /**
     * What one step decided.
     *
     * @param steering what the rider's keys resolved to, kept so a plugin can read the jump key this
     *                 layer deliberately does not act on
     * @param move     the displacement, the heading and the state to carry forward
     * @param kind     how the displacement has to reach the world, resolved against the host's own
     *                 ability to write one in place — so a {@link MoveKind#LOCAL} here is a write that
     *                 can really happen and a {@link MoveKind#TELEPORT} covers both a displacement too
     *                 large for the configured threshold and a host with no in-place move at all. The
     *                 caller performs the operation this names and makes no further choice
     * @param ticks    the elapsed time this plan was computed over, after clamping. Exposed because a
     *                 diagnostic about a vehicle that is running slow is a question about this number
     * @param terrain  what the terrain does to the model: the angles to show, the rise already folded
     *                 into {@code move}, and the attitude to carry forward
     */
    public record Plan(
            @NotNull VehicleSteering steering,
            @NotNull VehicleMove move,
            @NotNull MoveKind kind,
            float ticks,
            @NotNull TerrainSolver.Motion terrain
    ) {

        public Plan {
            Objects.requireNonNull(steering, "steering");
            Objects.requireNonNull(move, "move");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(terrain, "terrain");
        }

        /**
         * Whether this step has nothing at all to apply.
         *
         * <p>Both halves have to be settled, which is the same rule the pose pipeline's emission gate
         * follows: a vehicle being steered is not idle however small the displacement, because the next
         * step's displacement will be larger, and a vehicle with a displacement is not idle however
         * neutral the keys. Only the conjunction is a fixed point.
         *
         * <p>The terrain needs no third term here, and that is a property of the order rather than an
         * omission: a rise is folded into the move before its kind is taken, so a vehicle with nothing to
         * do but climb has a {@link MoveKind#LOCAL} displacement and is not idle. The <em>angles</em> are
         * deliberately not considered — a lean that is still settling is a rotation to send and not a
         * position to write, and the two are decided by different gates for different reasons.
         *
         * @return whether the step can be skipped entirely
         */
        public boolean isIdle() {
            return this.steering.isNeutral() && this.kind == MoveKind.NONE;
        }
    }
}
