package pandastic.studio.models.api.rig;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The clip bindings, and the per-bone animation-ownership flag they go with.
 *
 * <p>Both exist for one reason: this plugin's pose writer and the renderer's animation pass write the
 * same display, so the layer that composes them has to know where they overlap. These types carry that
 * knowledge; nothing here plays anything.
 */
class RigClipsTest {

    @Test
    void keepsAClipNameExactlyAsTheRiggerSpelledIt() {
        // The one name in this package that is NOT normalised. It is looked up inside a model file
        // this plugin does not own, and the models in this repository ship clips called standUp and
        // lookaround - lower-casing would name a clip that does not exist.
        final RigClips clips = RigClips.of(Map.of(ClipRole.RUN, "  standUp  "));

        assertAll(
                () -> assertEquals("standUp", clips.clip(ClipRole.RUN), "trimmed, never case-folded"),
                () -> assertEquals("standUp", clips.findClip(ClipRole.RUN).orElseThrow()),
                () -> assertTrue(clips.declares(ClipRole.RUN)),
                () -> assertFalse(clips.declares(ClipRole.IDLE)),
                () -> assertNull(clips.clip(ClipRole.IDLE)),
                () -> assertEquals(1, clips.size()));
    }

    @Test
    void bindsEachStateIndependently() {
        final Map<ClipRole, String> authored = new EnumMap<>(ClipRole.class);
        authored.put(ClipRole.IDLE, "idle");
        authored.put(ClipRole.WALK, "walk");
        final RigClips clips = RigClips.of(authored);

        // Mutating the map afterwards must not reach the value object.
        authored.put(ClipRole.RUN, "running");

        assertAll(
                () -> assertEquals(2, clips.size()),
                () -> assertFalse(clips.declares(ClipRole.RUN)),
                () -> assertEquals("idle", clips.asMap().get(ClipRole.IDLE)),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> clips.asMap().put(ClipRole.RUN, "running")));
    }

    @Test
    void refusesABlankClipName() {
        // A blank name reaches the renderer as a lookup for nothing, so it is a binding that cannot
        // work rather than an empty one. A rig file gets a diagnostic and an unbound state instead.
        assertThrows(IllegalArgumentException.class, () -> RigClips.of(ClipRole.IDLE, "   "));
    }

    @Test
    void namesNoClipAtAllByDefault() {
        assertAll(
                () -> assertTrue(RigClips.NONE.isEmpty()),
                () -> assertEquals(0, RigClips.NONE.size()),
                // An empty map is the same answer, so it is the same instance.
                () -> assertSame(RigClips.NONE, RigClips.of(Map.of())),
                () -> assertSame(RigClips.NONE,
                        Rig.builder("r", "m", RigFixtures.rigBounds(),
                                RigFixtures.wideProfileBounds()).root("body").build().clips()),
                () -> assertSame(RigClips.NONE, Rig.modelOnly("m").clips()));
    }

    @Test
    void parsesARoleLenientlyAndRefusesAStateNothingCanBeIn() {
        assertAll(
                () -> assertEquals(ClipRole.IDLE, ClipRole.parse(" IDLE ")),
                () -> assertEquals(ClipRole.RUN, ClipRole.parse("Run")),
                // Three states, because three is what the gait layer can distinguish. A fourth would
                // be a clip that could never be chosen.
                () -> assertEquals(3, ClipRole.COUNT),
                () -> assertEquals("idle, walk, run", ClipRole.names()),
                () -> assertThrows(IllegalArgumentException.class, () -> ClipRole.parse("sprint")));
    }

    @Test
    void reportsWhichBonesAShippedClipAlreadyDrives() {
        // The list the composition layer needs: on these bones the renderer is already writing the
        // display, so our pose has to be composed against the animation rather than the bind pose.
        final Rig rig = Rig.builder("deer", "deer_model", RigFixtures.rigBounds(),
                        RigFixtures.wideProfileBounds())
                .bone("torso", null, null, true)
                .bone("neck", "torso", null, false)
                .bone("head", "neck", null, true)
                .clips(RigClips.of(ClipRole.IDLE, "idle"))
                .build();

        assertAll(
                () -> assertTrue(rig.bone(0).animationOwned()),
                () -> assertFalse(rig.bone(1).animationOwned()),
                () -> assertEquals(2, rig.animationOwnedBoneCount()),
                // Ascending, and therefore topological, like the physics indices.
                () -> assertArrayEquals(new int[]{0, 2}, rig.animationOwnedBoneIndices()),
                () -> assertEquals("idle", rig.clips().clip(ClipRole.IDLE)));
    }

    @Test
    void treatsABoneAsNotAnimationOwnedUnlessToldOtherwise() {
        // The default a rig written before the flag existed gets, and the only one that cannot be
        // wrong: nothing here can look inside a .bbmodel to find out.
        final Rig rig = Rig.builder("r", "m", RigFixtures.rigBounds(), RigFixtures.wideProfileBounds())
                .root("body")
                .bone("tail", "body", null)
                .build();

        assertAll(
                () -> assertFalse(rig.bone(0).animationOwned()),
                () -> assertFalse(rig.bone(1).animationOwned()),
                () -> assertEquals(0, rig.animationOwnedBoneCount()),
                () -> assertFalse(Rig.modelOnly("m", "root").bone(0).animationOwned()));
    }
}
