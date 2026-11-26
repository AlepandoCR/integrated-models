package pandastic.studio.models.internal.vehicle;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.vehicle.ControlScheme;
import pandastic.studio.models.api.vehicle.TerrainResponse;
import pandastic.studio.models.api.vehicle.VehicleKind;
import pandastic.studio.models.api.vehicle.VehicleProfile;
import pandastic.studio.models.api.vehicle.VehicleState;

import java.util.Objects;

/**
 * How a vehicle answers the terrain: one pure function of a sample, a profile, the motion it just decided
 * and the attitude it was in.
 *
 * <h2>The pure half, and the reason the split exists</h2>
 * {@link TerrainSample} is the impure half: taking it reads blocks and has a thread rule. This half reads
 * nothing. Everything a terrain reaction can get wrong lives here — which input each ceiling is scaled
 * by, which kinds honour which term, how a climb is spread over its duration, what happens when there is
 * no sample — and all of it is a function from four values to one record. That is what lets the one piece
 * of behaviour a server cannot be made to report on, <em>the ceilings scaled by input</em>, be asserted
 * by enumeration instead of by driving a cart up a hill and looking at it.
 *
 * <p>It is also closed form, like {@link pandastic.studio.models.api.vehicle.VehicleSolver} and for the
 * same reason: nothing here oscillates, so there is nothing for a substepped integrator to stabilise. The
 * blend is geometric in the tick count and the climb is linear in it, so one step of two ticks gives the
 * same answer as two steps of one — a control loop that missed a tick produces a vehicle that reacted
 * late, not one that ended up at a different angle.
 *
 * <h2>Every angle is a ceiling scaled by the fraction of a full-rate input achieved</h2>
 * Stated on {@link TerrainResponse} and implemented here. Three ceilings, three inputs, and which input
 * drives which is not a choice this class makes freely — {@link VehicleProfile} publishes the rule and
 * refuses a profile whose denominator is zero, so the fractions below cannot be a zero over zero:
 *
 * <ul>
 *   <li><strong>Bank</strong> — under {@link ControlScheme#TURNING} the fraction is the yaw rate actually
 *       achieved over {@code turnRateDegreesPerTick}; under {@link ControlScheme#STRAFING} it is the side
 *       speed over {@code maxStrafeSpeedBlocksPerTick}. Measured from the two states rather than from the
 *       steering, because "how hard it is actually turning" is a statement about the motion and not about
 *       the key. A tight turn therefore leans further than a gentle one with nothing extra configured,
 *       which is the whole reason one number is enough.</li>
 *   <li><strong>Slope pitch</strong> — the fraction is the grade of the terrain, which is the rise over
 *       {@link TerrainSample#PROBE_RUN_BLOCKS}. Scaled by the terrain and by no term of the profile,
 *       which is what {@link VehicleProfile} says of it.</li>
 *   <li><strong>Acceleration pitch</strong> — the fraction is the speed change per tick over the
 *       profile's own acceleration or braking rate, whichever applies. Both are required positive by the
 *       profile.</li>
 * </ul>
 *
 * <h2>Which kinds honour which term is read from the predicates, never from the enum</h2>
 * {@link VehicleKind#followsSlope()} and {@link VehicleKind#pitchesFromAcceleration()} are consulted and
 * the two contributions are <em>added</em>, rather than a switch picking one. Today they are mutually
 * exclusive — ground is the only kind that follows slope and the only one that does not pitch from
 * acceleration — but a fifth kind that did both would need no change here, and a switch would have to be
 * found and corrected.
 *
 * <h2>A wall is not a slope</h2>
 * The grade is taken from a rise the vehicle could plausibly take. Above that the column ahead is a wall,
 * and the answer is zero rather than a clamped maximum: a vehicle stopped dead by a cliff face sits
 * level, where one scaled to its full slope angle rears up at it. Downhill has no such ceiling, because
 * driving off a ledge genuinely is a slope and the vehicle genuinely should nose into it.
 *
 * <h2>Nothing here is tunable</h2>
 * Every ceiling arrives in the {@link TerrainResponse} the profile carries, every denominator in the
 * profile itself, the grade from the sample, and the elapsed time from a measured clock. The literals are
 * {@code 0} and {@code 1} as the additive and multiplicative identities, and the two documented facts
 * below.
 */
