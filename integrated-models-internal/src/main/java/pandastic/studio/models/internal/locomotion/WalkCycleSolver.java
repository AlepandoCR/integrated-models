package pandastic.studio.models.internal.locomotion;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.locomotion.GaitProfile;
import pandastic.studio.models.api.locomotion.GaitRole;
import pandastic.studio.models.api.locomotion.GaitState;
import pandastic.studio.models.api.locomotion.GaitTuning;
import pandastic.studio.models.api.locomotion.LocomotionBinding;
import pandastic.studio.models.api.locomotion.LocomotionSolver;
import pandastic.studio.models.api.math.IkTolerance;
import pandastic.studio.models.api.math.TwoBoneIk;
import pandastic.studio.models.api.physics.PhysicsDriver;
import pandastic.studio.models.api.pose.Pose;

/**
 * A walk cycle evaluated from a distance-driven phase, with inverse kinematics on kneed legs.
 *
 * <h2>The model</h2>
 * One scalar, {@code phase}, says where in the stride the model is. It advances by the ground covered
 * divided by the stride length, and is evaluated — never integrated:
 *
 * <pre>
 *   (f, s)      = the driver's speeds rotated into the rig's own frame      blocks per server tick
 *   speed       = |(f, s)|
 *   moving      = speed ≥ movementDeadbandBlocksPerTick
 *   phase      += moving ? speed · h · TICKS_PER_SECOND / strideLength : 0     (mod 1)
 *   amplitude  → moving ? min(1, speed / fullStrideSpeedBlocksPerTick) : 0     (linear ramp)
 *   sprint     → moving ? clamp01((speed − walkSpeed) / (sprintSpeed − walkSpeed)) : 0  (linear ramp)
 *   bias        = moving ? f / speed : 1
 *   weight      = amplitude · (1 − airborneBlend)
 * </pre>
 *
 * and then every bone is a closed-form function of {@code phase} and {@code weight}. The left leg sits
 * at {@code phase}; the right leg, the left arm and everything else in opposition sit half a cycle away.
 *
 * <h2>The rig's frame, not the owner's</h2>
 * A {@link pandastic.studio.models.api.physics.PhysicsDriver} reports movement in the owner's frame; the
 * bones were modelled in the rig's. {@link LocomotionBinding#forwardSpeedInRigFrame} rotates the pair
 * between the two, and that rotation is the first thing this solver does with a sample — before the
 * magnitude, before the deadband, before anything. Two consequences fall out of it being a rotation:
 *
 * <ul>
 *   <li>The magnitude is unchanged, so <em>how fast</em> a model appears to walk cannot depend on how its
 *       rig was authored. Only the direction can.</li>
 *   <li>That direction arrives as {@code bias}, the cosine between travel and the rig's forward axis. At
 *       {@code +1} — a rig authored the way it walks — every term below is bit-for-bit what it was before
 *       there was a facing offset at all. At {@code −1} the cycle runs mirrored, which is the same
 *       function of a negated phase and therefore still closed-form. At {@code 0} the model is travelling
 *       straight across its own forward axis, and the fore-aft excursion correctly vanishes: this layer
 *       has no strafing gait, and striding forward while moving sideways is a sliding foot.</li>
 * </ul>
 *
 * <h2>Walk and sprint are two amplitudes, not one scaled</h2>
 * {@code amplitude} saturates at walking pace, because walking pace is the speed almost everything moves
 * at and the speed the gait should look right at. Above it, {@code sprint} blends the one-piece leg swing
 * from {@link GaitTuning#legSwingRadians()} toward {@link GaitTuning#sprintLegSwingRadians()}. Scaling
 * one amplitude by speed instead would make a sprint a louder walk, which it is not — it is a longer
 * stride with a different limb excursion. Both blends are zero at and below walking pace, so nothing
 * below it moved when the second amplitude arrived.
 *
 * <p>The sprint amplitude reaches only the one-piece leg. A kneed leg's thigh angle is not an amplitude
 * at all: it comes out of the inverse kinematics placing a foot whose fore-aft excursion is pinned to the
 * ground that passed under it. Scaling <em>that</em> is exactly the edit {@link
 * GaitTuning#strideToFootReach()} documents as desynchronising the foot from the ground, so a kneed rig
 * expresses a sprint through its stride length, as it already did.
 *
 * <h2>Why a phase and not an integrator</h2>
 * Three reasons, in order of weight.
 *
 * <p>A gait's correctness condition is that the foot does not slide: the limb's fore-aft excursion has to
 * match the ground that passed under it. That is a statement about <em>distance</em>, and a phase driven
 * by distance satisfies it by construction. An integrator driven by time satisfies it only if its rate
 * happens to match the owner's speed, which is the thing that varies.
 *
 * <p>An integrator would inherit a fixed-step requirement. {@code AngularPhysicsSolver} documents at
 * length why its own step is a stability bound and why a backlog must be discarded rather than carried;
 * adding a second integrator means a second copy of that argument, and a second thing to get wrong.
 *
 * <p>And evaluation is reversible. A gait that is a function of its phase can be queried at any phase, so
 * a caller can cross-fade two models, rewind, or sample the same stride twice and get the same answer.
 * None of that is true of state that was arrived at by stepping.
 *
 * <h2>Why this runs before physics, and writes rather than adds</h2>
 * {@link pandastic.studio.models.api.physics.PhysicsSolver#solve} is additive by contract, so something
 * has to establish the primary pose it adds to. This is that something: every bone the
 * {@link LocomotionBinding} names is <strong>assigned</strong> an absolute rotation, and every bone it
 * does not name is left untouched. Running physics first instead would have locomotion overwrite the
 * secondary motion, which presents as physics not working at all.
 *
 * <h2>Every calibrated angle is injected</h2>
 * Nothing cosmetic is decided here. The swing amplitudes, the blend durations, the deadband, the guards
 * and the idle oscillation all arrive as a {@link GaitTuning}, and the IK's degeneracy threshold as an
 * {@link IkTolerance}. Both are taken in the constructor rather than per call, so one instance is bound
 * to one snapshot: a reload builds a new solver and new states from the new snapshot rather than mutating
 * a live one. {@link WalkCycleState#isSettled()} is checked against
 * {@link GaitTuning#settledAmplitude()}, which is a state's own copy of the same reasoning.
 *
 * <p>Two numbers are <em>not</em> tunable, and that is the point of them: {@link #TICKS_PER_SECOND} is
 * the server's tick rate and {@code TAU} is a turn. See their own notes.
 *
 * <h2>The deadband is the most important number here</h2>
 * Below {@link GaitTuning#movementDeadbandBlocksPerTick()} the phase does not advance and the amplitude
 * ramps to zero, so a standing model converges on its rest pose and holds there. Without it,
 * floating-point noise in a sampled position delta walks the phase forward forever, and a model that
 * shuffles its feet while standing still is the single most visible defect this layer can ship. Note
 * that freezing the phase is not enough on its own and ramping the amplitude is: the pose has to converge
 * somewhere specific, and zero amplitude converges on the rest pose regardless of which phase the cycle
 * happened to stop at. A configured deadband of zero therefore reintroduces exactly that defect — it is
 * permitted, because a caller that quantises its own speed sampling may legitimately want it, but it is
 * the setting to suspect first when a model shuffles.
 *
 * <h2>Extremities are posed by cancellation, not by an amplitude</h2>
 * A {@link pandastic.studio.models.api.pose.Pose} is parent-relative, so a bone's world pitch is the sum
 * of its chain. A foot bone left at zero therefore inherits whatever the shin is doing — it points down
 * through the swing and drives into the ground at stance, which is the ice-skating look. So a bound foot
 * is written as the <em>negation</em> of the chain above it, plus the angle it is meant to hold against
 * the ground:
 *
 * <pre>
 *   footPitch = −(thighPitch + shinPitch) + legScale · groundPitch(phase)
 * </pre>
 *
 * with {@code shinPitch} simply absent from the sum when no shin is bound, which is what makes a foot
 * independent of the knee rather than conditional on it.
 *
 * <p>The counter-rotation is <strong>not</strong> scaled by the gait's weight and the ground pitch is.
 * That split is deliberate and the asymmetry is the point: a resting kneed leg stands at
 * {@link GaitTuning#restingLegExtension()}, so its chain sums to half the resting knee bend even at zero
 * amplitude, and fading the counter-rotation out with the cycle would leave a <em>standing</em> model's
 * soles pitched into the floor — the exact defect this is here to remove. The ground pitch, by contrast,
 * is a property of the stride, so it belongs to the stride's weight and fades with it.
 *
 * <p>A hand gets the same cancellation scaled by {@link GaitProfile#handStabilise()} rather than applied
 * whole, because a hand has no ground to be parallel to. At {@code 0} it follows its parent exactly, at
 * {@code 1} it is held level, and in between it lags — which is the range between a relaxed arm and one
 * carrying something. Nothing here assumes a hand exists: a single-piece arm has no hand group, and an
 * item is anchored to a bone name rather than to a role for that reason.
 *
 * <h2>Resting legs are deliberately short of straight</h2>
 * A kneed leg at rest extends to {@link GaitTuning#restingLegExtension()} of its reach, not to all of it.
 * At full extension the two segments are collinear, the law of cosines' joint angle is {@code acos(−1)},
 * and the bend is zero — the one configuration in which the knee's direction is undefined. Approaching
 * it, tiny changes in the foot target produce large changes in the solved angles, so a leg that
 * straightens fully jitters at exactly the moment it is most visible. Standing with a slight bend costs a
 * few degrees of stance and removes the singularity from the reachable set entirely.
 *
 * <h2>Allocation</h2>
 * The step path allocates nothing of its own: the state is scalar and every intermediate is a local. The
 * one object created per kneed leg is {@link TwoBoneIk.Result}, which this solver has no way to avoid —
 * it is the IK's return type — but which is created, read and discarded inside
 * {@link #writeLeg} without ever being stored or passed on, so it is the textbook case escape analysis
 * scalar-replaces.
 *
 * <p>Stateless and thread-safe: the tuning is immutable, so one instance may serve every model on the
 * server.
 */
