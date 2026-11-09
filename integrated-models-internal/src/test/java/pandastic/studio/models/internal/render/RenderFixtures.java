package pandastic.studio.models.internal.render;

import pandastic.studio.models.api.rig.ProfileBounds;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.api.rig.RigBounds;

/**
 * The bounds and settings the render tests run against, in one place.
 *
 * <p>Collected here for a reason beyond brevity: nothing in the render layer has a default any more, so
 * every test has to say what configuration it is exercising. Having the ordinary values in one named
 * place makes the tests that deliberately use <em>un</em>ordinary ones stand out as the point they are
 * making, instead of looking like noise.
 */
final class RenderFixtures {

    /** Roughly 0.006°, which at a one-block lever arm is about 10⁻⁴ blocks — below anything drawable. */
    static final float EPSILON_RADIANS = 1.0E-4F;

    /** Short enough that a 200-bone model is summarised, long enough to name a handful. */
    static final int BONE_NAME_LIMIT = 32;

    static final RenderSettings SETTINGS = new RenderSettings(EPSILON_RADIANS, BONE_NAME_LIMIT);

    static final RigBounds RIG_BOUNDS = new RigBounds(256);

    static final ProfileBounds PROFILE_BOUNDS =
            new ProfileBounds(0.05F, 10F, 4F, 4F, 2F, 1.7F, 45F);

    private RenderFixtures() {
    }

    /** A rig builder carrying the fixture bounds. */
    static Rig.Builder rig(String name, String modelId) {
        return Rig.builder(name, modelId, RIG_BOUNDS, PROFILE_BOUNDS);
    }
}
