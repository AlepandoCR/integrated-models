package pandastic.studio.models.internal.render;

import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.rig.Rig;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the load-time validation path. The model side is a plain list of names here, which is the
 * point: the same resolution runs against a live tracker, against a model definition nothing has
 * spawned, and against a literal in a test.
 */
class RigBoneResolutionTest {

    private static Rig cloak() {
        return RenderFixtures.rig("cloak", "npc")
                .root("body")
                .bone("cape", "body", null)
                .bone("cape_tip", "cape", null)
                .build();
    }

    @Test
    void resolvesEveryBoneAndReportsWhereEachLanded() {
        final RigBoneResolution resolution = RigBoneResolution.resolve(
                cloak(), List.of("head", "body", "cape", "cape_tip", "left_arm"));

        assertAll(
                () -> assertTrue(resolution.isBindable()),
                () -> assertTrue(resolution.isComplete()),
                () -> assertFalse(resolution.isAmbiguous()),
                () -> assertEquals(1, resolution.modelIndexOf(0)),
                () -> assertEquals(2, resolution.modelIndexOf(1)),
                () -> assertEquals(3, resolution.modelIndexOf(2)),
                () -> assertTrue(resolution.missing().isEmpty()));
    }

    @Test
    void reportsEveryMissingBoneRatherThanTheFirst() {
        // The whole reason this is a value type: a rigger who renamed two groups should need one
        // restart, not one per group.
        final RigBoneResolution resolution =
                RigBoneResolution.resolve(cloak(), List.of("body", "head"));

        assertAll(
                () -> assertFalse(resolution.isComplete()),
                () -> assertEquals(List.of("cape", "cape_tip"), resolution.missing()),
                () -> assertEquals(-1, resolution.modelIndexOf(1)),
                () -> assertEquals(-1, resolution.modelIndexOf(2)));
    }

    @Test
    void matchesCaseInsensitively() {
        // Rig stores names lower-cased; renderer names come from whatever the rigger typed.
        final RigBoneResolution resolution = RigBoneResolution.resolve(
                cloak(), List.of("Body", "CAPE", "Cape_Tip"));

        assertAll(
                () -> assertTrue(resolution.isBindable()),
                () -> assertEquals(0, resolution.modelIndexOf(0)),
                () -> assertEquals(2, resolution.modelIndexOf(2)));
    }

    @Test
    void treatsADuplicateBareNameAsAmbiguousAndRefusesToGuess() {
        // Tag-stripped matching is the forgiving half of the contract; its cost is that two
        // differently-tagged groups can collapse onto one rig name, and the renderer would then hand
        // back whichever it reached first with no error anywhere.
        final RigBoneResolution resolution = RigBoneResolution.resolve(
                cloak(), List.of("body", "cape", "cape", "cape_tip"));

        assertAll(
                () -> assertTrue(resolution.isComplete(), "it is present, just not unique"),
                () -> assertTrue(resolution.isAmbiguous()),
                () -> assertFalse(resolution.isBindable()),
                () -> assertEquals(List.of("cape"), resolution.ambiguous()),
                () -> assertEquals(1, resolution.modelIndexOf(1),
                        "the reported index must be the one the renderer would pick: the first"));
    }

    @Test
    void ignoresDuplicatesTheRigNeverNames() {
        final RigBoneResolution resolution = RigBoneResolution.resolve(
                cloak(), List.of("body", "cape", "cape_tip", "spike", "spike", "spike"));

        assertAll(
                () -> assertTrue(resolution.isBindable()),
                () -> assertTrue(resolution.ambiguous().isEmpty()));
    }

    @Test
    void requireBindableNamesTheRigTheModelAndEveryProblem() {
        final RigBoneResolution resolution =
                RigBoneResolution.resolve(cloak(), List.of("body", "cape", "cape"));

        final IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> resolution.requireBindable(RenderFixtures.SETTINGS));
        final String message = thrown.getMessage();

        assertAll(
                () -> assertTrue(message.contains("cloak"), "it must name the rig: " + message),
                () -> assertTrue(message.contains("npc"), "and the model: " + message),
                () -> assertTrue(message.contains("cape_tip"), "and the missing bone: " + message),
                () -> assertTrue(message.contains("more than one"), "and the ambiguity: " + message),
                () -> assertTrue(message.contains("body"),
                        "and what the model does have, so the fix is visible: " + message));
    }

    @Test
    void requireBindableAcceptsACompleteUnambiguousRig() {
        final RigBoneResolution resolution =
                RigBoneResolution.resolve(cloak(), List.of("body", "cape", "cape_tip"));
        assertDoesNotThrow(() -> resolution.requireBindable(RenderFixtures.SETTINGS));
    }

    @Test
    void summarisesAHugeModelInsteadOfPrintingAllOfIt() {
        final RigBoneResolution resolution = RigBoneResolution.resolve(cloak(), manyNames(200));

        final String message = assertThrows(IllegalStateException.class,
                () -> resolution.requireBindable(RenderFixtures.SETTINGS)).getMessage();

        assertAll(
                () -> assertTrue(message.contains("200 bone(s)"), message),
                () -> assertTrue(message.contains("more)"), "a 200-bone list must be truncated: " + message),
                () -> assertFalse(message.contains("bone_199"), "and truncated for real: " + message));
    }

    @Test
    void truncatesAtTheConfiguredLimitRatherThanAFixedOne() {
        // Three limits, one resolution: the cut has to follow the setting. A summariser that kept its
        // old constant would print 32 names in all three cases and pass none of these.
        final RigBoneResolution resolution = RigBoneResolution.resolve(cloak(), manyNames(100));

        final String narrow = failureWithLimit(resolution, 3);
        final String wide = failureWithLimit(resolution, 64);

        assertAll(
                () -> assertTrue(narrow.contains("bone_2"), narrow),
                () -> assertFalse(narrow.contains("bone_3"), "a limit of 3 lists exactly three: " + narrow),
                () -> assertTrue(narrow.contains("(+97 more)"), narrow),
                () -> assertTrue(wide.contains("bone_63"), wide),
                () -> assertFalse(wide.contains("bone_64"), wide),
                () -> assertTrue(wide.contains("(+36 more)"), wide));
    }

    @Test
    void aLimitAtOrAboveTheModelSizePrintsEveryName() {
        final RigBoneResolution resolution = RigBoneResolution.resolve(cloak(), manyNames(5));
        final String message = failureWithLimit(resolution, 5);

        assertAll(
                () -> assertTrue(message.contains("bone_4"), message),
                () -> assertFalse(message.contains("more)"),
                        "nothing was dropped, so nothing should be counted: " + message));
    }

    private static String failureWithLimit(RigBoneResolution resolution, int limit) {
        return assertThrows(IllegalStateException.class,
                () -> resolution.requireBindable(
                        new RenderSettings(RenderFixtures.EPSILON_RADIANS, limit))).getMessage();
    }

    private static List<String> manyNames(int count) {
        final List<String> names = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            names.add("bone_" + index);
        }
        return names;
    }

    @Test
    void anEmptyModelReportsEveryRigBoneAsMissing() {
        final RigBoneResolution resolution = RigBoneResolution.resolve(cloak(), List.of());

        assertEquals(List.of("body", "cape", "cape_tip"), resolution.missing());
    }
}
