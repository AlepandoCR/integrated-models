package pandastic.studio.models.internal.config;

import pandastic.studio.core.service.api.config.ConfigFormat;
import pandastic.studio.core.service.api.config.ConfigValue;
import pandastic.studio.core.service.api.config.Configurable;

/**
 * Declaration of {@code physics.yml}: everything the angular solver and the rig compiler measure
 * themselves against.
 *
 * <p>Three groups of values live in one file on purpose. The calibration coefficients turn an author's
 * dimensionless {@code 0..1} knob into a physical rate, and the profile bounds decide which knob
 * positions a rig is allowed to ask for — so the stability of the integrator is a statement about
 * <em>both</em> at once. {@code sqrt(stiffnessToRate / minMass) / substepRateHz} has to stay under the
 * integrator's bound, and splitting the two halves across two files would let an operator edit one side
 * of that inequality without ever seeing the other.
 *
 * <p>The third group is the {@code default*} coefficients: the knob positions a rig file inherits for
 * every coefficient it omits. They sit beside the bounds for the same reason — the bounds have to admit
 * the defaults, since a file is entitled to mention no coefficient at all and the profile that gets
 * built from the defaults alone is then validated against them. {@link ConfigMapper} enforces that
 * here rather than letting it surface as one confusing warning per bone at rig-load time.
 *
 * <p>Nothing reads this interface at runtime. The annotation processor turns it into
 * {@code PhysicsConfig}, which is what actually touches the disk, and
 * {@link ConfigMapper} turns a loaded {@code PhysicsConfig} into the immutable api value objects the
 * solver consumes. The declaration exists to hold the keys, the comments and the defaults in one
 * place that cannot drift from the file.
 *
 * @see ConfigMapper
 * @see ConfigSnapshot
 */
@Configurable(name = "PhysicsConfig", format = ConfigFormat.YAML)
public interface PhysicsSettings {

    /**
     * Author-facing stiffness to {@code rad*s^-2*rad^-1}.
     *
     * <p>Raising it raises the undamped frequency of every spring bone by its square root, which is
     * what makes it the value the integrator's stability bound is written against: the fastest legal
     * spring is {@code sqrt(stiffnessToRate / minMass)} rad/s, and that times the substep has to stay
     * below 2.
     *
     * @return the conversion factor, in rad*s^-2 per unit of stiffness
     */
    @ConfigValue(comment = "Converts an author's 0..1 stiffness into a spring rate. Higher means faster, tighter springs; it is the value the stability bound is written against.")
    default float stiffnessToRate() {
        return 260F;
    }

    /**
     * Author-facing damping to {@code rad*s^-1}, calibrated for a damping ratio near {@code 0.7} at
     * mid-range tuning.
     *
     * <p>This one carries the tighter of the two stability bounds. The velocity update is explicit, so
     * a step multiplies velocity by {@code (1 - damping * dampingToRate * h / mass)}; past {@code -1}
     * the velocity flips sign and grows without limit. The default makes {@code dampingToRate * h}
     * exactly 2 at 60 Hz, which is precisely what reduces the rig-level rule to "damping below mass" —
     * change this and that rule silently stops being the rule.
     *
     * @return the conversion factor, in rad*s^-1 per unit of damping
     */
    @ConfigValue(comment = "Converts an author's 0..1 damping into a velocity decay rate. At the default substep rate this makes the rig rule exactly 'damping must stay below mass'.")
    default float dampingToRate() {
        return 120F;
    }

    /**
     * Author-facing gravity to the pendulum's {@code g/L}, applied outside the mass division.
     *
     * <p>Outside the division because a pendulum's period does not depend on its mass; dividing here
     * would make a heavy bone hang more slowly, which is wrong and reads as the rig being broken.
     *
     * @return the conversion factor, in rad*s^-2 per unit of gravity
     */
    @ConfigValue(comment = "Converts an author's gravity into a pendulum rate. Applied outside the mass division, because a pendulum's period does not depend on its mass.")
    default float gravityToRate() {
        return 40F;
    }

    /**
     * Owner movement, in blocks per tick, to drag force on a bone.
     *
     * <p>This is the knob that decides how much a cape notices that its wearer is running. It is
     * deliberately not tied to the gravity rate: wind is an external force and gravity is a restoring
     * one, and a rig author tunes them against different-looking failures.
     *
     * @return the conversion factor, in radians of drag per block per tick of owner movement
     */
    @ConfigValue(comment = "Converts the owner's movement in blocks per tick into drag on a bone. Raise it to make cloth react harder to running.")
    default float windToRadians() {
        return 60F;
    }

