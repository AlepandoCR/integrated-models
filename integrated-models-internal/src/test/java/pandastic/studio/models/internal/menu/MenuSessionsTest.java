package pandastic.studio.models.internal.menu;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The timeout, and why there has to be one.
 *
 * <p><strong>Nothing reports that a dialog was closed.</strong> Not escape, not clicking away, not a
 * client disconnecting mid-screen. So a session has no end event, the only end it can have is a clock,
 * and these tests are the clock. Every assertion below is about state going away when no event said it
 * should.
 */
class MenuSessionsTest {

    @TempDir
    Path directory;

    private final SteppedClock clock = new SteppedClock();

    private MenuSessions sessions(String... lines) {
        return new MenuSessions(
                MenuSettingsSource.fixed(MenuFixtures.fromFile(this.directory, lines)), this.clock);
    }

    // ------------------------------------------------------------------ the lease

    @Test
    void openingTwiceHandsBackTheSameSession() {
        final MenuSessions sessions = sessions("session.timeoutSeconds: 60");
        final UUID player = UUID.randomUUID();
        assertSame(sessions.open(player), sessions.open(player));
    }

    @Test
    void aSessionSurvivesUntilTheConfiguredTimeout() {
        final MenuSessions sessions = sessions("session.timeoutSeconds: 60");
        final UUID player = UUID.randomUUID();
        final MenuSession opened = sessions.open(player);
        opened.pickerPage(4);

        this.clock.advanceSeconds(59);
        assertAll(
                () -> assertTrue(sessions.find(player).isPresent()),
                () -> assertEquals(4, sessions.find(player).orElseThrow().pickerPage()));
    }

    @Test
    void aSessionIsForgottenOnceTheTimeoutHasPassed() {
        final MenuSessions sessions = sessions("session.timeoutSeconds: 60");
        final UUID player = UUID.randomUUID();
        sessions.open(player).pickerPage(4);

        this.clock.advanceSeconds(60);
        assertAll(
                () -> assertTrue(sessions.find(player).isEmpty()),
                () -> assertEquals(0, sessions.size()));
    }

    @Test
    void aConfiguredTimeoutIsHonouredRatherThanADefault() {
        // A deliberately odd value, because the test would pass against a hardcoded 180 if the timeout
        // happened to be longer than whatever the test advanced by.
        final MenuSessions sessions = sessions("session.timeoutSeconds: 1.5");
        final UUID player = UUID.randomUUID();
        sessions.open(player);

        this.clock.advanceSeconds(1.4);
        assertTrue(sessions.find(player).isPresent());

        this.clock.advanceSeconds(0.2);
        assertTrue(sessions.find(player).isEmpty());
    }

    @Test
    void openingRestartsTheTimeout() {
        final MenuSessions sessions = sessions("session.timeoutSeconds: 10");
        final UUID player = UUID.randomUUID();
        sessions.open(player);

        this.clock.advanceSeconds(9);
        sessions.open(player);
        this.clock.advanceSeconds(9);

        assertTrue(sessions.find(player).isPresent());
    }

    @Test
    void lookingDoesNotRestartTheTimeout() {
        // find() is for a caller that wants to know whether a player is mid-menu. If it extended the
        // lease, a status check would keep a session alive forever and the timeout would mean nothing.
        final MenuSessions sessions = sessions("session.timeoutSeconds: 10");
        final UUID player = UUID.randomUUID();
        sessions.open(player);

        this.clock.advanceSeconds(9);
        assertTrue(sessions.find(player).isPresent());
        this.clock.advanceSeconds(1);

        assertTrue(sessions.find(player).isEmpty());
    }

    @Test
    void aForgottenSessionComesBackEmptyRatherThanWithItsOldPage() {
        final MenuSessions sessions = sessions("session.timeoutSeconds: 10");
        final UUID player = UUID.randomUUID();
        final MenuSession first = sessions.open(player);
        first.pickerPage(7);

        this.clock.advanceSeconds(11);
        final MenuSession second = sessions.open(player);
        assertAll(
                () -> assertNotSame(first, second),
                () -> assertEquals(0, second.pickerPage()));
    }

    // ------------------------------------------------------------------ the only deterministic end

    @Test
    void closingForgetsTheSessionImmediately() {
        final MenuSessions sessions = sessions("session.timeoutSeconds: 600");
        final UUID player = UUID.randomUUID();
        sessions.open(player);

        assertAll(
                () -> assertTrue(sessions.close(player)),
                () -> assertEquals(0, sessions.size()),
                () -> assertFalse(sessions.close(player)));
    }

