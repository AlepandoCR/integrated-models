package pandastic.studio.models.nms;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mapping probe is the one part of this module that can be exercised without a server, and it is
 * worth exercising for the reason {@code FoliaMovementBridge} explains: it is what stands between a
 * renamed member and a {@link NoSuchMethodError} thrown once per vehicle per tick from inside a region's
 * step.
 *
 * <p>Everything else here needs the Mojang-mapped server, which this module declares
 * {@code testCompileOnly} on purpose — NMS classes need a bootstrapped registry, so a test that linked
 * against them would fail on first touch and look like our bug. The probe needs none of it: it is
 * reflection over whatever class it is handed, so the cases below hand it this file's own.
 *
 * <p>The shapes under test are chosen to mirror the three members the movement bridge really asks for —
 * a {@code void} method with two reference parameters, a no-argument method returning a reference type,
 * and a no-argument method returning a primitive — so a regression in how any of those is matched is
 * caught here rather than on a server.
 */
class MappedMethodsTest {

    /**
     * Stands in for the server's class: a known shape the probe can be asked about.
     *
     * <p>Every member is reached reflectively, by name, from the cases below — nothing calls one
     * directly, which is the whole point of a probe.
     */
    public static class Subject extends Inherited {

        public int visible() {
            return 0;
        }

        public void act(MoverLike mover, VectorLike vector) {
            // Mirrors Entity#move's shape: void, two reference parameters.
        }

        public MoverLike controller() {
            return null;
        }

        public boolean flag() {
            return false;
        }

        private int hidden() {
            return 0;
        }

        protected int shielded() {
            return 0;
        }
    }

    /** A public member the probe must find through the hierarchy, as it does for the server's own. */
    public static class Inherited {

        public long carried() {
            return 0L;
        }
    }

    /** Stand-ins for the two reference parameter types, so the match is on types and not on names. */
    public static class MoverLike {
    }

    /** The second parameter type; distinct from the first so a transposition is a mismatch. */
    public static class VectorLike {
    }

    @Test
    void findsAPublicMethodOfExactlyTheRequestedShape() {
        assertAll(
                () -> assertNull(MappedMethods.missing(Subject.class, "visible", int.class)),
                () -> assertNull(MappedMethods.missing(Subject.class, "flag", boolean.class)),
                () -> assertNull(MappedMethods.missing(
                        Subject.class, "controller", MoverLike.class)),
                () -> assertNull(MappedMethods.missing(
                        Subject.class, "act", void.class, MoverLike.class, VectorLike.class)));
    }

    @Test
    void findsAPublicMethodDeclaredOnASupertype() {
        // The server's own members are spread over Entity's hierarchy, so a probe that only looked at
        // the declaring class would report a capability absent that the call site resolves fine.
        assertNull(MappedMethods.missing(Subject.class, "carried", long.class));
    }

    @Test
    void reportsARenamedMethodAndNamesWhatWasLookedFor() {
        // The Spigot-mapped case, and the fork-renamed case: the class is there, the member is not.
        final String failure = MappedMethods.missing(Subject.class, "notThere", int.class);
        assertNotNull(failure);
        assertAll(
                () -> assertTrue(failure.contains(Subject.class.getName()),
                        () -> "should name the owner, was: " + failure),
                () -> assertTrue(failure.contains("notThere"),
                        () -> "should name the member, was: " + failure));
    }

    @Test
    void reportsAMethodWhoseReturnTypeMoved() {
        // getMethod matches on name and parameters only, so this is the case a probe written without an
        // explicit return-type check would pass and the call site would then fail to link.
        final String failure = MappedMethods.missing(Subject.class, "visible", long.class);
        assertNotNull(failure);
        assertAll(
                () -> assertTrue(failure.contains("visible"),
                        () -> "should name the member, was: " + failure),
                () -> assertTrue(failure.contains("int"),
                        () -> "should name what it returns instead, was: " + failure));
    }

    @Test
    void reportsAMethodWhoseParametersMoved() {
        assertAll(
                () -> assertNotNull(MappedMethods.missing(
                                Subject.class, "act", void.class, MoverLike.class),
                        "a dropped parameter is a different method"),
                () -> assertNotNull(MappedMethods.missing(
                                Subject.class, "act", void.class, VectorLike.class, MoverLike.class),
                        "transposed parameters are a different method"));
    }

    @Test
    void reportsAMethodThatIsNotPublic() {
        // The call sites below this seam are in another package, so non-public is as unusable as absent
        // — and getMethod answering only for public members is what makes the probe match the call.
        assertAll(
                () -> assertNotNull(MappedMethods.missing(Subject.class, "hidden", int.class)),
                () -> assertNotNull(MappedMethods.missing(Subject.class, "shielded", int.class)));
    }
}