public final class TerrainSolver {

    /**
     * Degrees per unit of the wire's rotation byte.
     *
     * <p><strong>A protocol fact.</strong> An entity's yaw and pitch travel as a single byte, so the
     * finest rotation a client can be told about is one two-hundred-and-fifty-sixth of a turn. It is here
     * because {@link #isVisiblyDifferent(float, float)} is what stops this layer paying for a packet that
     * cannot change a pixel, and made configurable it would stop being a guarantee: an operator lowering
     * it would buy packets the protocol discards, and raising it would hide rotations the protocol can
     * carry.
     */
    private static final float DEGREES_PER_ROTATION_BYTE = 360F / 256F;

    /**
     * The shallowest rise that counts as a wall rather than a slope, in blocks.
     *
     * <p><strong>Structure</strong>, in the same sense {@link TerrainSample#PROBE_RUN_BLOCKS} is: a rise
     * of one block over a run of one block is a forty-five-degree grade, which is the steepest thing a
     * block grid can express between two adjacent columns. A vehicle whose own step height is higher may
     * take more than that and the ceiling moves up with it; one whose step height is lower — or zero,
     * because climbing is switched off — still noses up at the block in front of it, because the grade it
     * is looking at is real whether or not it can climb it.
     */
    private static final float MIN_WALL_HEIGHT_BLOCKS = 1F;

    private TerrainSolver() {
    }

    /**
     * What the terrain does to this vehicle over this step.
     *
     * @param attitude       how the model was sitting at the end of the previous step
     * @param sample         what is under and ahead of the vehicle, or an unavailable reading
     * @param profile        how this vehicle drives, which carries both the kind and the ceilings
     * @param previous       the motion the vehicle was in before this step
     * @param current        the motion this step decided, whose yaw and speeds are the inputs the
     *                       ceilings are scaled by
     * @param ticks          elapsed server ticks, already clamped by the caller. Zero leaves everything
     *                       exactly as it was, which is the honest answer to "no time has passed"
     * @param climbPermitted whether this step's write can carry a vertical rise. False when the
     *                       displacement is going through the asynchronous teleport, which applies an
     *                       absolute position with no interpolation at all — see {@link Motion#rise()}.
     *                       Including when it is going through it because the host has no in-place move
     *                       at all, which is every step of every vehicle on a server with no
     *                       Mojang-mapped adapter; {@link VehicleStepper#plan} is given that fact
     *                       expressly rather than inferring it from the displacement, because the
     *                       displacement cannot show it
     * @return the angles to show, the rise to apply, and the attitude to carry forward
     */
    @Contract(value = "_, _, _, _, _, _, _ -> new", pure = true)
    public static @NotNull Motion respond(
            @NotNull TerrainAttitude attitude,
            @NotNull TerrainSample sample,
            @NotNull VehicleProfile profile,
            @NotNull VehicleState previous,
            @NotNull VehicleState current,
            float ticks,
            boolean climbPermitted
    ) {
        Objects.requireNonNull(attitude, "attitude");
        Objects.requireNonNull(sample, "sample");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");
        if (!Float.isFinite(ticks) || ticks < 0F) {
            throw new IllegalArgumentException(
                    "ticks must be finite and not negative but was " + ticks);
        }
        if (ticks == 0F) {
            // Nothing has happened. Blending over zero time is the identity and a climb advanced over
            // zero time would be a rise the vehicle got for free, which is how a loop that is asked twice
            // in one tick would climb twice as fast as one that is asked once.
            return Motion.unchanged(attitude);
        }

        final TerrainResponse response = profile.terrain();
        final VehicleKind kind = profile.kind();

        float pitchTarget = 0F;
        if (kind.followsSlope()) {
            pitchTarget += slopeTarget(response, sample);
        }
        if (kind.pitchesFromAcceleration()) {
            pitchTarget += accelerationTarget(response, profile, previous, current, ticks);
        }

        final float blend = response.angleBlendPerTick();
        final float bank =
                blendToward(attitude.bankDegrees(), bankTarget(response, profile, previous, current,
                        ticks), blend, ticks);
        final float pitch = blendToward(attitude.pitchDegrees(), pitchTarget, blend, ticks);

        return climb(attitude, sample, profile, bank, pitch, ticks, climbPermitted);
    }