    /**
     * Effective lever arm, in blocks, that turns body yaw rate into a tangential speed at a bone.
     *
     * <p>A bone hanging off a turning body is dragged sideways even when the body's centre does not
     * move, and this is the radius the solver pretends it hangs at. One radius for every bone is a
     * simplification; measuring the real one per bone would cost a square root per bone per substep
     * and buy motion nobody can tell apart.
     *
     * @return the lever arm, in blocks
     */
    @ConfigValue(comment = "Lever arm in blocks used to turn the owner's turning rate into sideways drag. One shared radius for every bone, by design.")
    default float turnToSpeed() {
        return 2F;
    }

    /**
     * Fixed substep rate of the integrator, in hertz.
     *
     * <p><strong>This is a stability requirement, not a smoothness one.</strong> Semi-implicit Euler
     * survives only while {@code omega * h < 2}, and the legal tuning reaches about 73 rad/s, so a
     * coarser step does not make the motion choppier — it makes it diverge. Smoothness above the tick
     * rate comes from the client's interpolation, never from stepping faster.
     *
     * <p>Changing it invalidates every live simulation state, because a state is built against a step
     * size and its stability bound was checked at construction. {@link ConfigService} reports that on
     * the reload result rather than hiding it; see {@link ConfigReloadResult#requiresSimulationRebuild()}.
     *
     * @return the substep rate, in hertz
     */
    @ConfigValue(comment = "Fixed integrator substep rate in hertz. A stability requirement, not a smoothness one: a coarser step does not look choppier, it diverges. Changing it forces every live simulation state to be rebuilt.")
    default int substepRateHz() {
        return 60;
    }

    /**
     * Largest number of substeps one simulation step is allowed to run.
     *
     * <p>The ceiling is what stops a stalled server from paying back its whole missed interval at
     * once: after a two-second hitch the honest answer is 120 substeps, and running them would turn one
     * late step into a far later one. Dropping the remainder loses a little settling and keeps the loop
     * able to catch up.
     *
     * @return the substep ceiling per step
     */
    @ConfigValue(comment = "Largest number of substeps a single simulation step may run. Caps the catch-up work after a server hitch instead of letting one late step become a later one.")
    default int maxSubsteps() {
        return 4;
    }

    /**
     * Per-substep angular change below which a bone counts as having stopped moving, in radians.
     *
     * <p>It has to stay at or below the renderer's dirty-tracking epsilon in {@code render.yml}:
     * a rig the solver calls settled must be a rig the renderer calls silent, or emission never stops
     * for a model that is not moving. {@link ConfigMapper} enforces that across the two files.
     *
     * @return the rest threshold, in radians per substep
     */
    @ConfigValue(comment = "Per-substep angular change in radians below which a bone counts as at rest. Must stay at or below boneDirtyEpsilonRadians in render.yml, or a settled model never stops emitting.")
    default float restEpsilonRadians() {
        return 1.0E-5F;
    }

    /**
     * Lower bound on a physics profile's mass.
     *
     * <p>This is the other half of the stiffness stability bound: the fastest legal spring is the
     * stiffest one on the lightest bone, so lowering this is exactly as dangerous as raising
     * {@link #stiffnessToRate()}.
     *
     * @return the smallest mass a rig may declare
     */
    @ConfigValue(comment = "Smallest mass a rig may declare. Lowering it is exactly as destabilising as raising stiffnessToRate, because the fastest spring is the stiffest one on the lightest bone.")
    default float minMass() {
        return 0.05F;
    }

    /**
     * Upper bound on a physics profile's mass.
     *
     * @return the largest mass a rig may declare
     */
    @ConfigValue(comment = "Largest mass a rig may declare. A sanity ceiling: a heavier bone simply stops responding to anything.")
    default float maxMass() {
        return 10F;
    }

    /**
     * Upper bound on a physics profile's gravity coefficient.
     *
     * @return the largest gravity a rig may declare
     */
    @ConfigValue(comment = "Largest gravity coefficient a rig may declare, as a multiple of the calibrated pendulum rate.")
    default float maxGravity() {
        return 4F;
    }

    /**
     * Upper bound on a physics profile's wind coefficient.
     *
     * @return the largest wind coefficient a rig may declare
     */
    @ConfigValue(comment = "Largest wind coefficient a rig may declare, as a multiple of the calibrated drag rate.")
    default float maxWind() {
        return 4F;
    }

    /**
     * Upper bound on a physics profile's collision radius, in blocks.
     *
     * @return the largest collision radius a rig may declare, in blocks
     */
    @ConfigValue(comment = "Largest collision radius a rig may declare, in blocks. The clearance a bone is assumed to have before it would pass through the body it hangs from.")
    default float maxCollisionRadius() {
        return 2F;
    }

