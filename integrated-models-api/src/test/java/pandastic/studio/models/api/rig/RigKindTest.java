package pandastic.studio.models.api.rig;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The kind, and the one rule it carries: only a vehicle seats riders.
 */
class RigKindTest {

    @Test
    void parsesEverySpellingARigFileMightUse() {
        assertAll(
                () -> assertEquals(RigKind.CREATURE, RigKind.parse("creature")),
                () -> assertEquals(RigKind.VEHICLE, RigKind.parse("VEHICLE")),
                () -> assertEquals(RigKind.DECORATION, RigKind.parse(" Decoration ")),
                // Lenient about separators like every other option value in the format, so a kind
                // written with one is not a file that fails to load.
                () -> assertEquals(RigKind.CREATURE, RigKind.parse("crea-ture")),
                () -> assertEquals(RigKind.CREATURE, RigKind.parse("crea_ture")));
    }

    @Test
    void refusesAKindThatNamesNothingAndListsTheOnesItKnows() {
        final IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> RigKind.parse("vehicel"));
        assertAll(
                () -> assertTrue(thrown.getMessage().contains("vehicel"),
                        "it must quote what was written, got: " + thrown.getMessage()),
                () -> assertTrue(thrown.getMessage().contains("creature, vehicle, decoration"),
                        "it must list the options, got: " + thrown.getMessage()));
    }

    @Test
    void onlyAVehiclePermitsSeats() {
        // The whole reason the kind exists. Stated as a method on the enum so that the one place the
        // rule lives is the place a reader looks for it, rather than as a comparison at each caller.
        assertAll(
                () -> assertTrue(RigKind.VEHICLE.permitsSeats()),
                () -> assertFalse(RigKind.CREATURE.permitsSeats()),
                () -> assertFalse(RigKind.DECORATION.permitsSeats()));
    }

    @Test
    void aRigThatSaysNothingAboutItsKindIsACreature() {
        // The compatibility guarantee: a rig built before the kind existed means what it meant, and
        // every derived rig makes no claim either.
        assertAll(
                () -> assertEquals(RigKind.CREATURE,
                        Rig.builder("r", "m", RigFixtures.rigBounds(), RigFixtures.wideProfileBounds())
                                .root("body").build().kind()),
                () -> assertEquals(RigKind.CREATURE, Rig.modelOnly("m").kind()),
                () -> assertEquals(RigKind.CREATURE, Rig.modelOnly("m", "root").kind()));
    }
}