    /**
     * The rise this step applies, and the climb state to carry forward.
     *
     * <p>Armed from the column ahead and never re-armed while one is in flight, so a staircase is climbed
     * one step per {@link TerrainResponse#stepDurationTicks()} rather than having its remaining distance
     * replaced every step — see {@link TerrainAttitude#climbPerTickBlocks()} for why the second never
     * finishes.
     *
     * <p>An unknown column ahead arms nothing and <strong>cancels nothing</strong>. A climb already in
     * flight was decided against terrain that was read, and abandoning it half-way would leave the
     * vehicle sunk into the block it was climbing onto — which is a worse answer than finishing a climb
     * whose last tick nobody re-checked.
     *
     * <h2>A climb is armed only from a reading taken this step</h2>
     * The one place {@link TerrainSample#isReused()} is consulted, and the reason that flag exists. Every
     * other term here is idempotent — an angle blended twice toward the same target is an angle that got
     * closer to it — but a rise is <em>added to the vehicle's position</em>, so arming one twice from one
     * reading is a block of height nobody measured. A reading that survives several steps, which is the
     * whole point of {@link TerrainSampleGate}, would otherwise re-arm the same step up after the climb
     * it described had already finished: the vehicle would ascend for ever while every individual
     * decision stayed correct. See {@link TerrainSample#markReused()}.
     *
     * <p>Note what this does <em>not</em> gate. A climb in flight keeps advancing on a reused reading,
     * because it was armed from one that was taken and the rise it is applying is already decided; and
     * the slope pitch is computed from a reused reading like any other, because it is a target the model
     * blends toward rather than a displacement.
     */
    private static @NotNull Motion climb(
            @NotNull TerrainAttitude attitude,
            @NotNull TerrainSample sample,
            @NotNull VehicleProfile profile,
            float bank,
            float pitch,
            float ticks,
            boolean climbPermitted
    ) {
        final TerrainResponse response = profile.terrain();
        float remaining = attitude.climbRemainingBlocks();
        float perTick = attitude.climbPerTickBlocks();

        if (remaining <= 0F && !sample.isReused() && profile.climbs()
                && sample.isSupported() && sample.isAheadSupported()) {
            final double rise = sample.rise();
            if (rise > 0.0D && rise <= (double) response.stepHeightBlocks()) {
                remaining = (float) rise;
                // Fixed once, here, for the life of this climb. See TerrainAttitude.
                perTick = remaining / (float) response.stepDurationTicks();
            }
        }

        if (remaining <= 0F || !climbPermitted) {
            return new Motion(bank, pitch, 0.0D,
                    new TerrainAttitude(bank, pitch, remaining, perTick));
        }

        final float applied = Math.min(remaining, perTick * ticks);
        final float left = remaining - applied;
        return new Motion(bank, pitch, applied,
                left <= 0F
                        ? new TerrainAttitude(bank, pitch, 0F, 0F)
                        : new TerrainAttitude(bank, pitch, left, perTick));
    }