public final class WalkCycleSolver implements LocomotionSolver {

    /**
     * Server ticks per second. Not a tuning value and deliberately not configurable:
     * {@link PhysicsDriver}'s speeds are per tick and the delta is in seconds, so this is the unit
     * conversion between them. It is the same protocol fact
     * {@link pandastic.studio.models.api.render.EmissionCadence#CLIENT_TICK_HZ} states for the emission
     * side, and the reasoning there applies verbatim here.
     */
    static final float TICKS_PER_SECOND = 20F;

    /**
     * One full turn, in radians. Phases here are in cycles, so every evaluation scales by this. A
     * mathematical constant rather than a preference, and so not configurable.
     */
    private static final float TAU = (float) (Math.PI * 2);

    /**
     * Reciprocal of the peak of {@code max(0, cos θ) · max(0, ∓sin θ)}, which the foot's ground pitch is
     * shaped by.
     *
     * <p>A mathematical fact rather than a tuning value: the product of a sine and a cosine is
     * {@code ½·sin 2θ}, so the lobe peaks at exactly a half, and dividing by that half is what makes the
     * configured toe-off and heel-strike angles the <em>actual</em> peak angles instead of half of them.
     * Configurable, it would be a second amplitude multiplying the first.
     */
    private static final float SWING_LOBE_SCALE = 2F;