    /**
     * Blocks of declared clearance to radians of permitted swing.
     *
     * <p>Calibrated so a strip at the default clearance stops against its own thickness rather than
     * passing through the body it hangs from. It is a geometric fudge, not a measurement: the rig
     * layer has no body to intersect against, so clearance is spent as angle instead.
     *
     * @return the conversion factor, in radians of swing per block of clearance
     */
    @ConfigValue(comment = "Converts a bone's declared clearance in blocks into radians of permitted swing. A geometric approximation: the rig layer has no body to intersect against.")
    default float collisionRadiusToRadians() {
        return 1.7F;
    }

    /**
     * Half-width, in degrees, of the axis limit applied to an axis a rig omits.
     *
     * <p>A rig that mentions no limit still needs one, because an unbounded bone is how a model ends
     * up inside out. 45 degrees either way is loose enough not to clip a deliberate pose and tight
     * enough that a runaway bone is visibly held.
     *
     * @return the default symmetric half-width, in degrees
     */
    @ConfigValue(comment = "Half-width in degrees of the axis limit used for any axis a rig leaves out. An unbounded bone is how a model ends up inside out, so there is always a limit.")
    default float defaultLimitDegrees() {
        return 45F;
    }

    /**
     * Stiffness a rig file inherits when it declares none.
     *
     * <p>Every {@code default*} key below is read back by the rig loader as the fall-back for an omitted
     * coefficient, so a bone whose physics block says only {@code kind: pendulum} is tuned entirely by
     * these. They are calibration, not placeholders.
     *
     * @return the seeded stiffness, {@code 0..1}
     */
    @ConfigValue(comment = "Stiffness a bone inherits when its rig file declares none, 0..1. Every default* key below is the tuning of any bone that does not override it.")
    default float defaultStiffness() {
        return 0.08F;
    }

    /**
     * Damping a rig file inherits when it declares none.
     *
     * <p>Small on purpose: the seed has to stay well below the stability bound for the <em>lightest</em>
     * mass a rig might also inherit, or a bone that mentions neither value is clamped and warned about
     * by the loader before the author has typed anything at all.
     *
     * @return the seeded damping, {@code 0..1}
     */
    @ConfigValue(comment = "Damping a bone inherits when its rig file declares none, 0..1. Keep it well under the default mass: damping above mass is what the stability clamp exists to catch.")
    default float defaultDamping() {
        return 0.015F;
    }

    /**
     * Fraction of its offset a {@code drag} bone inherits keeping each step.
     *
     * @return the seeded lag, {@code 0..1}
     */
    @ConfigValue(comment = "Lag a bone inherits when its rig file declares none, 0..1. Only physics kind 'drag' reads it; every other kind ignores it.")
    default float defaultLag() {
        return 0.6F;
    }

    /**
     * Mass a rig file inherits when it declares none.
     *
     * <p>Must sit within {@link #minMass()}{@code ..}{@link #maxMass()}: a profile built from the
     * defaults alone is validated against those bounds, so a seed outside them refuses every bone that
     * omits a mass.
     *
     * @return the seeded mass
     */
    @ConfigValue(comment = "Mass a bone inherits when its rig file declares none. It has to sit within minMass..maxMass, because a bone that omits every coefficient is still validated against them.")
    default float defaultMass() {
        return 1F;
    }

    /**
     * Wind coefficient a rig file inherits when it declares none.
     *
     * @return the seeded wind, up to {@link #maxWind()}
     */
    @ConfigValue(comment = "Wind coefficient a bone inherits when its rig file declares none. It has to stay within maxWind.")
    default float defaultWind() {
        return 1F;
    }

    /**
     * Gravity a {@code pendulum} inherits when its rig file declares none.
     *
     * <p>Gravity is seeded per kind rather than once, because the difference between the kinds is
     * structural rather than stylistic: a pendulum with no gravity hangs nowhere.
     *
     * @return the seeded pendulum gravity, up to {@link #maxGravity()}
     */
    @ConfigValue(comment = "Gravity a 'pendulum' bone inherits when its rig file declares none. Gravity is seeded per kind because a pendulum with no gravity hangs nowhere.")
    default float defaultPendulumGravity() {
        return 0.25F;
    }