    /**
     * How far the model should be rolled, given how hard it is actually turning.
     *
     * <p>Positive is the model's right side down, and the sign follows the direction it is going round:
     * a right-hand turn — which is an increasing yaw, because Minecraft measures yaw clockwise — rolls
     * the right side down, as an aircraft banks into its turn rather than away from it. Under
     * {@link ControlScheme#STRAFING} there is no turn to read, so the same reading is taken from the
     * slide: sliding to its left is going left, so it leans left.
     */
    private static float bankTarget(
            @NotNull TerrainResponse response,
            @NotNull VehicleProfile profile,
            @NotNull VehicleState previous,
            @NotNull VehicleState current,
            float ticks
    ) {
        if (response.bankDegrees() <= 0F) {
            return 0F;
        }
        final float fraction;
        if (profile.controlScheme() == ControlScheme.TURNING) {
            // Normalised, because a vehicle that crossed the ±180 seam between the two states has turned
            // a few degrees and not three hundred and sixty of them.
            final float yawRate = VehicleState.normaliseDegrees(
                    current.yawDegrees() - previous.yawDegrees()) / ticks;
            fraction = yawRate / profile.turnRateDegreesPerTick();
        } else {
            fraction = -current.strafeSpeedBlocksPerTick() / profile.maxStrafeSpeedBlocksPerTick();
        }
        return Math.clamp(fraction, -1F, 1F) * response.bankDegrees();
    }

    /**
     * How far the model should be pitched to lie along the ground, given the grade ahead of it.
     *
     * <p>Negative is nose up, which is the sign the server measures pitch in, so climbing a grade gives a
     * negative pitch. A grade steeper than a wall gives zero; see the class note.
     */
    private static float slopeTarget(
            @NotNull TerrainResponse response,
            @NotNull TerrainSample sample
    ) {
        if (response.slopePitchDegrees() <= 0F || !sample.isSupported()
                || !sample.isAheadSupported()) {
            return 0F;
        }
        final double rise = sample.rise();
        final double wall = Math.max(response.stepHeightBlocks(), MIN_WALL_HEIGHT_BLOCKS);
        if (rise > wall) {
            return 0F;
        }
        final float grade = (float) (rise / (double) TerrainSample.PROBE_RUN_BLOCKS);
        return -Math.clamp(grade, -1F, 1F) * response.slopePitchDegrees();
    }

    /**
     * How far the model should be pitched by its own acceleration.
     *
     * <p>Negative is nose up, so opening the throttle squats the stern and closing it noses down — which
     * is the one terrain-reactive term a vessel with no ground under it actually has. The denominator is
     * the profile's acceleration rate when the speed's magnitude is growing and its braking rate when it
     * is shrinking, matching the regimes
     * {@link pandastic.studio.models.api.vehicle.VehicleSolver} integrates in; both are required positive
     * by {@link VehicleProfile}, so neither can make this a zero over zero.
     */
    private static float accelerationTarget(
            @NotNull TerrainResponse response,
            @NotNull VehicleProfile profile,
            @NotNull VehicleState previous,
            @NotNull VehicleState current,
            float ticks
    ) {
        if (response.accelerationPitchDegrees() <= 0F) {
            return 0F;
        }
        final float change =
                (current.forwardSpeedBlocksPerTick() - previous.forwardSpeedBlocksPerTick()) / ticks;
        final boolean gaining = Math.abs(current.forwardSpeedBlocksPerTick())
                >= Math.abs(previous.forwardSpeedBlocksPerTick());
        final float rate = gaining
                ? profile.accelerationBlocksPerTickSquared()
                : profile.brakingBlocksPerTickSquared();
        return -Math.clamp(change / rate, -1F, 1F) * response.accelerationPitchDegrees();
    }