    private final @NotNull GaitTuning tuning;

    private final @NotNull IkTolerance ikTolerance;

    /**
     * {@link GaitTuning#elapsedWrapSeconds()}, cached rather than recomputed.
     *
     * <p>Cached because the accessor is a division and the step path runs over every model on the server;
     * read from the tuning rather than re-derived here because the tuning owns the reason the interval is
     * a whole number of breaths, and two copies of that derivation could disagree.
     */
    private final float elapsedWrapSeconds;

    /**
     * @param tuning      every calibrated angle, duration and guard the cycle is evaluated from.
     *                    Immutable, so the solver stays shareable; replace the solver rather than the
     *                    tuning to reload
     * @param ikTolerance the degeneracy threshold handed to {@link TwoBoneIk#solve}, held here because
     *                    the IK is a static function and has nowhere else to read it from
     */
    public WalkCycleSolver(@NotNull GaitTuning tuning, @NotNull IkTolerance ikTolerance) {
        this.tuning = tuning;
        this.ikTolerance = ikTolerance;
        this.elapsedWrapSeconds = tuning.elapsedWrapSeconds();
    }

    @Override
    public @NotNull GaitState createState(@NotNull LocomotionBinding binding) {
        return new WalkCycleState(binding, this.tuning);
    }

    @Override
    public float movementDeadband() {
        return this.tuning.movementDeadbandBlocksPerTick();
    }

