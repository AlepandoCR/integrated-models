package pandastic.studio.models.internal.physics;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.physics.PhysicsDriver;
import pandastic.studio.models.api.physics.PhysicsSolver;
import pandastic.studio.models.api.physics.PhysicsState;
import pandastic.studio.models.api.physics.PhysicsTuning;
import pandastic.studio.models.api.pose.Pose;
import pandastic.studio.models.api.rig.PhysicsProfile;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.api.rig.RigBone;
import pandastic.studio.models.api.rig.RotationAxis;

/**
 * Secondary motion solved in angle space with a damped spring, integrated at a fixed, injected rate.
 *
 * <h2>The model</h2>
 * Each simulated bone keeps a three-component angular offset. Every substep it is <em>carried</em> by
 * however far its ancestors rotated since the last substep, pulled back toward its rest pose by a
 * spring, bled off by damping, pushed by the drag of the owner's movement, and clamped to its per-axis
 * limits. Per axis, for a spring-like bone:
 *
 * <pre>
 *   driverNow   = Σ pose[ancestor][axis]  (+ angle[chainParent][axis])
 *   driverDelta = driverNow − parentPrevious
 *   θ'          = θ − driverDelta
 *   pull        = axis == PITCH ? gravity · gravityToRate : 0
 *   a           = (−stiffness · stiffnessToRate · θ' − damping · dampingToRate · v + wind) / mass
 *                 − pull · θ'
 *   v          += a · h
 *   θ           = clamp(θ' + v · h, limitMin, limitMax)
 * </pre>
 *
 * The driver enters as a positional impulse on the angle rather than as a force, because the bone is
 * being carried rather than pushed: when a head turns, its hair is left behind by exactly the angle the
 * head moved, which is what inertia looks like.
 *
 * <p>{@link pandastic.studio.models.api.rig.PhysicsKind#DRAG} replaces the spring with exponential lag
 * and no velocity term — unconditionally stable, and the right choice when ringing is unwanted.
 *
 * <h2>Every coefficient is injected, and injected through the constructor</h2>
 * The factors that turn a rig author's dimensionless {@code 0..1} coefficients into angular rates — and
 * the substep rate itself — arrive as a {@link PhysicsTuning} and are never defaulted here. Taking them
 * in the constructor rather than per call is what makes a reload safe: changing
 * {@link PhysicsTuning#substepRateHz()} invalidates every live {@link PhysicsState}, because a state is
 * sized and stability-checked against one step size when it is built. Rebuilding the solver from the new
 * snapshot and rebuilding the states with it makes that invalidation structural, instead of a rule every
 * caller has to remember.
 *
 * <p>A {@code PhysicsTuning} is immutable, so a solver built from one is still stateless and shareable:
 * a single instance may serve every model on the server, exactly as before, since all mutable data lives
 * in the {@link AngularPhysicsState} passed in.
 *
 * <h2>Semi-implicit Euler, and why the rate is not a preference</h2>
 * Velocity is updated before position, which makes the integrator symplectic and keeps a lightly damped
 * spring from gaining energy. It is stable only while {@code ω·h < 2}, and {@code ω} rises as mass
 * falls:
 *
 * <pre>
 *   ω² = stiffness · stiffnessToRate / mass + gravity · gravityToRate
 * </pre>
 *
 * At the shipped calibration and the loosest tuning the profile permits ({@code stiffness 1, mass 0.05,
 * gravity 4}) that is {@code ω ≈ 73 rad/s}, so {@code ω·h ≈ 1.22} at 60 Hz — stable — and {@code ≈ 3.66}
 * at 20 Hz, which diverges. That is why the shipped substep rate is 60 Hz rather than the tick rate, and
 * it is unrelated to how smooth the result looks: smoothness comes from the client interpolating between
 * the transforms it receives. Configuring the rate downward is therefore configuring the stability
 * margin downward, which is why {@link #requireStable} is checked against whatever rate the tuning
 * carries rather than against the shipped one.
 *
 * <p>The damping term carries a <em>second</em>, independent bound, and it is the one that actually
 * bites. The velocity update is explicit, so each step multiplies velocity by
 * {@code (1 − damping · dampingToRate · h / mass)}; past {@code −1} the velocity flips sign and grows
 * without limit. At the shipped calibration {@code dampingToRate · h} is exactly {@code 2} at 60 Hz, so
 * the bound reduces there to <strong>damping must stay below mass</strong> — and the permitted ranges
 * allow violating it by a factor of forty, so this is a real constraint rather than a theoretical one.
 * Halve the substep rate and the same bound halves with it.
 *
 * <p>{@link #requireStable} refuses both cases at rig compile time instead of letting them produce NaN
 * at runtime; {@link #maxStableDamping} lets a loader clamp and warn instead.
 *
 * <h2>No trigonometry, no square roots, no allocation</h2>
 * The whole integration path is adds and multiplies, the drag torque is one cross product, and the only
 * scratch buffer is a field on the state. That is what lets this run over every model on the server
 * inside a 25 ms budget.
 */
