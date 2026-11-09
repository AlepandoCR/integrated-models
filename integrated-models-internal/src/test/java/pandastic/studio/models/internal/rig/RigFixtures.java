package pandastic.studio.models.internal.rig;

import pandastic.studio.models.api.physics.PhysicsTuning;
import pandastic.studio.models.api.rig.ProfileBounds;
import pandastic.studio.models.api.rig.ProfileDefaults;
import pandastic.studio.models.api.rig.RigBounds;
import pandastic.studio.models.internal.physics.AngularPhysicsSolver;

/**
 * The configuration the loader tests run against, in one place.
 *
 * <p>These are the stock figures — the ones the shipped configuration carries and that the loader's
 * documented behaviour is described in terms of. Tests that are about <em>reading</em> configuration
 * rather than about the format build their own settings from unusual values instead, which is the only
 * way to tell a loader that honours its bounds from one that happens to agree with them.
 */
final class RigFixtures {

    static final RigBounds RIG_BOUNDS = new RigBounds(256);

    static final ProfileBounds PROFILE_BOUNDS =
            new ProfileBounds(0.05F, 10F, 4F, 4F, 2F, 1.7F, 45F);

    /**
     * The stock seeds, which are what a file that omits a coefficient inherits.
     *
     * <p>Every one of them sits inside {@link #PROFILE_BOUNDS}, as it must: a bone may declare nothing
     * but its kind, and the profile built from these alone is validated against those bounds.
     */
    static final ProfileDefaults PROFILE_DEFAULTS = new ProfileDefaults(
            0.08F, 0.015F, 0.6F, 1F, 1F,
            0.25F, 0F, 0.25F, 0.25F,
            0F, -1F, 0F);

    /** The stock calibration: 60 Hz, which is where "damping must stay below mass" comes from. */
    static final PhysicsTuning TUNING = tuning(60);

    static final AngularPhysicsSolver SOLVER = new AngularPhysicsSolver(TUNING);

    static final RigLoadSettings SETTINGS = settings(SOLVER);

    private RigFixtures() {
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

    /** Stock settings around {@code solver}, so a test can move the solver and nothing else. */
    static RigLoadSettings settings(AngularPhysicsSolver solver) {
        return new RigLoadSettings(
                solver, RIG_BOUNDS, PROFILE_BOUNDS, PROFILE_DEFAULTS, 0.99F, 1.0E-4F);
    }
}
