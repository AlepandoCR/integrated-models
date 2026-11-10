package pandastic.studio.models.internal.sim;

import pandastic.studio.models.api.locomotion.BindingBounds;
import pandastic.studio.models.api.locomotion.GaitProfile;
import pandastic.studio.models.api.locomotion.GaitTuning;
import pandastic.studio.models.api.physics.DriverBounds;
import pandastic.studio.models.api.physics.PhysicsSolver;
import pandastic.studio.models.api.physics.PhysicsTuning;
import pandastic.studio.models.api.render.CadenceBounds;
import pandastic.studio.models.api.render.EmissionCadence;
import pandastic.studio.models.api.rig.ProfileBounds;
import pandastic.studio.models.api.rig.ProfileDefaults;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.api.rig.RigBounds;
import pandastic.studio.models.internal.physics.AngularPhysicsSolver;

/**
 * The configuration the simulation tests run against, in one place.
 *
 * <p>These are the stock figures. Tests about <em>reading</em> configuration build their own snapshots
 * from deliberately unusual values instead — a 5 Hz emission rate, a 30 Hz loop, teleport thresholds of
 * nearly nothing — because a suite that only ever used 60 and 20 cannot tell a component that reads its
 * settings from one that ignores them and agrees by accident.
 */
final class SimFixtures {

    static final int SIMULATION_HZ = 60;
    static final int EMISSION_HZ = 20;
    static final long ERROR_LOG_INTERVAL_NANOS = 5_000_000_000L;

    static final CadenceBounds CADENCE_BOUNDS = new CadenceBounds(1, 59);
    static final DriverBounds DRIVER_BOUNDS = new DriverBounds(1.5F, 6F);
    static final RigBounds RIG_BOUNDS = new RigBounds(256);

    static final ProfileBounds PROFILE_BOUNDS =
            new ProfileBounds(0.05F, 10F, 4F, 4F, 2F, 1.7F, 45F);

    /** The stock seeds a profile that states nothing but its kind is built from. */
    static final ProfileDefaults PROFILE_DEFAULTS = new ProfileDefaults(
            0.08F, 0.015F, 0.6F, 1F, 1F,
            0.25F, 0F, 0.25F, 0.25F,
            0F, -1F, 0F);

    static final BindingBounds BINDING_BOUNDS =
            new BindingBounds(1.0E-3F, 16F, 0.1F, 8F, 2F);

    /**
     * The shipped gait calibration. {@link GaitTuning.Builder} carries no defaults on purpose, so all
     * thirty-four are stated; these tests are about the simulation's ordering rather than about the
     * gait, so they only ever want the calibrated look.
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
            .attackDurationSeconds(0.35F)
            .attackUpperArmRadians(-1.35F)
            .attackForearmRadians(0.55F)
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

    static final GaitProfile GAIT_PROFILE = GaitProfile.defaults(GAIT_TUNING);

    private SimFixtures() {
    }

    /** The stock calibration at {@code substepRateHz}, so a test can move the rate and nothing else. */
    static PhysicsTuning tuning(int substepRateHz) {
        return PhysicsTuning.builder()
                .stiffnessToRate(260F)
                .dampingToRate(120F)
                .gravityToRate(40F)
                .windToRadians(60F)
                .turnToSpeed(2F)
                .substepRateHz(substepRateHz)
                .maxSubsteps(4)
                .restEpsilonRadians(1.0E-5F)
                .build();
    }

    /** The real solver at the stock calibration. */
    static AngularPhysicsSolver realSolver() {
        return new AngularPhysicsSolver(tuning(SIMULATION_HZ));
    }

    static EmissionCadence cadence(int simulationHz, int emissionHz) {
        return EmissionCadence.of(simulationHz, emissionHz, CADENCE_BOUNDS);
    }

    /** A stock snapshot around {@code solver}. */
    static SimulationSettings settings(PhysicsSolver solver) {
        return settings(solver, SIMULATION_HZ, EMISSION_HZ);
    }

    /** A snapshot at a chosen loop and emission rate, with everything else stock. */
    static SimulationSettings settings(PhysicsSolver solver, int simulationHz, int emissionHz) {
        return new SimulationSettings(
                solver,
                simulationHz,
                cadence(simulationHz, emissionHz),
                DRIVER_BOUNDS,
                ERROR_LOG_INTERVAL_NANOS);
    }

    /** A fixed source holding a stock snapshot around {@code solver}. */
    static SimulationSettingsSource source(PhysicsSolver solver) {
        return SimulationSettingsSource.fixed(settings(solver));
    }

    /** A rig builder carrying the fixture bounds. */
    static Rig.Builder rig(String name, String modelId) {
        return Rig.builder(name, modelId, RIG_BOUNDS, PROFILE_BOUNDS);
    }

    /**
     * A source whose snapshot a test can swap, which is what a configuration reload looks like from
     * inside this package.
     *
     * <p>It also counts reads, which is how the "one snapshot per step" contract becomes testable: a
     * step that consulted the source twice is indistinguishable from one that consulted it once until
     * somebody counts.
     */
    static final class MutableSource implements SimulationSettingsSource {

        private volatile SimulationSettings settings;
        private int reads;

        MutableSource(SimulationSettings settings) {
            this.settings = settings;
        }

        @Override
        public SimulationSettings current() {
            this.reads++;
            return this.settings;
        }

        void publish(SimulationSettings replacement) {
            this.settings = replacement;
        }

        int reads() {
            return this.reads;
        }

        void resetReads() {
            this.reads = 0;
        }
    }
}