    @Override
    public void solve(
            @NotNull LocomotionBinding binding,
            @NotNull GaitProfile profile,
            @NotNull GaitState state,
            @NotNull Pose pose,
            float deltaSeconds,
            @NotNull PhysicsDriver driver
    ) {
        if (!(state instanceof WalkCycleState gait)) {
            throw new IllegalArgumentException(
                    "This solver only accepts states it created, got " + state.getClass().getName());
        }
        if (gait.boneCount() != binding.boneCount()) {
            throw new IllegalArgumentException(
                    "This state was created for a " + gait.boneCount() + "-bone binding but '"
                            + binding.getRigName() + "' has " + binding.boneCount()
                            + ". A state belongs to one model.");
        }
        if (pose.boneCount() != binding.boneCount()) {
            throw new IllegalArgumentException(
                    "Pose holds " + pose.boneCount() + " bones but binding '" + binding.getRigName()
                            + "' expects " + binding.boneCount());
        }
        // NaN fails the first test, so the ordering matters: `!(x > 0)` is true for NaN whereas
        // `x <= 0` is false for it.
        if (!(deltaSeconds > 0F) || !Float.isFinite(deltaSeconds)) {
            return;
        }

        final float step = Math.min(deltaSeconds, this.tuning.maxStepSeconds());

        // Elapsed time, wrapped at a whole number of breaths. One subtraction suffices because the step
        // is clamped far below the wrap interval.
        float elapsed = gait.elapsedSeconds + step;
        if (elapsed >= this.elapsedWrapSeconds) {
            elapsed -= this.elapsedWrapSeconds;
        }
        gait.elapsedSeconds = elapsed;
        if (gait.attackElapsedSeconds >= 0F) {
            gait.attackElapsedSeconds += step;
            if (gait.attackElapsedSeconds >= this.tuning.attackDurationSeconds()) {
                gait.attackElapsedSeconds = -1F;
            }
        }

        // Horizontal speed only, and in the rig's own frame rather than the owner's — the rotation is
        // the first thing done with a sample, so everything downstream of it is already expressed in
        // the frame the bones were modelled in. Body yaw rate is deliberately ignored: a model that
        // stepped because it turned would shuffle forever while tracking a moving camera, and turning in
        // place is a different gait rather than a slower walk. Squared in double so a caller that
        // skipped withoutTeleports() cannot overflow the sum before the clamp below catches it.
        final double forward = binding.forwardSpeedInRigFrame(driver);
        final double side = binding.sideSpeedInRigFrame(driver);
        final float maxSpeed = this.tuning.maxGaitSpeedBlocksPerTick();
        final double planar = Math.sqrt(forward * forward + side * side);
        float planarSpeed = (float) planar;
        if (planarSpeed > maxSpeed) {
            planarSpeed = maxSpeed;
        }

        final boolean moving = planarSpeed >= this.tuning.movementDeadbandBlocksPerTick();

        // Which way round the stride runs: the cosine between travel and the rig's forward axis. Taken
        // from the unclamped pair on purpose — the speed clamp scales the vector, and scaling cannot
        // change a direction. The finiteness guard is for the one case the clamp exists for: a caller
        // that handed over a near-MAX_VALUE speed can overflow the rotated component to infinity, and
        // infinity over infinity is NaN. Falling back to the identity keeps a guard from inventing a
        // direction nobody asked for.
        gait.strideBias = moving && Double.isFinite(planar) && planar > 0D
                ? (float) (forward / planar)
                : 1F;

        if (moving) {
            // Distance, not time. The phase is unsigned: a backpedalling model runs its cycle forward,
            // matching the convention that limb swing is driven by ground covered rather than by heading.
            // A signed cycle needs a backward gait to be worth having, and this layer does not have one.
            final float blocks = planarSpeed * step * TICKS_PER_SECOND;
            final float maxAdvance = this.tuning.maxPhaseAdvancePerStep();
            float advance = blocks / profile.strideLength();
            if (advance > maxAdvance) {
                advance = maxAdvance;
            }
            final float advanced = gait.phase + advance;
            gait.phase = advanced - (float) Math.floor(advanced);
        }

        final float amplitudeTarget = moving
                ? Math.min(1F, planarSpeed / this.tuning.fullStrideSpeedBlocksPerTick())
                : 0F;
        gait.amplitude =
                ramp(gait.amplitude, amplitudeTarget, step / this.tuning.amplitudeBlendSeconds());

        // How far past walking pace the model is. Clamped at both ends, so everything at or below
        // walking pace is bit-for-bit what it was before there was a second amplitude. It rides the
        // amplitude's own blend duration because it is an amplitude: a separate key would be a second
        // number describing one ramp, and the two could then disagree about how fast a leg widens.
        final float sprintTarget = moving
                ? clampUnit((planarSpeed - this.tuning.fullStrideSpeedBlocksPerTick())
                        / this.tuning.sprintSpeedBandBlocksPerTick())
                : 0F;
        gait.sprintBlend =
                ramp(gait.sprintBlend, sprintTarget, step / this.tuning.amplitudeBlendSeconds());

        gait.airborneBlend = ramp(gait.airborneBlend, gait.airborne ? 1F : 0F,
                step / this.tuning.airborneBlendSeconds());
        gait.sneakBlend = ramp(gait.sneakBlend, gait.sneaking ? 1F : 0F,
                step / this.tuning.sneakBlendSeconds());

        write(binding, profile, gait, pose);
    }

