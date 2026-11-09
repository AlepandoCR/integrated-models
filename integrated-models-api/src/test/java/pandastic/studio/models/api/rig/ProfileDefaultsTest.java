package pandastic.studio.models.api.rig;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seeds a rig file inherits, and the one invariant that ties them to the bounds in the same file.
 *
 * <p>Every case uses {@link RigFixtures#oddProfileDefaults()} or numbers chosen the same way, because a
 * suite built on the figures the removed literals happened to hold could not tell "the seed is read from
 * configuration" from "the seed is ignored and the old constant agrees".
 */
class ProfileDefaultsTest {

    private static ProfileDefaults defaults(
            float stiffness, float damping, float lag, float mass, float wind,
            float pendulumGravity, float springGravity, float clothStripGravity, float dragGravity,
            float reachX, float reachY, float reachZ
    ) {
        return new ProfileDefaults(stiffness, damping, lag, mass, wind,
                pendulumGravity, springGravity, clothStripGravity, dragGravity,
                reachX, reachY, reachZ);
    }

    /** The odd fixture with one component replaced, so a case varies exactly one thing. */
    private static ProfileDefaults withMass(float mass) {
        final ProfileDefaults odd = RigFixtures.oddProfileDefaults();
        return defaults(odd.stiffness(), odd.damping(), odd.lag(), mass, odd.wind(),
                odd.pendulumGravity(), odd.springGravity(), odd.clothStripGravity(), odd.dragGravity(),
                odd.reachX(), odd.reachY(), odd.reachZ());
    }

    @Test
    void readsBackEveryComponentExactly() {
        final ProfileDefaults odd = RigFixtures.oddProfileDefaults();
        assertAll(
                () -> assertEquals(0.92F, odd.stiffness()),
                () -> assertEquals(0.004F, odd.damping()),
                () -> assertEquals(0.11F, odd.lag()),
                () -> assertEquals(3.75F, odd.mass()),
                () -> assertEquals(0.3F, odd.wind()),
                () -> assertEquals(1.9F, odd.pendulumGravity()),
                () -> assertEquals(0.6F, odd.springGravity()),
                () -> assertEquals(0.05F, odd.clothStripGravity()),
                () -> assertEquals(2.5F, odd.dragGravity()),
                // Carried as configured rather than normalised: PhysicsProfile normalises, so doing it
                // twice would mean the value an operator reads back is not the value they typed.
                () -> assertEquals(2F, odd.reachX()),
                () -> assertEquals(0.5F, odd.reachY()),
                () -> assertEquals(-1F, odd.reachZ()));
    }

    @Test
    void selectsTheGravityOfEveryKindAndKeepsThemDistinct() {
        final ProfileDefaults odd = RigFixtures.oddProfileDefaults();
        assertAll(
                () -> assertEquals(1.9F, odd.gravityFor(PhysicsKind.PENDULUM)),
                () -> assertEquals(0.6F, odd.gravityFor(PhysicsKind.SPRING)),
                () -> assertEquals(0.05F, odd.gravityFor(PhysicsKind.CLOTH_STRIP)),
                () -> assertEquals(2.5F, odd.gravityFor(PhysicsKind.DRAG)),
                // The per-kind difference is the point of there being four components at all: a spring
                // that hangs is not a spring, so the kinds have to be allowed to disagree.
                () -> assertNotEquals(odd.gravityFor(PhysicsKind.PENDULUM),
                        odd.gravityFor(PhysicsKind.SPRING)),
                () -> assertNotEquals(odd.gravityFor(PhysicsKind.PENDULUM),
                        odd.gravityFor(PhysicsKind.CLOTH_STRIP)));
    }

    @Test
    void everyKindHasASeed() {
        // gravityFor is an exhaustive switch, so this is really a check that no kind was forgotten when
        // one was added: a new constant makes the api stop compiling, and this notices if it is ever
        // given a hole instead.
        final ProfileDefaults stock = RigFixtures.stockProfileDefaults();
        for (final PhysicsKind kind : PhysicsKind.values()) {
            final float gravity = stock.gravityFor(kind);
            assertTrue(Float.isFinite(gravity) && gravity >= 0F,
                    () -> kind + " has no usable gravity seed: " + gravity);
        }
    }

    @Test
    void enforcesTheFractionsBecauseTheRangeIsTheWordsMeaning() {
        assertAll(
                () -> assertRefused(() -> withStiffness(1.5F), "stiffness"),
                () -> assertRefused(() -> withStiffness(-0.1F), "stiffness"),
                () -> assertRefused(() -> withStiffness(Float.NaN), "stiffness"),
                () -> assertDoesNotThrow(() -> withStiffness(1F)));
    }

    @Test
    void refusesAMassThatWouldDivideByZeroForEveryBoneThatOmitsOne() {
        assertAll(
                () -> assertRefused(() -> withMass(0F), "mass"),
                () -> assertRefused(() -> withMass(-1F), "mass"),
                () -> assertRefused(() -> withMass(Float.POSITIVE_INFINITY), "mass"),
                () -> assertDoesNotThrow(() -> withMass(0.02F)));
    }

    @Test
    void refusesAReachThatPointsNowhere() {
        // A seeded bone has to extend in some direction, because that is the axis the wind torque and
        // the pendulum's pull are applied along.
        assertRefused(() -> withReach(0F, 0F, 0F), "no direction");
        // Any one non-zero component is a direction, however odd.
        assertDoesNotThrow(() -> withReach(0F, 0F, 0.25F));
    }

    @Test
    void refusesANegativeGravityOrWindSeed() {
        final ProfileDefaults odd = RigFixtures.oddProfileDefaults();
        assertAll(
                () -> assertRefused(() -> defaults(odd.stiffness(), odd.damping(), odd.lag(),
                        odd.mass(), -0.5F, odd.pendulumGravity(), odd.springGravity(),
                        odd.clothStripGravity(), odd.dragGravity(),
                        odd.reachX(), odd.reachY(), odd.reachZ()), "wind"),
                () -> assertRefused(() -> defaults(odd.stiffness(), odd.damping(), odd.lag(),
                        odd.mass(), odd.wind(), odd.pendulumGravity(), -1F,
                        odd.clothStripGravity(), odd.dragGravity(),
                        odd.reachX(), odd.reachY(), odd.reachZ()), "springGravity"));
    }

    @Test
    void refusesSeedsTheSuppliedBoundsWouldNotAdmit() {
        // The invariant worth having: a rig file may declare nothing but a bone's kind, in which case
        // the profile is built entirely out of these seeds and then validated against these bounds.
        // Defaults outside their own bounds therefore refuse every bone that trusted one.
        final ProfileBounds narrow = new ProfileBounds(0.5F, 1.5F, 1F, 0.5F, 2F, 1.7F, 45F);
        final ProfileDefaults odd = RigFixtures.oddProfileDefaults();

        assertAll(
                // Mass 3.75 is above the 1.5 ceiling, wind 0.3 is inside, and the message names the
                // component an operator can go and change.
                () -> assertRefused(() -> odd.requireWithin(narrow), "mass"),
                () -> assertRefused(() -> withMass(1F).requireWithin(narrow), "pendulumGravity"),
                () -> assertRefused(
                        () -> withMass(1F).requireWithin(new ProfileBounds(
                                0.5F, 1.5F, 10F, 0.1F, 2F, 1.7F, 45F)),
                        "wind"),
                // A seed below the mass floor is refused as well; the range is two-sided.
                () -> assertRefused(() -> withMass(0.1F).requireWithin(narrow), "mass"));
    }

    @Test
    void requireWithinReturnsTheSameInstanceSoItChains() {
        final ProfileDefaults odd = RigFixtures.oddProfileDefaults();
        assertSame(odd, odd.requireWithin(RigFixtures.wideProfileBounds()));
    }

    @Test
    void acceptsTheStockSeedsAgainstTheStockBounds() {
        // The shipped pairing has to be legal, or a fresh installation refuses its own configuration.
        assertDoesNotThrow(() -> RigFixtures.stockProfileDefaults()
                .requireWithin(new ProfileBounds(0.05F, 10F, 4F, 4F, 2F, 1.7F, 45F)));
    }

    private static ProfileDefaults withStiffness(float stiffness) {
        final ProfileDefaults odd = RigFixtures.oddProfileDefaults();
        return defaults(stiffness, odd.damping(), odd.lag(), odd.mass(), odd.wind(),
                odd.pendulumGravity(), odd.springGravity(), odd.clothStripGravity(), odd.dragGravity(),
                odd.reachX(), odd.reachY(), odd.reachZ());
    }

    private static ProfileDefaults withReach(float x, float y, float z) {
        final ProfileDefaults odd = RigFixtures.oddProfileDefaults();
        return defaults(odd.stiffness(), odd.damping(), odd.lag(), odd.mass(), odd.wind(),
                odd.pendulumGravity(), odd.springGravity(), odd.clothStripGravity(), odd.dragGravity(),
                x, y, z);
    }

    private static void assertRefused(Runnable construction, String expectedInMessage) {
        final IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, construction::run);
        assertTrue(thrown.getMessage().contains(expectedInMessage),
                "it must say what is wrong, got: " + thrown.getMessage());
    }
}