public final class AngularPhysicsSolver implements PhysicsSolver {

    // The one number in this file that is not configuration is PhysicsTuning.STABILITY_LIMIT, and it is
    // declared there rather than here only because the tuning's own javadoc has to explain it. It stays
    // a constant deliberately: it is the stability bound of semi-implicit Euler — the step at which the
    // method's amplification factor leaves the unit disc, the same 2 for every calibration anyone could
    // write — and not a preference. Exposed as a setting it would read "permit NaN", and what it would
    // permit is not a louder or twitchier model but a bone whose rotation goes infinite within a second,
    // then NaN, and reaches the client as a bone that disappears or swallows the screen. The tunables
    // are the two factors whose product with h is compared against it.

    private final @NotNull PhysicsTuning tuning;

    /**
     * @param tuning the calibration and step size this solver integrates with. Immutable, so the solver
     *               stays shareable; replace the solver rather than the tuning to reload
     */
    public AngularPhysicsSolver(@NotNull PhysicsTuning tuning) {
        this.tuning = tuning;
    }

    @Override
    public @NotNull PhysicsState createState(@NotNull Rig rig) {
        return new AngularPhysicsState(rig, this.tuning);
    }

    @Override
    public int substepRateHz() {
        return this.tuning.substepRateHz();
    }

    @Override
    public int maxSubsteps() {
        return this.tuning.maxSubsteps();
    }

    @Override
    public void solve(
            @NotNull Rig rig,
            @NotNull PhysicsState state,
            @NotNull Pose pose,
            float deltaSeconds,
            @NotNull PhysicsDriver driver
    ) {
        if (!(state instanceof AngularPhysicsState angular)) {
            throw new IllegalArgumentException(
                    "This solver only accepts states it created, got " + state.getClass().getName());
        }
        if (angular.entryCount() == 0) {
            return;
        }
        if (pose.boneCount() != rig.boneCount()) {
            throw new IllegalArgumentException(
                    "Pose holds " + pose.boneCount() + " bones but the rig has " + rig.boneCount());
        }
        if (deltaSeconds <= 0F || !Float.isFinite(deltaSeconds)) {
            return;
        }

        angular.accumulator += deltaSeconds;

        // Hoisted out of the loop. The step size is a division derived from the rate, and this runs over
        // every axis of every simulated bone of every model on the server.
        final float substepSeconds = this.tuning.substepSeconds();
        final int maxSubsteps = this.tuning.maxSubsteps();

        float maxChange = 0F;
        int steps = 0;
        while (angular.accumulator >= substepSeconds && steps < maxSubsteps) {
            maxChange = Math.max(maxChange, integrate(angular, pose, driver, substepSeconds));
            angular.accumulator -= substepSeconds;
            steps++;
        }
        if (steps == maxSubsteps) {
            // Discard the backlog rather than carry it. Carrying guarantees that the step after a stall
            // is far larger than the integrator is stable for, which is exactly how a lag spike turns
            // into an exploded chain. Dropped time costs a little settling, not correctness.
            angular.accumulator = 0F;
        }
        angular.recordMaxChange(maxChange);

        if (steps > 0) {
            apply(angular, pose);
        }
    }

