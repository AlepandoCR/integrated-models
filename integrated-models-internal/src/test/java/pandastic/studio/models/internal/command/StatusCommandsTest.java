package pandastic.studio.models.internal.command;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two status commands: what they send, how often they look, and what they are gated on.
 *
 * <h2>Why there is so little here</h2>
 * Because both commands are two statements over seams that are tested elsewhere: one read of the
 * settings, one capture, and one line sent per line the reporter produced. That is deliberate — the
 * decisions worth testing are the verdict's and the reporter's, and a command that held any of them would
 * need a server to exercise them. What is left is exactly the part a seam cannot cover: that the capture
 * happens once, and that each command replies to whoever typed it.
 */
class StatusCommandsTest {

    @Test
    void theHealthCommandSendsEveryLineTheReporterProduced() {
        final RecordingAudience replies = new RecordingAudience();
        final StatusSettings settings = StatusFixtures.withoutGaps();

        health(StatusFixtures.healthy(), settings).run(replies);

        assertAll(
                () -> assertEquals(
                        StatusReport.health(StatusFixtures.healthy(), settings).size(),
                        replies.count()),
                () -> assertTrue(replies.joined().contains("healthy"), replies.joined()));
    }

    @Test
    void theStatisticsCommandSendsEveryLineTheReporterProduced() {
        final RecordingAudience replies = new RecordingAudience();
        final StatusSettings settings = StatusFixtures.verbose();

        stats(StatusFixtures.healthy(), settings).run(replies);

        assertAll(
                () -> assertEquals(
                        StatusReport.statistics(StatusFixtures.healthy(), settings).size(),
                        replies.count()),
                () -> assertTrue(replies.joined().contains("bones posed"), replies.joined()));
    }

    @Test
    void aSilencedReportSendsNothingAtAllRatherThanAnEmptyLine() {
        final RecordingAudience replies = new RecordingAudience();

        health(StatusFixtures.healthy(), StatusSettings.SILENT).run(replies);
        stats(StatusFixtures.healthy(), StatusSettings.SILENT).run(replies);

        assertEquals(0, replies.count());
    }

    @Test
    void oneInvocationCapturesExactlyOnce() {
        final AtomicInteger captures = new AtomicInteger();
        final StatusSource counting = () -> {
            captures.incrementAndGet();
            return StatusFixtures.healthy();
        };

        new HealthCommand(counting, CommandSettingsSource.fixed(
                settingsWith(StatusFixtures.verbose()))).run(new RecordingAudience());

        // Two captures would let a bind land between the title and the row that counts it, which is the
        // mixed-generation read this project avoids everywhere else — and in a report it is the kind of
        // inconsistency an operator spends an afternoon on.
        assertEquals(1, captures.get());
    }

    @Test
    void theTwoCommandsAreGatedSeparatelyAndNeitherSharesTheReloadsNode() {
        assertAll(
                () -> assertNotEquals(HealthCommand.PERMISSION, StatsCommand.PERMISSION),
                () -> assertNotEquals(ReloadCommand.PERMISSION, HealthCommand.PERMISSION),
                () -> assertNotEquals(ReloadCommand.PERMISSION, StatsCommand.PERMISSION),
                // Undeclared nodes, which is what makes them operator-only by Bukkit's own default.
                () -> assertTrue(HealthCommand.PERMISSION.startsWith("integratedmodels.command.")),
                () -> assertTrue(StatsCommand.PERMISSION.startsWith("integratedmodels.command.")));
    }

    @Test
    void theLabelsDoNotCollideWithEachOtherOrWithTheExistingCommands() {
        assertAll(
                () -> assertNotEquals(HealthCommand.NAME, StatsCommand.NAME),
                () -> assertNotEquals(HealthCommand.NAME, ReloadCommand.NAME),
                () -> assertNotEquals(HealthCommand.NAME, MenuCommand.NAME),
                () -> assertNotEquals(StatsCommand.NAME, ReloadCommand.NAME),
                () -> assertNotEquals(StatsCommand.NAME, MenuCommand.NAME),
                () -> assertTrue(java.util.Collections.disjoint(
                        HealthCommand.ALIASES, StatsCommand.ALIASES)),
                () -> assertTrue(java.util.Collections.disjoint(
                        HealthCommand.ALIASES, ReloadCommand.ALIASES)),
                () -> assertTrue(java.util.Collections.disjoint(
                        StatsCommand.ALIASES, MenuCommand.ALIASES)));
    }

    @Test
    void thePermissionIsWhatTheServerIsAskedFor() {
        assertAll(
                () -> assertEquals(HealthCommand.PERMISSION,
                        health(StatusFixtures.healthy(), StatusFixtures.settings()).permission()),
                () -> assertEquals(StatsCommand.PERMISSION,
                        stats(StatusFixtures.healthy(), StatusFixtures.settings()).permission()));
    }

    private static HealthCommand health(StatusSnapshot status, StatusSettings settings) {
        return new HealthCommand(StatusSource.fixed(status),
                CommandSettingsSource.fixed(settingsWith(settings)));
    }

    private static StatsCommand stats(StatusSnapshot status, StatusSettings settings) {
        return new StatsCommand(StatusSource.fixed(status),
                CommandSettingsSource.fixed(settingsWith(settings)));
    }

    /** The declared defaults with one status section swapped in, so only the wording under test moves. */
    private static CommandSettings settingsWith(StatusSettings status) {
        final CommandSettings defaults = CommandFixtures.defaults();
        return new CommandSettings(
                defaults.menuNotPlayerMessage(),
                defaults.startedMessage(),
                defaults.appliedMessage(),
                defaults.rebuildMessage(),
                defaults.refusedMessage(),
                defaults.diagnosticLine(),
                defaults.busyMessage(),
                defaults.failedMessage(),
                status);
    }
}