    /**
     * Moves {@code current} toward {@code target} by at most {@code maxChange}, snapping on arrival.
     *
     * <p>A linear ramp rather than an exponential approach, for three reasons. It reaches the target in
     * exactly the configured time, so "blends in over a fifth of a second" is a statement about the code
     * rather than about a time constant. It is monotone, so a blend never overshoots or oscillates. And
     * it arrives <em>exactly</em>, which is what lets {@link WalkCycleState#isSettled()} compare for
     * equality instead of guessing at an epsilon. An exponential approach would also cost a
     * {@code Math.exp} per blend per step to be frame-rate independent, and buys nothing visible on a
     * weight that is already multiplying a small angle.
     */
    private static float ramp(float current, float target, float maxChange) {
        final float delta = target - current;
        if (delta > maxChange) {
            return current + maxChange;
        }
        if (delta < -maxChange) {
            return current - maxChange;
        }
        return target;
    }

    /**
     * {@code value} held to {@code [0, 1]}.
     *
     * <p>Both bounds are the definition of a fraction rather than a choice, which is why they are written
     * here instead of configured: the sprint blend below walking pace is negative arithmetic rather than
     * a negative sprint, and above sprinting pace there is nothing further to blend toward.
     */
    private static float clampUnit(float value) {
        if (value <= 0F) {
            return 0F;
        }
        return value >= 1F ? 1F : value;
    }