    /** One fixed substep over every entry. Returns the largest angular change it produced. */
    private float integrate(
            @NotNull AngularPhysicsState state,
            @NotNull Pose pose,
            @NotNull PhysicsDriver driver,
            float substepSeconds
    ) {
        float maxChange = 0F;
        final int entries = state.entryCount();
        final float stiffnessToRate = this.tuning.stiffnessToRate();
        final float dampingToRate = this.tuning.dampingToRate();
        final float gravityToRate = this.tuning.gravityToRate();

        for (int entry = 0; entry < entries; entry++) {
            final PhysicsProfile profile = state.profileOf(entry);
            final int slot = entry * RotationAxis.COUNT;
            final int chainParent = state.chainParentOf(entry);
            final int[] ancestors = state.ancestorsOf(entry);

            computeWind(state.windScratch, profile, driver);

            final boolean springLike = profile.isSpringLike();
            final float mass = profile.mass();
            final float restoring = profile.stiffness() * stiffnessToRate;
            final float bleed = profile.damping() * dampingToRate;
            final float gravityPull = profile.gravity() * gravityToRate;

            for (int axis = 0; axis < RotationAxis.COUNT; axis++) {
                final int index = slot + axis;
                final RotationAxis rotationAxis = RotationAxis.at(axis);

                // The driver is the total rotation this bone inherits: every ancestor's pose rotation,
                // plus the live angle of the nearest simulated ancestor. The latter is read from the
                // physics array rather than the pose because the pose is only written once per solve,
                // after all substeps — and a chain has to propagate within a substep to hold together.
                float driverNow = 0F;
                for (final int ancestor : ancestors) {
                    driverNow += pose.get(ancestor, rotationAxis);
                }
                if (chainParent >= 0) {
                    driverNow += state.angle[chainParent * RotationAxis.COUNT + axis];
                }

                final float driverDelta = driverNow - state.parentPrevious[index];
                state.parentPrevious[index] = driverNow;

                final float previous = state.angle[index];
                final float carried = previous - driverDelta;
                final float wind = state.windScratch[axis];

                final float updated;
                if (springLike) {
                    final float pull = rotationAxis == RotationAxis.PITCH ? gravityPull : 0F;
                    final float acceleration =
                            (-restoring * carried - bleed * state.velocity[index] + wind) / mass
                                    - pull * carried;
                    final float velocity = state.velocity[index] + acceleration * substepSeconds;
                    state.velocity[index] = velocity;
                    updated = carried + velocity * substepSeconds;
                } else {
                    // Exponential lag: the bone keeps a fraction of its offset each step and the rest
                    // decays. No velocity, so nothing to accumulate and nothing to diverge.
                    state.velocity[index] = 0F;
                    final float pushed =
                            carried + wind / mass * substepSeconds * substepSeconds;
                    updated = pushed * profile.lag();
                }

                final float clamped =
                        Math.clamp(updated, state.limitMin[index], state.limitMax[index]);
                state.angle[index] = clamped;

                final float change = Math.abs(clamped - previous);
                if (change > maxChange) {
                    maxChange = change;
                }
            }
        }
        return maxChange;
    }

    /**
     * The drag torque the owner's movement exerts on one bone, written into {@code out}.
     *
     * <p>The bone is treated as a rigid segment pointing along its reach vector; the apparent wind is
     * the negation of the owner's velocity plus the tangential speed that body yaw imparts at that
     * lever arm, and the torque is the cross product of the two. One cross product, no trigonometry.
     *
     * <p>The per-axis signs follow the renderer's rotation convention, in which pitch and yaw run
     * opposite to roll; they are calibrated so that drag opposes motion for a bone hanging downward
     * rather than derived from first principles.
     */
    private void computeWind(
            float[] out,
            @NotNull PhysicsProfile profile,
            @NotNull PhysicsDriver driver
    ) {
        final float scale = profile.wind();
        if (scale == 0F || !driver.isMoving()) {
            out[0] = 0F;
            out[1] = 0F;
            out[2] = 0F;
            return;
        }

        final float reachX = profile.reachX();
        final float reachY = profile.reachY();
        final float reachZ = profile.reachZ();

        final float windToRadians = this.tuning.windToRadians();

        final float spin = -driver.yawRateRadians() * this.tuning.turnToSpeed();
        final float velocityX = driver.sideSpeed() - spin * reachZ;
        final float velocityZ = -driver.forwardSpeed() + spin * reachX;

        final float windX = -velocityX * windToRadians * scale;
        final float windY = driver.verticalSpeed() * windToRadians * scale;
        final float windZ = -velocityZ * windToRadians * scale;

        out[0] = -(reachY * windZ - reachZ * windY);
        out[1] = -(reachZ * windX - reachX * windZ);
        out[2] = reachX * windY - reachY * windX;
    }

    /**
     * Adds every entry's angular offset onto the pose.
     *
     * <p>Runs once per {@code solve}, outside the substep loop: the pose is an output, and writing it
     * three times for one visible frame would be waste. Physics <em>adds</em>, because it is secondary
     * motion layered on whatever animation or locomotion already wrote — which makes resetting the pose
     * each step the caller's job, not this method's.
     */
    private static void apply(@NotNull AngularPhysicsState state, @NotNull Pose pose) {
        for (int entry = 0; entry < state.entryCount(); entry++) {
            final int slot = entry * RotationAxis.COUNT;
            pose.add(
                    state.boneOf(entry),
                    state.angle[slot],
                    state.angle[slot + 1],
                    state.angle[slot + 2]);
        }
    }

