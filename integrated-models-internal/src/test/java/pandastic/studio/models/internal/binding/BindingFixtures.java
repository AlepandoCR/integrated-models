package pandastic.studio.models.internal.binding;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.locomotion.BindingBounds;
import pandastic.studio.models.api.locomotion.GaitTuning;
import pandastic.studio.models.api.physics.DriverBounds;
import pandastic.studio.models.api.physics.PhysicsTuning;
import pandastic.studio.models.api.render.CadenceBounds;
import pandastic.studio.models.api.render.EmissionCadence;
import pandastic.studio.models.api.rig.PhysicsKind;
import pandastic.studio.models.api.rig.PhysicsProfile;
import pandastic.studio.models.api.rig.ProfileBounds;
import pandastic.studio.models.api.rig.ProfileDefaults;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.api.rig.RigBounds;
import pandastic.studio.models.internal.physics.AngularPhysicsSolver;
import pandastic.studio.models.internal.render.RenderSettings;
import pandastic.studio.models.internal.render.RenderSettingsSource;
import pandastic.studio.models.internal.sim.SimulationSettings;
import pandastic.studio.models.internal.sim.SimulationSettingsSource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * The configuration and the rig the binding tests run against.
 *
 * <p>The rates are the shipped ones, because the binding layer has no opinion about them and a cadence
 * has to agree with the loop's rate or registration refuses. Everything the binding layer <em>does</em>
 * read — the sampler's period, the teleport thresholds — is given a deliberately unusual value in the
 * test that reads it, because a suite that only ever used the defaults cannot tell a component that
 * honours its configuration from one that ignores it and agrees by accident.
 */
final class BindingFixtures {

    static final int SIMULATION_HZ = 60;
    static final int EMISSION_HZ = 20;
    static final long ERROR_LOG_INTERVAL_NANOS = 5_000_000_000L;
    static final long SAMPLE_PERIOD_TICKS = 1L;

    static final CadenceBounds CADENCE_BOUNDS = new CadenceBounds(1, 59);
    static final DriverBounds DRIVER_BOUNDS = new DriverBounds(1.5F, 6F);
    static final RigBounds RIG_BOUNDS = new RigBounds(256);

    static final ProfileBounds PROFILE_BOUNDS =
            new ProfileBounds(0.05F, 10F, 4F, 4F, 2F, 1.7F, 45F);

    static final ProfileDefaults PROFILE_DEFAULTS = new ProfileDefaults(
            0.08F, 0.015F, 0.6F, 1F, 1F,
            0.25F, 0F, 0.25F, 0.25F,
            0F, -1F, 0F);

    static final BindingBounds BINDING_BOUNDS =
            new BindingBounds(1.0E-3F, 16F, 0.1F, 8F, 2F);

    static final RenderSettings RENDER_SETTINGS = new RenderSettings(1.0E-4F, 8);

    /**
     * The shipped gait calibration. {@link GaitTuning.Builder} carries no defaults on purpose, so all
     * thirty-four are stated; these tests are about where a stance flag goes rather than about the
     * gait, so they only ever want a valid profile to hang one off.
     */
    static final GaitTuning GAIT_TUNING = GaitTuning.builder()
            .counterPhaseCycles(0.5F)
            .restingLegExtension(0.95F)
            .movementDeadbandBlocksPerTick(0.003F)
            .sneakTorsoPitchRadians(0.35F)
            .fullStrideSpeedBlocksPerTick(0.215F)
            .sprintSpeedBlocksPerTick(0.28F)
            .strideToFootReach(0.25F)
            .footLiftFraction(0.18F)
            .footToeOffRadians(0.45F)
            .footHeelStrikeRadians(0.18F)
            .maxIkReachFraction(0.99F)
            .minIkDownFraction(0.35F)
            .legSwingRadians(0.70F)
            .sprintLegSwingRadians(1.05F)
            .armSwingRadians(0.55F)
            .forearmCurlRadians(0.35F)
            .torsoLeanRadians(0.10F)
            .torsoBobRadians(0.035F)
            .torsoBobHarmonic(2F)
            .torsoSwayRadians(0.06F)
            .torsoTwistRadians(0.08F)
            .breathRateHz(0.25F)
            .breathRadians(0.02F)
            .amplitudeBlendSeconds(0.15F)
            .airborneBlendSeconds(0.20F)
            .sneakBlendSeconds(0.18F)
            .maxStepSeconds(0.25F)
            .maxGaitSpeedBlocksPerTick(4F)
            .maxPhaseAdvancePerStep(4F)
            .elapsedWrapBreathCycles(256)
            .settledAmplitude(1.0E-3F)
            .defaultStrideLength(1.3F)
            .defaultHeadStabilise(0.65F)
            .defaultHandStabilise(0.35F)
            .build();

