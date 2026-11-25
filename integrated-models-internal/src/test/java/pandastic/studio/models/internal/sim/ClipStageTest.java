package pandastic.studio.models.internal.sim;

import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.rig.ClipRole;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.api.rig.RigClips;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The half of clip driving that knows about clip names: which one a state resolves to, when the renderer
 * is told, and when it is deliberately not told again.
 */
class ClipStageTest {

    private static final ClipSettings SETTINGS =
            new ClipSettings(true, 0.1F, 0.5F, 0.05F, 0F, 4, 7);

    private static final float STEP_SECONDS = 0.05F;

    /** A rig with the three clips a well-described animal ships. Spellings as a rigger would type them. */
    private static Rig deer() {
        return rigWith(RigClips.of(Map.of(
                ClipRole.IDLE, "idle",
                ClipRole.WALK, "walk",
                ClipRole.RUN, "running")));
    }

    private static Rig rigWith(RigClips clips) {
        return SimFixtures.rig("rig", "model").root("body").clips(clips).build();
    }

    private static ClipStage stageOf(Rig rig, ClipPlayer player) {
        final ClipStage stage = ClipStage.of(rig, ClipSettingsSource.fixed(SETTINGS), player);
        assertTrue(stage != null, "expected a stage for a rig that names clips");
        return stage;
    }

    @Test
    void aRigThatNamesNoClipsGetsNoStageAtAll() {
        // krampus, and every rig written before the section existed. The whole selection rule.
        assertNull(ClipStage.of(
                rigWith(RigClips.NONE), ClipSettingsSource.fixed(SETTINGS), ClipPlayer.NONE));
    }

    @Test
    void playsTheIdleClipWhenTheOwnerIsStandingStill() {
        final RecordingClipPlayer player = new RecordingClipPlayer();
        final ClipStage stage = stageOf(deer(), player);

        assertAll(
                () -> assertTrue(stage.advance(0F, STEP_SECONDS)),
                () -> assertEquals(List.of("play:idle:4:7"), player.calls()),
                () -> assertEquals("idle", stage.playing()),
                () -> assertEquals(ClipRole.IDLE, stage.role()));
    }

    @Test
    void threadsTheConfiguredBlendDurationsThroughToTheRenderer() {
        // The blend is configuration, so it has to arrive at the renderer rather than being whatever the
        // two-argument play happened to use.
        final RecordingClipPlayer player = new RecordingClipPlayer();
        final ClipStage stage = ClipStage.of(
                deer(),
                ClipSettingsSource.fixed(new ClipSettings(true, 0.1F, 0.5F, 0.05F, 0F, 11, 13)),
                player);
        assertTrue(stage != null);

        stage.advance(0F, STEP_SECONDS);

        assertEquals(List.of("play:idle:11:13"), player.calls());
    }

    @Test
    void startsTheReplacementBeforeStoppingWhatItReplaces() {
        // So the renderer has something to blend out of. Reversed, the model snaps to its rest pose for
        // one frame between two clips.
        final RecordingClipPlayer player = new RecordingClipPlayer();
        final ClipStage stage = stageOf(deer(), player);
        stage.advance(0F, STEP_SECONDS);
        player.clear();

        stage.advance(0.9F, STEP_SECONDS);

        assertEquals(List.of("play:running:4:7", "stop:idle"), player.calls());
    }

    @Test
    void doesNothingWhileTheStateIsUnchanged() {
        final RecordingClipPlayer player = new RecordingClipPlayer();
        final ClipStage stage = stageOf(deer(), player);
        stage.advance(0.2F, STEP_SECONDS);
        player.clear();

        for (int sample = 0; sample < 20; sample++) {
            assertFalse(stage.advance(0.2F, STEP_SECONDS));
        }

        assertEquals(List.of(), player.calls());
    }

    @Test
    void isIdempotentByClipNameRatherThanByRole() {
        // A rigger reusing one clip for two roles is ordinary. Crossing the sprint threshold must not
        // restart it, which remembering the role rather than the name would do.
        final RecordingClipPlayer player = new RecordingClipPlayer();
        final ClipStage stage = stageOf(rigWith(RigClips.of(Map.of(
                ClipRole.IDLE, "idle",
                ClipRole.WALK, "walk",
                ClipRole.RUN, "walk"))), player);
        stage.advance(0.2F, STEP_SECONDS);
        player.clear();

        assertAll(
                () -> assertFalse(stage.advance(0.9F, STEP_SECONDS)),
                () -> assertEquals(ClipRole.RUN, stage.role()),
                () -> assertEquals("walk", stage.playing()),
                () -> assertEquals(List.of(), player.calls()));
    }