    /**
     * {@code current} moved a {@code blendPerTick} share of the way to {@code target} per tick.
     *
     * <p>Geometric in the tick count rather than linear, for the reason
     * {@link pandastic.studio.models.api.vehicle.VehicleSolver}'s friction is: closing a fraction of the
     * <em>remaining</em> difference compounds, so the exponent is the tick count and one step of two
     * ticks agrees with two steps of one. A blend of exactly {@code 1} snaps, which
     * {@link Math#pow(double, double)} gives for free as zero for any positive exponent.
     *
     * @param current      where the angle is
     * @param target       where it is being asked to go
     * @param blendPerTick the share of the remaining difference closed each tick, in {@code (0, 1]}
     * @param ticks        elapsed ticks, positive
     * @return the new angle
     */
    static float blendToward(float current, float target, float blendPerTick, float ticks) {
        final float retained = (float) Math.pow(1.0D - (double) blendPerTick, (double) ticks);
        final float blended = target + (current - target) * retained;
        // Folded for the reason TerrainAttitude folds it: an angle that blended to -0.0 is equal to
        // nothing, including to the level attitude it is indistinguishable from.
        return blended == 0F ? 0F : blended;
    }

    /**
     * Whether two angles differ by enough for a client to be able to draw the difference.
     *
     * <p>The gate on the emission, and the reason a vehicle whose lean is settling does not spend a
     * packet a tick on it. See {@link #DEGREES_PER_ROTATION_BYTE}: the wire carries a rotation as one
     * byte, so two angles that pack to the same byte are the same rotation as far as anything downstream
     * is concerned. The stored angle keeps its full precision and keeps blending, so a change below this
     * is deferred rather than discarded — it is sent on whichever later step it finally crosses a byte.
     *
     * @param degrees      one angle
     * @param otherDegrees the other
     * @return whether sending the first would show anything the second does not
     */
    @Contract(pure = true)
    public static boolean isVisiblyDifferent(float degrees, float otherDegrees) {
        return packDegrees(degrees) != packDegrees(otherDegrees);
    }

    /** {@code degrees} in the units the wire carries a rotation in. */
    private static byte packDegrees(float degrees) {
        return (byte) Math.round(degrees / DEGREES_PER_ROTATION_BYTE);
    }

    /**
     * What one step's terrain reaction decided.
     *
     * <h2>Why the rise is separate from the angles</h2>
     * Because they reach the world through different operations, and one of them has a protocol
     * constraint the other does not. The angles are a rotation written to the model; the rise is a
     * vertical displacement written to the root entity, and it is only smooth while it is small enough
     * for the client to interpolate across. That is why it is spread over
     * {@link TerrainResponse#stepDurationTicks()} and why a step going through the asynchronous teleport
     * carries none of it: a teleport applies an absolute position with no interpolation, so a block of
     * rise inside one would snap however carefully the rest was spread.
     *
     * @param bankDegrees  how far the model should be rolled now, right side down positive
     * @param pitchDegrees how far it should be pitched now, nose up negative
     * @param rise         world y displacement to apply this step, in blocks; never negative, because a
     *                     vehicle going downhill falls under the server's own gravity
     * @param next         the attitude to carry into the next step
     */
    public record Motion(
            float bankDegrees,
            float pitchDegrees,
            double rise,
            @NotNull TerrainAttitude next
    ) {

        public Motion {
            Objects.requireNonNull(next, "next");
            if (!Float.isFinite(bankDegrees) || !Float.isFinite(pitchDegrees)
                    || !Double.isFinite(rise)) {
                throw new IllegalArgumentException("A terrain motion must be finite");
            }
        }

        /**
         * A step that changed nothing.
         *
         * @param attitude the attitude to carry forward unchanged
         * @return a motion with the attitude's own angles and no rise
         */
        @Contract(value = "_ -> new", pure = true)
        public static @NotNull Motion unchanged(@NotNull TerrainAttitude attitude) {
            Objects.requireNonNull(attitude, "attitude");
            return new Motion(attitude.bankDegrees(), attitude.pitchDegrees(), 0.0D, attitude);
        }

        /** Whether this step has a vertical rise to write. */
        @Contract(pure = true)
        public boolean hasRise() {
            return this.rise != 0.0D;
        }
    }
}