    private BindingFixtures() {
    }

    /** The stock calibration, which every bone of {@link #rig()} is stable under. */
    static PhysicsTuning tuning() {
        return PhysicsTuning.builder()
                .stiffnessToRate(260F)
                .dampingToRate(120F)
                .gravityToRate(40F)
                .windToRadians(60F)
                .turnToSpeed(2F)
                .substepRateHz(SIMULATION_HZ)
                .maxSubsteps(4)
                .restEpsilonRadians(1.0E-5F)
                .build();
    }

    /** Two bones: an anchor and one pendulum hanging off it. */
    static Rig rig() {
        return rig("test-rig");
    }

    static Rig rig(@NotNull String name) {
        return Rig.builder(name, "test-model", RIG_BOUNDS, PROFILE_BOUNDS)
                .root("body")
                .bone("hair", "body", PhysicsProfile
                        .builder(PhysicsKind.PENDULUM, PROFILE_BOUNDS, PROFILE_DEFAULTS)
                        .damping(0.1F)
                        .mass(1F)
                        .build())
                .build();
    }

    /** The bone names a model matching {@link #rig()} would report. */
    static List<String> modelBoneNames() {
        return List.of("body", "hair", "cape");
    }

    static SimulationSettings simulationSettings() {
        return simulationSettings(DRIVER_BOUNDS);
    }

    /** A snapshot with chosen teleport thresholds, so a test can prove the sampler reads them. */
    static SimulationSettings simulationSettings(@NotNull DriverBounds driverBounds) {
        return new SimulationSettings(
                new AngularPhysicsSolver(tuning()),
                SIMULATION_HZ,
                EmissionCadence.of(SIMULATION_HZ, EMISSION_HZ, CADENCE_BOUNDS),
                driverBounds,
                ERROR_LOG_INTERVAL_NANOS);
    }

    static SimulationSettingsSource simulationSource() {
        return SimulationSettingsSource.fixed(simulationSettings());
    }

    static RenderSettingsSource renderSource() {
        return RenderSettingsSource.fixed(RENDER_SETTINGS);
    }

    /** A binding settings source a test can move, which is what a reload looks like from in here. */
    static final class MutableBindingSource implements BindingSettingsSource {

        private volatile BindingSettings settings;
        private int reads;

        MutableBindingSource(long samplePeriodTicks) {
            this.settings = new BindingSettings(samplePeriodTicks);
        }

        @Override
        public BindingSettings current() {
            this.reads++;
            return this.settings;
        }

        void publish(long samplePeriodTicks) {
            this.settings = new BindingSettings(samplePeriodTicks);
        }

        /**
         * How many times the source was consulted.
         *
         * <p>The whole point of counting: a sampler that re-read its period on every execution would be
         * indistinguishable from one that reads it at dispatch until somebody counted, and the
         * difference is an allocation per model per tick on the region threads.
         */
        int reads() {
            return this.reads;
        }
    }

    /**
     * What happened, in order, with the loop's membership observed at each step.
     *
     * <p>The ordering claims the binding layer makes span four collaborators — the loop, the sampler
     * task, the sink and the tracker — and three of them are reached through seams a fake can record.
     * The fourth, the loop, cannot record anything, so instead every entry carries how many simulations
     * the loop was still stepping when it was written. That turns "unregistered before anything was
     * released" from an assertion about an invisible ordering into one about the recorded text.
     */
    static final class Journal {

        private final List<String> entries = new ArrayList<>();
        private @Nullable IntSupplier stepping;

        /** Observes {@code simulationsInLoop} alongside every subsequent entry. */
        Journal observing(@NotNull IntSupplier simulationsInLoop) {
            this.stepping = simulationsInLoop;
            return this;
        }

        void record(@NotNull String event) {
            this.entries.add(this.stepping == null
                    ? event
                    : event + "(stepping=" + this.stepping.getAsInt() + ")");
        }

        List<String> entries() {
            return List.copyOf(this.entries);
        }

        void clear() {
            this.entries.clear();
        }
    }
}