    @Test
    void fallsBackDownTheChainWhenAStateNamesNoClip() {
        // hollandloppet names idle and walk and nothing else; a sprinting owner gets its walk rather
        // than nothing.
        final RecordingClipPlayer player = new RecordingClipPlayer();
        final ClipStage stage = stageOf(rigWith(RigClips.of(Map.of(
                ClipRole.IDLE, "idle",
                ClipRole.WALK, "walk"))), player);

        stage.advance(0.9F, STEP_SECONDS);

        assertAll(
                () -> assertEquals(ClipRole.RUN, stage.role()),
                () -> assertEquals("walk", stage.playing()),
                () -> assertEquals(List.of("play:walk:4:7"), player.calls()));
    }

    @Test
    void fallsAllTheWayToIdleWhenNothingElseIsNamed() {
        final RecordingClipPlayer player = new RecordingClipPlayer();
        final ClipStage stage = stageOf(rigWith(RigClips.of(ClipRole.IDLE, "idle")), player);

        stage.advance(0.9F, STEP_SECONDS);

        assertEquals("idle", stage.playing());
    }

    @Test
    void playsNothingWhenOnlyAFasterStateIsNamedAndTheOwnerIsStanding() {
        // The one case the fallback cannot help: a rig naming only run, with a standing owner. Nothing
        // is the honest answer — running up the chain would mean a standing model playing a run.
        final RecordingClipPlayer player = new RecordingClipPlayer();
        final ClipStage stage = stageOf(rigWith(RigClips.of(ClipRole.RUN, "running")), player);

        assertAll(
                () -> assertFalse(stage.advance(0F, STEP_SECONDS)),
                () -> assertNull(stage.playing()),
                () -> assertEquals(List.of(), player.calls()));
    }

    @Test
    void stopsEverythingWhenClipDrivingIsTurnedOff() {
        final RecordingClipPlayer player = new RecordingClipPlayer();
        final MutableClipSettings settings = new MutableClipSettings(SETTINGS);
        final ClipStage stage = ClipStage.of(deer(), settings, player);
        assertTrue(stage != null);
        stage.advance(0.2F, STEP_SECONDS);
        player.clear();

        settings.value = new ClipSettings(false, 0.1F, 0.5F, 0.05F, 0F, 4, 7);

        assertAll(
                () -> assertTrue(stage.advance(0.2F, STEP_SECONDS)),
                () -> assertNull(stage.playing()),
                () -> assertEquals(List.of("stop:walk"), player.calls()),
                // And stays stopped rather than reporting a change every sample.
                () -> assertFalse(stage.advance(0.2F, STEP_SECONDS)));
    }

    @Test
    void remembersAClipTheModelDoesNotHaveRatherThanAskingEverySample() {
        // A rig naming a clip the model lacks is a content error. Asking the renderer about it twenty
        // times a second forever is not a useful way to report it.
        final RecordingClipPlayer player = new RecordingClipPlayer(Set.of("idle", "walk"));
        final ClipStage stage = stageOf(deer(), player);
        stage.advance(0.2F, STEP_SECONDS);
        player.clear();

        assertAll(
                () -> assertFalse(stage.advance(0.9F, STEP_SECONDS)),
                () -> assertFalse(stage.advance(0.9F, STEP_SECONDS)),
                () -> assertEquals(List.of("play:running:4:7"), player.calls()),
                () -> assertEquals("running", stage.refused()),
                // The walk it was already playing keeps playing, rather than the model freezing.
                () -> assertEquals("walk", stage.playing()));
    }

    @Test
    void stopReturnsToStandingAndStopsTheClip() {
        final RecordingClipPlayer player = new RecordingClipPlayer();
        final ClipStage stage = stageOf(deer(), player);
        stage.advance(0.9F, STEP_SECONDS);
        player.clear();

        assertAll(
                () -> assertTrue(stage.stop()),
                () -> assertEquals(List.of("stop:running"), player.calls()),
                () -> assertEquals(ClipRole.IDLE, stage.role()),
                () -> assertNull(stage.playing()),
                () -> assertFalse(stage.stop()));
    }

    @Test
    void aFixedSourceIsIdentityStableAcrossReads() {
        // Nothing compares a ClipSettings by identity today, but the project's worst bug was a settings
        // source that allocated per call, so the guarantee is asserted where it is cheap.
        final ClipSettingsSource source = ClipSettingsSource.fixed(SETTINGS);

        assertSame(source.current(), source.current());
    }

    /** A source whose value a test can move, which is what a reload looks like from this side. */
    private static final class MutableClipSettings implements ClipSettingsSource {

        private ClipSettings value;

        private MutableClipSettings(ClipSettings value) {
            this.value = value;
        }

        @Override
        public ClipSettings current() {
            return this.value;
        }
    }
}