    /**
     * Evaluates every bound bone at the current phase and writes it.
     *
     * <p>Writes are absolute, and only bound bones are touched — that is the contract that lets the
     * additive physics layer run afterwards on the same pose.
     */
    private void write(
            @NotNull LocomotionBinding binding,
            @NotNull GaitProfile profile,
            @NotNull WalkCycleState gait,
            @NotNull Pose pose
    ) {
        final float cycle = TAU * gait.phase;
        final float opposed = cycle + TAU * this.tuning.counterPhaseCycles();

        // Airborne scales the whole cycle out. The phase keeps advancing underneath, so landing resumes
        // the stride where the ground would have put it rather than where the jump interrupted it.
        final float weight = gait.amplitude * (1F - gait.airborneBlend);

        final float sway = weight * profile.torsoSway();
        final float lean = sway * this.tuning.torsoLeanRadians();
        final float bob = sway * this.tuning.torsoBobRadians()
                * (float) Math.sin(this.tuning.torsoBobHarmonic() * cycle);
        final float roll = sway * this.tuning.torsoSwayRadians() * (float) Math.sin(cycle);
        final float twist = -sway * this.tuning.torsoTwistRadians() * (float) Math.sin(cycle);
        final float crouch =
                gait.sneakBlend * this.tuning.sneakTorsoPitchRadians() * profile.sneakDepth();

        // Idle motion. Unconditional and independent of the gait: it is what keeps a standing model —
        // or a legless one — from reading as a prop. Driven off elapsed time rather than off the phase,
        // because a resting model's phase does not advance.
        final float breath = profile.breathDepth() * this.tuning.breathRadians()
                * (float) Math.sin(TAU * this.tuning.breathRateHz() * gait.elapsedSeconds);

        if (binding.isBound(GaitRole.TORSO)) {
            pose.set(binding.boneOf(GaitRole.TORSO), crouch + lean + bob + breath, twist, roll);
        }
        if (binding.isBound(GaitRole.HEAD)) {
            // The head cancels a fraction of whatever the torso is doing, so the model keeps looking
            // where it is going while walking. Crouch is deliberately not cancelled: it belongs to
            // the upper body, while the leg roots counter-rotate it below.
            final float cancel = profile.headStabilise();
            pose.set(binding.boneOf(GaitRole.HEAD),
                    -cancel * (lean + bob),
                    -cancel * twist,
                    -cancel * roll);
        }

        final float legScale = weight * profile.legSwing();

        // One swing amplitude interpolated between two configured ones, rather than one scaled. At a
        // sprint blend of zero this is exactly legSwingRadians, which is what keeps everything at and
        // below walking pace unchanged.
        final float walkSwing = this.tuning.legSwingRadians();
        final float onePieceSwing =
                walkSwing + gait.sprintBlend * (this.tuning.sprintLegSwingRadians() - walkSwing);
        final float legCrouch = binding.isBound(GaitRole.TORSO) ? -crouch : 0F;

        writeLeg(binding, profile, gait, pose, legScale, onePieceSwing, cycle,
                GaitRole.LEFT_THIGH, GaitRole.LEFT_SHIN, GaitRole.LEFT_FOOT, legCrouch);
        writeLeg(binding, profile, gait, pose, legScale, onePieceSwing, opposed,
                GaitRole.RIGHT_THIGH, GaitRole.RIGHT_SHIN, GaitRole.RIGHT_FOOT, legCrouch);

        // Arms oppose their own side's leg: the left arm travels with the right leg and vice versa. That
        // is what cancels the angular momentum a stride generates, and a model whose arms swing in phase
        // with its legs reads as a toy soldier.
        final float armScale = weight * profile.armSwing();
        writeArm(binding, profile, gait, pose, armScale, opposed,
                GaitRole.LEFT_UPPER_ARM, GaitRole.LEFT_FOREARM, GaitRole.LEFT_HAND);
        writeArm(binding, profile, gait, pose, armScale, cycle,
                GaitRole.RIGHT_UPPER_ARM, GaitRole.RIGHT_FOREARM, GaitRole.RIGHT_HAND);
        writeAttack(binding, gait, pose);
    }