    /**
     * Gravity a {@code spring} inherits when its rig file declares none.
     *
     * <p>Zero is what distinguishes a spring from a pendulum — a spring's rest pose is wherever the rig
     * put it. Raising this turns every spring that does not say otherwise into a pendulum, which is a
     * legitimate thing to want and a surprising thing to get by accident.
     *
     * @return the seeded spring gravity, up to {@link #maxGravity()}
     */
    @ConfigValue(comment = "Gravity a 'spring' bone inherits when its rig file declares none. Zero is what makes a spring a spring: it returns to where the rig put it instead of hanging.")
    default float defaultSpringGravity() {
        return 0F;
    }

    /**
     * Gravity a {@code cloth_strip} inherits when its rig file declares none.
     *
     * @return the seeded cloth strip gravity, up to {@link #maxGravity()}
     */
    @ConfigValue(comment = "Gravity a 'cloth_strip' bone inherits when its rig file declares none.")
    default float defaultClothStripGravity() {
        return 0.25F;
    }

    /**
     * Gravity a {@code drag} bone inherits when its rig file declares none.
     *
     * <p>Inert, and declared anyway. Exponential lag has no gravity term, so this only ever shows up as
     * the value {@code PhysicsProfile#gravity()} reports for a drag bone — but the alternative to a key
     * is a literal, and a literal is what this file exists to remove. The loader already warns an author
     * who sets {@code gravity} on a drag bone that nothing reads it.
     *
     * @return the seeded drag gravity, up to {@link #maxGravity()}
     */
    @ConfigValue(comment = "Gravity a 'drag' bone inherits when its rig file declares none. Inert: exponential lag has no gravity term, so this only ever shows up in diagnostics.")
    default float defaultDragGravity() {
        return 0.25F;
    }

    /**
     * X of the direction a bone extends along when its rig file declares no reach.
     *
     * <p>The three components are one value, and only their direction matters: the api normalises the
     * vector on the way in, so {@code [0, -2, 0]} and {@code [0, -1, 0]} are the same bone.
     *
     * @return the seeded reach x, in model space
     */
    @ConfigValue(comment = "X of the direction a bone extends along when its rig file declares no reach. Only the direction matters; the vector is normalised.")
    default float defaultReachX() {
        return 0F;
    }

    /**
     * Y of the direction a bone extends along when its rig file declares no reach.
     *
     * <p>Negative is downward in model space, which is what makes the shipped default a bone that hangs
     * — the right assumption for the hair, tails and cloth this layer exists for.
     *
     * @return the seeded reach y, in model space
     */
    @ConfigValue(comment = "Y of the direction a bone extends along when its rig file declares no reach. Negative is down, so the default -1 is a bone that hangs.")
    default float defaultReachY() {
        return -1F;
    }

    /**
     * Z of the direction a bone extends along when its rig file declares no reach.
     *
     * @return the seeded reach z, in model space
     */
    @ConfigValue(comment = "Z of the direction a bone extends along when its rig file declares no reach.")
    default float defaultReachZ() {
        return 0F;
    }

    /**
     * Hard ceiling on bones in a single rig.
     *
     * <p>Exists so a malformed rig cannot allocate unboundedly. The per-viewer bandwidth budget binds
     * long before this does, so treat it as a guard rather than as a tuning value.
     *
     * @return the largest number of bones one rig may declare
     */
    @ConfigValue(comment = "Hard ceiling on bones in a single rig. A guard against a malformed file allocating unboundedly; the bandwidth budget binds long before this does.")
    default int maxBones() {
        return 256;
    }

    /**
     * Fraction of the damping stability bound a clamped rig value is held to.
     *
     * <p>The bound is exclusive and compared in {@code float}, so clamping a file's damping to exactly
     * the bound can still land on the wrong side of it. Pulling the clamp a little under leaves room
     * for that comparison instead of trusting it.
     *
     * @return the margin, as a fraction of the bound
     */
    @ConfigValue(comment = "Fraction of the damping stability bound a clamped rig value is held to. The bound is exclusive and compared in float, so clamping to exactly the bound can still land on the wrong side.")
    default float rigStabilityMargin() {
        return 0.99F;
    }

    /**
     * Shortest reach vector a rig file may declare, in blocks.
     *
     * <p>A reach vector is normalised, which hides how short it was — so a near-zero vector is not a
     * weak direction but no direction at all, and it has to be caught before the normalisation throws
     * it away. Sits above the length the api cannot normalise, so a value that passes here always
     * normalises cleanly.
     *
     * @return the minimum length, in blocks
     */
    @ConfigValue(comment = "Shortest reach vector a rig file may declare, in blocks. Normalisation hides how short a vector was, so a near-zero one has to be refused before it is normalised away.")
    default float rigMinReachLengthBlocks() {
        return 1.0E-3F;
    }
}