    /**
     * Refuses a profile whose spring would diverge at {@code tuning}'s step size.
     *
     * <p>Checked at rig compile time rather than tolerated at runtime, because divergence does not
     * degrade gracefully: within a second the angle is infinite, then NaN, and a NaN rotation reaches
     * the client as a bone that disappears or swallows the screen. The rig author is the only person
     * who can fix it, so they are the person who should hear about it.
     *
     * <p>Static, and taking the tuning explicitly, because the one caller is
     * {@link AngularPhysicsState}'s constructor — the check belongs to building a state against a step
     * size, and at that point there is no solver instance in hand to ask.
     *
     * @throws IllegalArgumentException if the tuning is unstable at this step size
     */
    static void requireStable(
            @NotNull PhysicsTuning tuning,
            @NotNull PhysicsProfile profile,
            @NotNull Rig rig,
            @NotNull RigBone bone
    ) {
        if (!profile.isSpringLike()) {
            // Exponential lag with a factor in 0..1 is a contraction; it cannot gain energy.
            return;
        }

        final int substepRateHz = tuning.substepRateHz();
        final float step = tuning.substepSeconds();

        // Restoring bound. At the shipped calibration and within the ranges PhysicsProfile permits this
        // peaks at omega*h = 1.22 at 60 Hz, so it cannot trigger there — it exists to catch a configured
        // substep rate low enough that the same tuning reaches 3.66 and diverges.
        final float restoringProduct = naturalFrequency(tuning, profile) * step;
        if (restoringProduct >= PhysicsTuning.STABILITY_LIMIT) {
            throw new IllegalArgumentException(
                    "Rig '" + rig.getName() + "' bone '" + bone.name() + "' is tuned past the"
                            + " integrator's restoring-force stability bound at " + substepRateHz
                            + " Hz: omega*h = " + restoringProduct + " must stay below "
                            + PhysicsTuning.STABILITY_LIMIT
                            + ". Raise mass, or lower stiffness and gravity.");
        }

        // Damping bound, which is the one that actually bites. The velocity update is explicit, so the
        // bleed factor per step is (1 - damping · dampingToRate · h / mass); once that passes -1 the
        // velocity flips sign and grows every step. Because dampingToRate · h is exactly 2 at the
        // shipped calibration and 60 Hz, the bound reduces there to the memorable form "damping must
        // stay below mass" — and the permitted ranges very much allow violating it, which is why this
        // check is not theoretical. A lower configured substep rate tightens it proportionally.
        final float dampingProduct = profile.damping() * tuning.dampingToRate() * step / profile.mass();
        if (dampingProduct >= PhysicsTuning.STABILITY_LIMIT) {
            throw new IllegalArgumentException(
                    "Rig '" + rig.getName() + "' bone '" + bone.name() + "' is over-damped past the"
                            + " integrator's stability bound at " + substepRateHz + " Hz: damping "
                            + profile.damping() + " against mass " + profile.mass() + " gives "
                            + dampingProduct + ", which must stay below " + PhysicsTuning.STABILITY_LIMIT
                            + ". Keep damping below mass.");
        }
    }

    /**
     * The largest damping a profile of this mass may carry at {@code substepRateHz}, exclusive.
     *
     * <p>Exposed so a rig loader can clamp an author's value and warn, rather than refuse the rig —
     * a model that loads with a warning beats a model that does not load.
     *
     * <p><strong>An instance method, not a static taking a factor.</strong> The bound divides by
     * {@link PhysicsTuning#dampingToRate()}, which is no longer a universal constant but a property of
     * one configured solver, and the only caller — the rig loader — already needs a solver in hand to
     * ask it for {@link #substepRateHz()}. A static overload taking a loose factor would let a loader
     * clamp against a snapshot the solver that actually runs the rig is not using, and a value clamped
     * against the wrong factor is worse than one left alone: the rig loads, reports that it was made
     * safe, and then diverges. {@code substepRateHz} stays a parameter so a loader can still ask what
     * the bound would be at another rate, which is what its diagnostics quote.
     */
    public float maxStableDamping(float mass, int substepRateHz) {
        return PhysicsTuning.STABILITY_LIMIT * mass * substepRateHz / this.tuning.dampingToRate();
    }

    /** The spring's undamped natural frequency in rad/s, as the integrator sees it under {@code tuning}. */
    static float naturalFrequency(@NotNull PhysicsTuning tuning, @NotNull PhysicsProfile profile) {
        final float squared =
                profile.stiffness() * tuning.stiffnessToRate() / profile.mass()
                        + profile.gravity() * tuning.gravityToRate();
        return (float) Math.sqrt(squared);
    }
}