    /**
     * One leg: either a two-segment IK solve or a plain swing, chosen by whether a shin is bound, plus
     * the foot that may hang off either of them.
     *
     * <p>The kneed path places a foot target in the hip's own plane and lets {@link TwoBoneIk} find the
     * angles. The target is clamped twice, and the order is load-bearing: the floor on {@code down}
     * first, so a lifted foot cannot rise into the hip, then the radius, because <em>that</em> is the
     * clamp protecting the straight-leg singularity and it has to be the one that holds.
     *
     * <p>Both paths end by writing the foot if one is bound, and both reach {@link #footPitch} with the
     * same two angles — the one-piece path simply contributes a zero shin, so an absent segment drops out
     * of the chain sum rather than needing a branch. That is what makes a foot independent of the knee.
     *
     * @param swingRadians the one-piece swing amplitude for this step, already interpolated between the
     *                     walk and sprint calibrations. Unused on the kneed path, where the thigh's angle
     *                     is a result rather than an amplitude
     */
    private void writeLeg(
            @NotNull LocomotionBinding binding,
            @NotNull GaitProfile profile,
            @NotNull WalkCycleState gait,
            @NotNull Pose pose,
            float legScale,
            float swingRadians,
            float angle,
            @NotNull GaitRole thighRole,
            @NotNull GaitRole shinRole,
            @NotNull GaitRole footRole,
            float legCrouch
    ) {
        if (!binding.isBound(thighRole)) {
            return;
        }
        final int thighBone = binding.boneOf(thighRole);

        // The bias turns the cycle round for a rig travelling against its own forward axis. Applied to
        // the sine alone because −sin(θ) is sin(−θ): mirroring the fore-aft term is the same cycle read
        // backward, whereas mirroring the cosine as well would also invert the foot's lift.
        final float sine = gait.strideBias * (float) Math.sin(angle);
        final float cosine = (float) Math.cos(angle);

        final float thighPitch;
        final float shinPitch;

        if (!binding.isBound(shinRole)) {
            // One-piece leg: nothing to solve for, so it simply swings about the hip.
            thighPitch = legScale * swingRadians * sine;
            shinPitch = 0F;
            pose.set(thighBone, thighPitch + legCrouch, 0F, 0F);
        } else {
            final float thigh = binding.thighLength();
            final float shin = binding.shinLength();
            final float reach = thigh + shin;

            float forward =
                    legScale * profile.strideLength() * this.tuning.strideToFootReach() * sine;

            // The foot lifts when it is travelling fastest, which is a quarter cycle from either
            // extreme — i.e. where the cosine peaks, and where the sine that drives `forward` is zero.
            // That phasing is what makes the planted half of the cycle stay planted.
            final float lift = legScale * reach * this.tuning.footLiftFraction()
                    * Math.max(0F, cosine);
            float down = this.tuning.restingLegExtension() * reach - lift;

            final float minDown = this.tuning.minIkDownFraction() * reach;
            if (down < minDown) {
                down = minDown;
            }

            final float maxRadius = this.tuning.maxIkReachFraction() * reach;
            final float radiusSquared = down * down + forward * forward;
            if (radiusSquared > maxRadius * maxRadius) {
                final float scale = maxRadius / (float) Math.sqrt(radiusSquared);
                down *= scale;
                forward *= scale;
            }

            final TwoBoneIk.Result solved = TwoBoneIk.solve(
                    thigh, shin, down, forward, binding.kneeBendSign(), this.ikTolerance);
            thighPitch = solved.upperAngle();
            shinPitch = solved.lowerAngle();
            pose.set(thighBone, thighPitch + legCrouch, 0F, 0F);
            pose.set(binding.boneOf(shinRole), shinPitch, 0F, 0F);
        }

        if (binding.isBound(footRole)) {
            pose.set(binding.boneOf(footRole),
                    footPitch(legScale, sine, cosine, thighPitch, shinPitch), 0F, 0F);
        }
    }

    /**
     * The parent-relative pitch that holds a foot flat against the ground and rolls it over the swing.
     *
     * <p>Two terms, and they are scaled differently on purpose — see the class note. The cancellation is
     * unconditional, because a standing leg's chain does not sum to zero and a standing model's soles
     * have to be flat. The ground pitch rides {@code legScale}, because it is part of the stride.
     *
     * <p>The ground pitch is shaped by {@code max(0, cos)}, which is nonzero over exactly the half of the
     * cycle in which the foot is off the ground. So it is <strong>identically zero through the whole
     * stance half</strong> rather than merely small there, which is the property that matters: a planted
     * foot that rolls is a foot sliding through the floor. Within the swing it is the plantarflexion of
     * the push-off decaying into the dorsiflexion that presents a heel to land on, and it returns to zero
     * at both ground contacts, so the whole function is continuous across them.
     */
    private float footPitch(
            float legScale,
            float sine,
            float cosine,
            float thighPitch,
            float shinPitch
    ) {
        final float swingWindow = Math.max(0F, cosine);
        final float groundPitch = swingWindow
                * (this.tuning.footToeOffRadians() * Math.max(0F, -sine)
                        - this.tuning.footHeelStrikeRadians() * Math.max(0F, sine));
        return -(thighPitch + shinPitch) + legScale * SWING_LOBE_SCALE * groundPitch;
    }

