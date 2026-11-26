package pandastic.studio.models.internal.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one piece of instrumentation the status commands added.
 *
 * <h2>Why it is worth a suite of its own</h2>
 * Because the state it exists to make visible is the one state nothing else on the server reports. A
 * refused reload leaves the previous values live <em>by identity</em>, so a server running on files its
 * last reload rejected is indistinguishable from a server nobody has edited — and the configuration layer
 * keeps no history, correctly, because a reader on the simulation thread has no use for one. If this
 * class records the wrong outcome the health command confidently says the opposite of the truth.
 *
 * <p>The reload results are real, loaded from really-written files, for the reason
 * {@link CommandFixtures} documents: {@code ConfigReloadResult}'s factories are package-private to the
 * configuration layer because "applied" and "refused" are that layer's conclusions to draw.
 */
class ReloadHistoryTest {

    @Test
    void nothingObservedIsNotTheSameClaimAsAReloadThatWorked() {
        final ReloadHistory history = new ReloadHistory();

        final StatusSnapshot.Configuration described = history.describe(3L);

        assertAll(
                () -> assertTrue(history.last().isEmpty()),
                () -> assertEquals(ReloadOutcome.UNKNOWN, described.lastReload()),
                // The live revision is still reported: it is a fact the caller has and this class
                // does not.
                () -> assertEquals(3L, described.revision()),
                () -> assertEquals(StatusSnapshot.State.OK, described.state()));
    }

    @Test
    void anAppliedReloadIsRecordedWithNoProblems(@TempDir Path directory) {
        final ReloadHistory history = new ReloadHistory();

        history.record(CommandFixtures.applied(directory));

        assertAll(
                () -> assertEquals(ReloadOutcome.APPLIED, history.last().orElseThrow().outcome()),
                () -> assertEquals(0, history.last().orElseThrow().problems()),
                () -> assertFalse(history.last().orElseThrow().isTrouble()));
    }

    @Test
    void aRefusedReloadIsRecordedWithEveryProblemItProduced(@TempDir Path directory) {
        final ReloadHistory history = new ReloadHistory();

        history.record(CommandFixtures.refused(directory));
        final ReloadHistory.Entry entry = history.last().orElseThrow();

        assertAll(
                () -> assertEquals(ReloadOutcome.REFUSED, entry.outcome()),
                // Two files were broken, so a count of one would mean the report under-states what the
                // operator has to go and fix.
                () -> assertTrue(entry.problems() >= 2, entry.toString()),
                () -> assertTrue(entry.isTrouble()),
                () -> assertEquals(StatusSnapshot.State.DEGRADED,
                        history.describe(1L).state()));
    }

    @Test
    void theLiveRevisionWinsOverTheRecordedOne(@TempDir Path directory) {
        final ReloadHistory history = new ReloadHistory();
        history.record(CommandFixtures.applied(directory));

        // They differ only if something published a snapshot without going through this history, and in
        // that case the number an operator wants is the one in force rather than the one last seen.
        assertEquals(99L, history.describe(99L).revision());
    }

    @Test
    void theWrappedOperationIsUnchangedAndItsOutcomeIsRecorded(@TempDir Path directory) {
        final ReloadHistory history = new ReloadHistory();
        final ReloadReport report = new ReloadReport(CommandFixtures.applied(directory), 4);

        final ReloadReport returned = history.observing(CommandFixtures.returning(report)).perform();

        assertAll(
                // The decorator must not touch what the command gets back: the rig count and the result
                // are what the reply is built from.
                () -> assertSame(report, returned),
                () -> assertEquals(ReloadOutcome.APPLIED, history.last().orElseThrow().outcome()));
    }

    @Test
    void aReloadThatThrewIsRecordedAndRethrownUnchanged() {
        final ReloadHistory history = new ReloadHistory();
        final RuntimeException failure = new IllegalStateException("the directory is unreadable");

        final RuntimeException thrown = assertThrows(IllegalStateException.class,
                () -> history.observing(CommandFixtures.throwing(failure)).perform());

        assertAll(
                // Swallowing it would turn a reload that threw into a reload that silently did nothing,
                // and the command's own failure path is what reports it.
                () -> assertSame(failure, thrown),
                () -> assertEquals(ReloadOutcome.FAILED, history.last().orElseThrow().outcome()),
                // A reload that threw may not have reached a point where a revision exists.
                () -> assertFalse(history.last().orElseThrow().hasRevision()),
                () -> assertEquals(StatusSnapshot.State.DEGRADED, history.describe(2L).state()));
    }

    @Test
    void onlyTheLastReloadIsKept(@TempDir Path directory) {
        final ReloadHistory history = new ReloadHistory();

        // In that order, because the refusal fixture is the one that breaks the files: a load of
        // defaults afterwards would be refused by the same two keys and would be asserting the
        // fixture rather than the history.
        history.record(CommandFixtures.applied(directory));
        history.record(CommandFixtures.refused(directory));

        assertEquals(ReloadOutcome.REFUSED, history.last().orElseThrow().outcome());
    }

    @Test
    void aRefusalWithNothingToSayIsRefusedRatherThanRecorded() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReloadHistory.Entry(ReloadOutcome.REFUSED, 1L, 0));
    }
}
