package pandastic.studio.models.api.rig;

/**
 * Shared {@link RigBounds}, {@link ProfileBounds} and {@link ProfileDefaults} fixtures for the rig tests.
 *
 * <p>{@link #wideProfileBounds()} is the permissive configuration a test uses when the bounds are not
 * what it is about. The narrow and odd ones live in the tests that are about them, deliberately chosen to
 * disagree with the constants this layer used to carry so a surviving fallback fails rather than agrees.
 */
final class RigFixtures {

    private RigFixtures() {
    }

    /**
     * The shipped seeds, for a test whose subject is not the seeding.
     *
     * <p>These are the figures {@code physics.yml} declares, which is also what the removed literals in
     * {@code PhysicsProfile.Builder} used to be — so a test that asserts on them is asserting about this
     * fixture and nothing else. The test that proves the builder actually reads its defaults uses values
     * that disagree with these in both directions, and lives next to the claim it defends.
     */
    static ProfileDefaults stockProfileDefaults() {
        return new ProfileDefaults(
                0.08F, 0.015F, 0.6F, 1F, 1F,
                0.25F, 0F, 0.25F, 0.25F,
                0F, -1F, 0F);
    }

    /**
     * Seeds that disagree with every literal {@code PhysicsProfile.Builder} used to carry, in both
     * directions.
     *
     * <p>Shared by the two suites that assert the seeding is read rather than assumed, because the whole
     * value of these numbers is in how they were chosen and that reasoning should be written once.
     * Stiffness, mass and the pendulum's gravity are above the removed literal; damping, lag, wind and
     * the cloth strip's gravity are below it; the spring's gravity is off the zero the builder used to
     * hardcode for it; the four gravities are mutually distinct, so a seed that reaches the wrong kind is
     * visible; and the reach points up and sideways, which nothing deriving from the old
     * hangs-straight-down default could produce. Every one of them is inside
     * {@link #wideProfileBounds()}, so a test using both is never measuring a range failure by accident.
     */
    static ProfileDefaults oddProfileDefaults() {
        return new ProfileDefaults(
                0.92F, 0.004F, 0.11F, 3.75F, 0.3F,
                1.9F, 0.6F, 0.05F, 2.5F,
                2F, 0.5F, -1F);
    }

    /** Not 256. */
    static RigBounds rigBounds() {
        return new RigBounds(37);
    }

    /** Wide enough that no profile in a test trips it, so range failures are never incidental. */
    static ProfileBounds wideProfileBounds() {
        return new ProfileBounds(0.01F, 100F, 50F, 50F, 20F, 1.7F, 45F);
    }
}