    /**
     * One arm: a swing on the upper segment, a rectified curl on the forearm, and a hand held as level as
     * the profile asks for.
     *
     * <p>No IK. A walking arm's hand is not constrained to anything — there is no ground for it to avoid
     * sliding against — so solving for a hand position would invent a constraint and then pay to satisfy
     * it. The curl is {@code (1 + sin)/2} so it is never negative and peaks with the arm forward: an
     * elbow folds one way, and in the opposite direction to a knee, which is where
     * {@link LocomotionBinding#elbowBendSign()} comes from. The bias never leaves {@code [-1, 1]}, so
     * biasing the sine cannot push that rectified term negative.
     */
    private void writeArm(
            @NotNull LocomotionBinding binding,
            @NotNull GaitProfile profile,
            @NotNull WalkCycleState gait,
            @NotNull Pose pose,
            float armScale,
            float angle,
            @NotNull GaitRole upperRole,
            @NotNull GaitRole lowerRole,
            @NotNull GaitRole handRole
    ) {
        if (!binding.isBound(upperRole)) {
            return;
        }
        final float swing = gait.strideBias * (float) Math.sin(angle);
        final float upperPitch = armScale * this.tuning.armSwingRadians() * swing;
        setArmAxis(pose, binding, upperRole, upperPitch);

        float lowerPitch = 0F;
        if (binding.isBound(lowerRole)) {
            final float curl = armScale * this.tuning.forearmCurlRadians() * 0.5F * (1F + swing);
            lowerPitch = binding.elbowBendSign() * curl;
            setArmAxis(pose, binding, lowerRole, lowerPitch);
        }

        if (binding.isBound(handRole)) {
            // A fraction of the chain cancelled rather than all of it. Zero leaves the hand welded to
            // whatever it hangs from, which is what the pose's own zero already means — so the identity
            // case costs nothing and needs no branch.
            setHandAxis(pose, binding, handRole,
                    -profile.handStabilise() * (upperPitch + lowerPitch));
        }
    }

    /**
     * Adds the generic primary-hand swing on top of the walk pose. Only the conventional right-arm
     * roles are used; rigs without one of them simply receive the portion they can represent.
     */
    private void writeAttack(
            @NotNull LocomotionBinding binding,
            @NotNull WalkCycleState gait,
            @NotNull Pose pose
    ) {
        if (gait.attackElapsedSeconds < 0F) {
            return;
        }
        final float progress = Math.min(1F,
                gait.attackElapsedSeconds / this.tuning.attackDurationSeconds());
        final float envelope = (float) Math.sin(Math.PI * progress);
        if (binding.isBound(GaitRole.RIGHT_UPPER_ARM)) {
            addArmAxis(pose, binding, GaitRole.RIGHT_UPPER_ARM,
                    this.tuning.attackUpperArmRadians() * envelope);
        }
        if (binding.isBound(GaitRole.RIGHT_FOREARM)) {
            addArmAxis(pose, binding, GaitRole.RIGHT_FOREARM,
                    binding.elbowBendSign() * this.tuning.attackForearmRadians() * envelope);
        }
    }

    private static void setArmAxis(
            @NotNull Pose pose,
            @NotNull LocomotionBinding binding,
            @NotNull GaitRole role,
            float value
    ) {
        final int bone = binding.boneOf(role);
        switch (binding.armSwingAxis()) {
            case PITCH -> pose.set(bone, value, 0F, 0F);
            case YAW -> pose.set(bone, 0F, value, 0F);
            case ROLL -> pose.set(bone, 0F, 0F, value);
        }
    }

    private static void addArmAxis(
            @NotNull Pose pose,
            @NotNull LocomotionBinding binding,
            @NotNull GaitRole role,
            float value
    ) {
        final int bone = binding.boneOf(role);
        switch (binding.armSwingAxis()) {
            case PITCH -> pose.add(bone, value, 0F, 0F);
            case YAW -> pose.add(bone, 0F, value, 0F);
            case ROLL -> pose.add(bone, 0F, 0F, value);
        }
    }

    private static void setHandAxis(
            @NotNull Pose pose,
            @NotNull LocomotionBinding binding,
            @NotNull GaitRole role,
            float value
    ) {
        final int bone = binding.boneOf(role);
        switch (binding.handSwingAxis()) {
            case PITCH -> pose.set(bone, value, 0F, 0F);
            case YAW -> pose.set(bone, 0F, value, 0F);
            case ROLL -> pose.set(bone, 0F, 0F, value);
        }
    }
}