    @Test
    void clearingForgetsEveryone() {
        final MenuSessions sessions = sessions("session.timeoutSeconds: 600");
        sessions.open(UUID.randomUUID());
        sessions.open(UUID.randomUUID());

        sessions.clear();
        assertEquals(0, sessions.size());
    }

    // ------------------------------------------------------------------ pruning and the ceiling

    @Test
    void pruningReportsHowManyItDropped() {
        final MenuSessions sessions = sessions("session.timeoutSeconds: 10");
        sessions.open(UUID.randomUUID());
        sessions.open(UUID.randomUUID());
        final UUID recent = UUID.randomUUID();

        this.clock.advanceSeconds(9);
        sessions.open(recent);
        this.clock.advanceSeconds(2);

        assertAll(
                () -> assertEquals(2, sessions.prune()),
                () -> assertEquals(1, sessions.size()),
                () -> assertTrue(sessions.find(recent).isPresent()));
    }

    @Test
    void theCeilingDropsTheLeastRecentlyUsedSession() {
        final MenuSessions sessions = sessions(
                "session.timeoutSeconds: 600", "session.maxSessions: 2");
        final UUID oldest = UUID.randomUUID();
        final UUID middle = UUID.randomUUID();
        final UUID newest = UUID.randomUUID();

        sessions.open(oldest);
        this.clock.advanceSeconds(1);
        sessions.open(middle);
        this.clock.advanceSeconds(1);
        sessions.open(newest);

        assertAll(
                () -> assertEquals(2, sessions.size()),
                () -> assertTrue(sessions.find(oldest).isEmpty()),
                () -> assertTrue(sessions.find(middle).isPresent()),
                () -> assertTrue(sessions.find(newest).isPresent()));
    }

    @Test
    void theCeilingNeverDropsTheSessionThatCausedThePass() {
        // Otherwise a server at its ceiling could not open a menu at all: every attempt would
        // immediately discard its own state and the player would see a screen with nothing remembered.
        final MenuSessions sessions = sessions(
                "session.timeoutSeconds: 600", "session.maxSessions: 1");
        final UUID first = UUID.randomUUID();
        final UUID second = UUID.randomUUID();

        sessions.open(first);
        this.clock.advanceSeconds(1);
        final MenuSession opened = sessions.open(second);
        opened.pickerPage(3);

        assertAll(
                () -> assertEquals(1, sessions.size()),
                () -> assertTrue(sessions.find(second).isPresent()),
                () -> assertEquals(3, sessions.find(second).orElseThrow().pickerPage()));
    }

    @Test
    void expiredSessionsAreDroppedBeforeTheCeilingIsApplied() {
        // Pruning first means the ceiling is measured against what is still live, so a burst of openers
        // does not evict a session that was about to be swept anyway.
        final MenuSessions sessions = sessions(
                "session.timeoutSeconds: 5", "session.maxSessions: 2");
        final UUID stale = UUID.randomUUID();
        sessions.open(stale);

        this.clock.advanceSeconds(6);
        final UUID live = UUID.randomUUID();
        sessions.open(live);

        assertAll(
                () -> assertEquals(1, sessions.size()),
                () -> assertTrue(sessions.find(stale).isEmpty()));
    }

    // ------------------------------------------------------------------ the one-shot notice

    @Test
    void aNoticeIsDeliveredOnceAndThenGone() {
        // It describes the click that produced the next screen, so leaving it in place would have
        // "Model removed" still on screen three pages later.
        final MenuSessions sessions = sessions("session.timeoutSeconds: 600");
        final MenuSession session = sessions.open(UUID.randomUUID());
        session.notice(Component.text("done"));

        assertAll(
                () -> assertEquals("done", FakeMenuSurface.plain(session.takeNotice())),
                () -> assertNull(session.takeNotice()));
    }

    @Test
    void aSelectionIsRememberedUntilItIsReplaced() {
        final MenuSessions sessions = sessions("session.timeoutSeconds: 600");
        final MenuSession session = sessions.open(UUID.randomUUID());

        assertAll(
                () -> assertTrue(session.selectedModel().isEmpty()),
                () -> {
                    session.selectedModel("dragon");
                    assertEquals("dragon", session.selectedModel().orElseThrow());
                },
                () -> {
                    session.selectedModel(null);
                    assertTrue(session.selectedModel().isEmpty());
                });
    }
}
