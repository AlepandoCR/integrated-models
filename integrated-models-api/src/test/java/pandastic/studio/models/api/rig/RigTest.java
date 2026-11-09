package pandastic.studio.models.api.rig;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RigTest {

    private static final RigBounds RIG_BOUNDS = RigFixtures.rigBounds();
    private static final ProfileBounds PROFILE_BOUNDS = RigFixtures.wideProfileBounds();
    private static final ProfileDefaults PROFILE_DEFAULTS = RigFixtures.stockProfileDefaults();

    private static Rig.Builder builder(String name, String modelId) {
        return Rig.builder(name, modelId, RIG_BOUNDS, PROFILE_BOUNDS);
    }

    private static PhysicsProfile pendulum() {
        return PhysicsProfile.builder(PhysicsKind.PENDULUM, PROFILE_BOUNDS, PROFILE_DEFAULTS).build();
    }

    @Test
    void buildsATopologicallyOrderedRig() {
        final Rig rig = builder("doll", "haunted_doll")
                .root("body")
                .bone("head", "body", null)
                .bone("hair", "head", pendulum())
                .build();

        assertAll(
                () -> assertEquals(3, rig.boneCount()),
                () -> assertEquals("doll", rig.getName()),
                () -> assertEquals("haunted_doll", rig.getModelId()),
                () -> assertTrue(rig.bone(0).isRoot()),
                () -> assertEquals(0, rig.bone(1).parentIndex()),
                () -> assertEquals(1, rig.bone(2).parentIndex()),
                () -> assertEquals(1, rig.physicsBoneCount()),
                () -> assertArrayEquals(new int[]{2}, rig.physicsBoneIndices()));
    }

    @Test
    void physicsIndicesStayInTopologicalOrderSoAChainResolvesInOnePass() {
        final Rig rig = builder("chain", "model")
                .root("root")
                .bone("a", "root", pendulum())
                .bone("b", "a", pendulum())
                .bone("c", "b", pendulum())
                .build();

        final int[] entries = rig.physicsBoneIndices();
        assertArrayEquals(new int[]{1, 2, 3}, entries);
        for (int i = 1; i < entries.length; i++) {
            assertTrue(entries[i - 1] < entries[i], "entries must ascend");
        }
    }

    @Test
    void looksBonesUpCaseInsensitively() {
        final Rig rig = builder("r", "m").root("Body").bone("HAIR", "Body", pendulum()).build();

        assertAll(
                () -> assertEquals(OptionalInt.of(0), rig.indexOf("body")),
                () -> assertEquals(OptionalInt.of(0), rig.indexOf("BODY")),
                () -> assertEquals(OptionalInt.of(1), rig.indexOf("hair")),
                () -> assertEquals(OptionalInt.empty(), rig.indexOf("tail")));
    }

    @Test
    void rejectsADuplicateBoneEvenInADifferentCase() {
        final Rig.Builder builder = builder("r", "m").root("body");
        assertThrows(IllegalArgumentException.class, () -> builder.bone("BODY", null, null));
    }

    @Test
    void rejectsAChildDeclaredBeforeItsParent() {
        final Rig.Builder builder = builder("r", "m");
        final IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class, () -> builder.bone("hair", "head", pendulum()));
        assertTrue(thrown.getMessage().contains("has not been declared yet"));
    }

    @Test
    void rejectsAnEmptyRig() {
        assertThrows(IllegalArgumentException.class, () -> builder("r", "m").build());
    }

    @Test
    void rejectsABlankNameOrModelId() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> builder(" ", "m")),
                () -> assertThrows(IllegalArgumentException.class, () -> builder("r", " ")));
    }

    @Test
    void enforcesTheConfiguredBoneCeiling() {
        final Rig.Builder builder = builder("big", "m").root("b0");
        for (int i = 1; i < RIG_BOUNDS.maxBones(); i++) {
            builder.bone("b" + i, "b0", null);
        }
        final IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class, () -> builder.bone("overflow", "b0", null));
        assertTrue(thrown.getMessage().contains(RIG_BOUNDS.maxBones() + "-bone ceiling"),
                "it must state the configured ceiling, got: " + thrown.getMessage());
    }

    @Test
    void theBoneCeilingComesFromTheSuppliedBoundsAndNothingElse() {
        // The removed constant was 256. A ceiling of 3 is honoured literally, which no fallback to 256
        // could produce.
        final Rig.Builder tiny = Rig.builder("tiny", "m", new RigBounds(3), PROFILE_BOUNDS)
                .root("a")
                .bone("b", "a", null)
                .bone("c", "a", null);
        assertAll(
                () -> assertEquals(3, tiny.build().boneCount()),
                () -> assertThrows(IllegalArgumentException.class, () -> tiny.bone("d", "a", null)),
                // And a single-bone rig is a legal configuration, not an edge case.
                () -> assertEquals(1, Rig.builder("solo", "m", new RigBounds(1), PROFILE_BOUNDS)
                        .root("only").build().boneCount()));
    }

    @Test
    void foldsAClothStripCollisionConeIntoAPitchClamp() {
        final PhysicsProfile authored = PhysicsProfile
                .builder(PhysicsKind.CLOTH_STRIP, PROFILE_BOUNDS, PROFILE_DEFAULTS)
                .collisionRadius(0.1F)
                .limits(AxisLimits.symmetricDegrees(80F))
                .build();

        final Rig rig = builder("cloak", "m").root("cape", authored).build();
        final PhysicsProfile compiled = rig.bone(0).physics();

        assertTrue(compiled != null, "the bone keeps its physics");
        // 0.1 blocks of clearance * the configured 1.7 rad/block = 0.17 rad, far tighter than 80 degrees.
        assertEquals(0.1F * PROFILE_BOUNDS.collisionRadiusToRadians(),
                compiled.limits().maxPitch(), 1.0E-5F);
        // Only the ceiling moves; the other axes are untouched.
        assertEquals(authored.limits().maxYaw(), compiled.limits().maxYaw(), 1.0E-6F);
    }

    @Test
    void theClearanceConversionComesFromTheSuppliedBoundsAndNothingElse() {
        // The removed constant was 1.7. A conversion of 0.4 rad/block gives a ceiling 0.17 could not.
        final ProfileBounds odd = new ProfileBounds(0.01F, 100F, 50F, 50F, 20F, 0.4F, 45F);
        final PhysicsProfile authored = PhysicsProfile.builder(PhysicsKind.CLOTH_STRIP, odd, PROFILE_DEFAULTS)
                .collisionRadius(0.25F)
                .limits(AxisLimits.symmetricDegrees(80F))
                .build();

        final Rig rig = Rig.builder("cloak", "m", RIG_BOUNDS, odd).root("cape", authored).build();
        final PhysicsProfile compiled = rig.bone(0).physics();

        assertTrue(compiled != null);
        assertEquals(0.1F, compiled.limits().maxPitch(), 1.0E-5F);
    }

    @Test
    void refusesABoneWhosePhysicsIsOutsideTheConfiguredRanges() {
        // PhysicsProfile's own constructor cannot reach the bounds, so the rig compile is where that
        // guarantee is restored. Without this the api would quietly accept a coefficient the solver
        // diverges on, which is the behaviour the whole range check exists to prevent.
        final PhysicsProfile heavy = PhysicsProfile.builder(PhysicsKind.PENDULUM, PROFILE_BOUNDS, PROFILE_DEFAULTS)
                .mass(40F)
                .build();
        final ProfileBounds narrow = new ProfileBounds(0.5F, 2F, 4F, 4F, 2F, 1.7F, 45F);

        final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Rig.builder("r", "m", RIG_BOUNDS, narrow).root("hair", heavy));
        assertTrue(thrown.getMessage().contains("mass"),
                "it must name the coefficient, got: " + thrown.getMessage());
    }

    @Test
    void leavesANonClothProfileAlone() {
        final PhysicsProfile authored = PhysicsProfile.builder(PhysicsKind.PENDULUM, PROFILE_BOUNDS, PROFILE_DEFAULTS)
                .collisionRadius(0.1F)
                .build();
        final Rig rig = builder("r", "m").root("hair", authored).build();
        assertEquals(authored, rig.bone(0).physics());
    }

    @Test
    void reportsNoPhysicsForAPassThroughBone() {
        final Rig rig = builder("r", "m").root("body").build();
        assertAll(
                () -> assertFalse(rig.bone(0).hasPhysics()),
                () -> assertEquals(0, rig.physicsBoneCount()));
    }
}
