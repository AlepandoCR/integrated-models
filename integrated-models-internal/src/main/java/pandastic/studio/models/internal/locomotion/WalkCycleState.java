package pandastic.studio.models.internal.locomotion;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.locomotion.GaitState;
import pandastic.studio.models.api.locomotion.GaitTuning;
import pandastic.studio.models.api.locomotion.LocomotionBinding;

/**
 * The seven scalars and two flags {@link WalkCycleSolver} carries between steps.
 *
 * <h2>No arrays, and that is the point</h2>
 * The angular physics state needs flat per-bone buffers because it integrates a differential equation
 * per bone and has to remember each bone's velocity. A gait does not: it is a closed-form function of one
 * phase, so the entire per-model memory is scalar and the step path allocates nothing at all. The
 * scratch-buffers-as-fields discipline the physics state follows is still honoured here — there simply
 * is nothing left to buffer once the limbs are solved from locals.
 *
 * <p>Fields are package-private and written directly by the solver. That is the same arrangement
 * {@code AngularPhysicsState} uses, and for the same reason: accessors on a per-step hot path buy nothing
 * when the only writer is one class in the same package, and the state's invariants are maintained by
 * that solver rather than by this type.
 *
 * <p>The one configured value a state keeps is {@link GaitTuning#settledAmplitude()}, because
 * {@link #isSettled()} is the only question this type answers on its own. It is copied in at construction
 * rather than read from a live snapshot, so that a state and the solver stepping it can never disagree
 * about what "settled" means — a reload rebuilds both.
 */
final class WalkCycleState implements GaitState {

    /** Pose width this state was created for, so a mismatched binding is caught instead of writing wild. */
    private final int boneCount;

    /** Amplitude below which the walk cycle counts as no longer contributing. */
    private final float settledAmplitude;

    /** Position in the walk cycle, {@code [0, 1)}. Advanced by distance travelled. */
    float phase;

    /** How strongly the cycle is expressed, {@code [0, 1]}. Eased toward the owner's speed. */
    float amplitude;

    /** How airborne the model is, {@code [0, 1]}. Eased toward {@link #airborne}. */
    float airborneBlend;

    /** How crouched the model is, {@code [0, 1]}. Eased toward {@link #sneaking}. */
    float sneakBlend;

    /**
     * How far between walking pace and sprinting pace the model is, {@code [0, 1]}.
     *
     * <p>Eased rather than computed afresh each step, and eased over the same configured duration as
     * {@link #amplitude} because it <em>is</em> an amplitude: crossing from the walk swing to the sprint
     * swing in one step would snap the legs wider in a single frame, which is the same defect the
     * amplitude ramp exists to prevent one level up.
     *
     * <p>Deliberately absent from {@link #isSettled()}. It only ever multiplies the leg swing, and the
     * leg swing is already multiplied by {@link #amplitude}; a model whose amplitude has decayed below
     * the settled threshold is contributing nothing for a lingering sprint blend to change.
     */
    float sprintBlend;

    /**
     * Which way round the stride runs, {@code [-1, 1]} — the cosine of the angle between the direction
     * of travel and the rig's own forward axis.
     *
     * <p>Derived from the driver sample every step rather than carried across them, and kept here rather
     * than passed down the write path because the evaluation reaches four limbs through two helpers and
     * threading it through every signature buys nothing a field does not.
     *
     * <p>Seeded at {@code 1}, which is the identity: a model that has never been driven is treated as
     * facing the way it walks, so the rest pose a standing model holds is the same one it would hold
     * after walking forward and stopping.
     */
    float strideBias = 1F;

    /** Wall time stepped, wrapped at a whole number of breathing cycles. */
    float elapsedSeconds;

    /** Elapsed time in the current attack swing, or a negative value when none is active. */
    float attackElapsedSeconds = -1F;

    /** The requested airborne stance, set by the owner's thread. */
    boolean airborne;

    /** The requested crouched stance. */
    boolean sneaking;

    WalkCycleState(@NotNull LocomotionBinding binding, @NotNull GaitTuning tuning) {
        this.boneCount = binding.boneCount();
        this.settledAmplitude = tuning.settledAmplitude();
    }

    /** The pose width this state expects. */
    int boneCount() {
        return this.boneCount;
    }

    @Override
    public void reset() {
        this.phase = 0F;
        this.amplitude = 0F;
        this.airborneBlend = 0F;
        this.sneakBlend = 0F;
        this.sprintBlend = 0F;
        // The identity rather than zero: a bias of zero is "travelling straight across the rig's
        // forward axis", which is a real state and emphatically not a stand.
        this.strideBias = 1F;
        this.elapsedSeconds = 0F;
        this.attackElapsedSeconds = -1F;
        this.airborne = false;
        this.sneaking = false;
    }

    @Override
    public float phase() {
        return this.phase;
    }

    @Override
    public float amplitude() {
        return this.amplitude;
    }

    @Override
    public float airborneBlend() {
        return this.airborneBlend;
    }

    @Override
    public float sneakBlend() {
        return this.sneakBlend;
    }

    @Override
    public float elapsedSeconds() {
        return this.elapsedSeconds;
    }

    @Override
    public boolean isAirborne() {
        return this.airborne;
    }

    @Override
    public void setAirborne(boolean airborne) {
        this.airborne = airborne;
    }

    @Override
    public boolean isSneaking() {
        return this.sneaking;
    }

    @Override
    public void setSneaking(boolean sneaking) {
        this.sneaking = sneaking;
    }

    @Override
    public void triggerAttack() {
        this.attackElapsedSeconds = 0F;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The two blend comparisons are exact float equality, which is safe here rather than sloppy:
     * {@link WalkCycleSolver} ramps linearly and snaps to the target on the step that would overshoot it,
     * so a resolved blend holds the target's bit pattern exactly. Amplitude needs an epsilon because it
     * chases a continuously varying speed and may legitimately sit just above zero.
     */
    @Override
    public boolean isSettled() {
        return this.amplitude < this.settledAmplitude
                && this.attackElapsedSeconds < 0F
                && this.airborneBlend == (this.airborne ? 1F : 0F)
                && this.sneakBlend == (this.sneaking ? 1F : 0F);
    }

    @Override
    public String toString() {
        return "WalkCycleState[phase=" + this.phase + ", amplitude=" + this.amplitude
                + ", airborne=" + this.airborneBlend + ", sneak=" + this.sneakBlend + "]";
    }
}
