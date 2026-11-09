package pandastic.studio.models.internal.command;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pandastic.studio.models.internal.config.ConfigFile;
import pandastic.studio.models.internal.config.ConfigReloadResult;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reload command: what it says, where it says it from, and what it refuses to do twice at once.
 *
 * <h2>What is worth testing here</h2>
 * Not the reload — that belongs to the configuration layer and the runtime, both of which have their own
 * suites. What is only decidable here is the four things the command adds: that nothing is read on the
 * thread that invoked it, that two operators cannot start two reloads, that a refusal names every
 * problem rather than claiming success, and that every line sent is the operator's own text rather than
 * something left in the source.
 *
 * <p>The last one is why every template in {@link CommandFixtures} is deliberately unlike anything a
 * default says: a literal that survived in the command would not match, so the assertion fails rather
 * than passing on a string that merely reads correctly.
 */
class ReloadCommandTest {

    @TempDir
    Path directory;

    /** Runs the body on the calling thread. For every test whose subject is not the dispatch itself. */
    private static final Executor DIRECT = Runnable::run;

    private @NotNull CommandSettings odd() {
        return CommandFixtures.odd(this.directory.resolve("text"));
    }

    private @NotNull ReloadReport appliedReport(int rigs) {
        return new ReloadReport(CommandFixtures.applied(this.directory.resolve("applied")), rigs);
    }

    // ---------------------------------------------------------------- the configured text

    @Test
    void anAppliedReloadReportsTheCountsThroughTheConfiguredLines() {
        final RecordingAudience replies = new RecordingAudience();
        final ReloadCommand command = new ReloadCommand(
                CommandSettingsSource.fixed(odd()),
                CommandFixtures.returning(appliedReport(7)),
                DIRECT,
                CommandFailureSink.DISCARDING);

        command.run(replies);

        assertAll(
                // Two lines: the acknowledgement, then the result. A reload that needed no rebuild says
                // nothing about one.
                () -> assertEquals(2, replies.count(), replies.joined()),
                () -> assertEquals(CommandFixtures.STARTED, replies.lines().get(0)),
                () -> assertEquals("QQ-applied-QQ files=" + ConfigFile.values().length
                        + " rigs=7 rev=1", replies.lines().get(1)));
    }

    @Test
    void theRigCountComesFromTheReportRatherThanFromAnywhereElse() {
        final RecordingAudience replies = new RecordingAudience();
        new ReloadCommand(
                CommandSettingsSource.fixed(odd()),
                CommandFixtures.returning(appliedReport(0)),
                DIRECT,
                CommandFailureSink.DISCARDING).run(replies);

        // Zero is the answer a server with an empty rig directory gets, and it has to be reported as a
        // number rather than omitted: "0 rig(s) loaded" is the whole explanation for a model that will
        // not bind.
        assertTrue(replies.joined().contains("rigs=0"), replies.joined());
    }

    @Test
    void aSubstepRateChangeAddsTheRebuildLine() {
        final RecordingAudience replies = new RecordingAudience();
        final ConfigReloadResult rebuilt =
                CommandFixtures.rebuilding(this.directory.resolve("rebuild"));
        new ReloadCommand(
                CommandSettingsSource.fixed(odd()),
                CommandFixtures.returning(new ReloadReport(rebuilt, 2)),
                DIRECT,
                CommandFailureSink.DISCARDING).run(replies);

        assertAll(
                () -> assertTrue(rebuilt.requiresSimulationRebuild()),
                () -> assertEquals(3, replies.count(), replies.joined()),
                () -> assertEquals(CommandFixtures.REBUILD, replies.lines().get(2)));
    }

    @Test
    void anEmptiedLineIsOmittedRatherThanSentBlank() {
        // Blank is how an operator turns a line off; there is no second key saying whether the first
        // applies. Both optional lines are emptied here, and the result is one line rather than three.
        final RecordingAudience replies = new RecordingAudience();
        final CommandSettings quiet = CommandFixtures.fromFile(this.directory.resolve("quiet"),
                "reload:",
                "  startedMessage: ''",
                "  rebuildMessage: ''");
        final ConfigReloadResult rebuilt =
                CommandFixtures.rebuilding(this.directory.resolve("quiet-rebuild"));

        new ReloadCommand(
                CommandSettingsSource.fixed(quiet),
                CommandFixtures.returning(new ReloadReport(rebuilt, 1)),
                DIRECT,
                CommandFailureSink.DISCARDING).run(replies);

        assertAll(
                () -> assertFalse(quiet.hasStartedMessage()),
                () -> assertFalse(quiet.hasRebuildMessage()),
                () -> assertEquals(1, replies.count(), replies.joined()));
    }

    @Test
    void theSettingsAreReadOnceSoOneReplyCannotMixTwoGenerations() {
        // A reload can edit this command's own wording, so a source read per line could answer the
        // header from one generation and the result from the next. One read per invocation makes that
        // unrepresentable, and the way to prove it is a source that changes on every call.
        final RecordingAudience replies = new RecordingAudience();
        final CommandSettings first = odd();
        final CommandSettings second = CommandFixtures.fromFile(this.directory.resolve("second"),
                "reload:",
                "  startedMessage: 'second-started'",
                "  appliedMessage: 'second-applied'");
        final AtomicInteger reads = new AtomicInteger();
        final CommandSettingsSource alternating =
                () -> reads.getAndIncrement() == 0 ? first : second;

        new ReloadCommand(
                alternating,
                CommandFixtures.returning(appliedReport(1)),
                DIRECT,
                CommandFailureSink.DISCARDING).run(replies);

        assertAll(
                () -> assertEquals(1, reads.get(), "the source was read more than once"),
                () -> assertTrue(replies.lines().get(0).startsWith("QQ-started"), replies.joined()),
                () -> assertTrue(replies.lines().get(1).startsWith("QQ-applied"), replies.joined()));
    }

    // ---------------------------------------------------------------- a refusal

    @Test
    void aRefusedReloadNamesEveryProblemAndClaimsNoSuccess() {
        final RecordingAudience replies = new RecordingAudience();
        final ConfigReloadResult refused = CommandFixtures.refused(this.directory.resolve("refused"));
        new ReloadCommand(
                CommandSettingsSource.fixed(odd()),
                CommandFixtures.returning(new ReloadReport(refused, 3)),
                DIRECT,
                CommandFailureSink.DISCARDING).run(replies);

        final String sent = replies.joined();
        assertAll(
                () -> assertFalse(refused.applied()),
                () -> assertFalse(sent.contains("QQ-applied"), sent),
                // The acknowledgement, the header, and one line per diagnostic.
                () -> assertEquals(2 + refused.diagnostics().size(), replies.count(), sent),
                () -> assertEquals("QQ-refused-QQ count=" + refused.diagnostics().size()
                        + " rev=1", replies.lines().get(1)),
                // Every one of them, each naming its file and its key — which is the entire point of
                // the validation layer, and the only thing that tells the operator what to go and edit.
                () -> assertTrue(sent.contains("QQ-problem-QQ render.yml|emissionHz|"), sent),
                () -> assertTrue(sent.contains("QQ-problem-QQ binding.yml|samplePeriodTicks|"), sent));
    }

    @Test
    void aWholeFileProblemLeavesTheKeyPlaceholderEmptyRatherThanSayingNull() {
        // A diagnostic about a file as a whole carries no key. The placeholder is substituted with
        // nothing, because the word "null" in an operator's message is a bug report waiting to happen.
        final RecordingAudience replies = new RecordingAudience();
        final ConfigReloadResult wholeFile =
                CommandFixtures.corrupted(this.directory.resolve("corrupt"));

        new ReloadCommand(
                CommandSettingsSource.fixed(odd()),
                CommandFixtures.returning(new ReloadReport(wholeFile, 0)),
                DIRECT,
                CommandFailureSink.DISCARDING).run(replies);

        final String sent = replies.joined();
        assertAll(
                () -> assertTrue(wholeFile.diagnostics().stream().anyMatch(d -> d.key() == null)),
                () -> assertTrue(sent.contains("simulation.yml||"), sent),
                () -> assertFalse(sent.contains("null"), sent));
    }

    // ---------------------------------------------------------------- threading

    @Test
    void nothingIsReadOnTheThreadThatTypedIt() {
        // The reload reads ten files. A command body runs on a region thread, so the work has to be
        // handed somewhere else — and the way to prove it was is an executor that never runs anything.
        final RecordingAudience replies = new RecordingAudience();
        final Deque<Runnable> queued = new ArrayDeque<>();
        final AtomicInteger performed = new AtomicInteger();
        final ReloadCommand command = new ReloadCommand(
                CommandSettingsSource.fixed(odd()),
                () -> {
                    performed.incrementAndGet();
                    return appliedReport(4);
                },
                queued::add,
                CommandFailureSink.DISCARDING);

        command.run(replies);

        assertAll(
                () -> assertEquals(0, performed.get(), "the reload ran on the calling thread"),
                () -> assertEquals(1, queued.size()),
                // The acknowledgement is sent before the dispatch, so it cannot arrive after the result
                // it announces.
                () -> assertEquals(List.of(CommandFixtures.STARTED), replies.lines()));

        queued.removeFirst().run();

        assertAll(
                () -> assertEquals(1, performed.get()),
                () -> assertTrue(replies.joined().contains("QQ-applied"), replies.joined()));
    }

    @Test
    void theResultIsReportedFromTheExecutorThreadWithNoHopBack() throws InterruptedException {
        // Sending a message is region-independent, so the reply needs no return trip. This asserts that
        // there is not one: the line arrives on the thread the reload ran on.
        final RecordingAudience replies = new RecordingAudience();
        final AtomicReference<Thread> reportedOn = new AtomicReference<>();
        final List<Thread> workers = new ArrayList<>();
        final Executor onItsOwnThread = body -> {
            final Thread worker = new Thread(body, "reload-test-worker");
            workers.add(worker);
            worker.start();
        };

        new ReloadCommand(
                CommandSettingsSource.fixed(odd()),
                () -> {
                    reportedOn.set(Thread.currentThread());
                    return appliedReport(1);
                },
                onItsOwnThread,
                CommandFailureSink.DISCARDING).run(replies);

        for (final Thread worker : workers) {
            worker.join();
        }

        assertAll(
                () -> assertEquals(1, workers.size()),
                () -> assertSame(workers.get(0), reportedOn.get()),
                () -> assertNotSame(Thread.currentThread(), reportedOn.get()),
                () -> assertTrue(replies.joined().contains("QQ-applied"), replies.joined()));
    }

    @Test
    void anExecutorThatRefusesTheTaskIsReportedRatherThanSwallowed() {
        final RecordingAudience replies = new RecordingAudience();
        final List<String> logged = new ArrayList<>();
        final RejectedExecutionException rejected = new RejectedExecutionException("pool is shut down");

        new ReloadCommand(
                CommandSettingsSource.fixed(odd()),
                CommandFixtures.returning(appliedReport(1)),
                body -> {
                    throw rejected;
                },
                (message, thrown) -> logged.add(message)).run(replies);

        assertAll(
                () -> assertTrue(replies.joined().contains("QQ-failed-QQ pool is shut down"),
                        replies.joined()),
                () -> assertEquals(1, logged.size()));
    }

    // ---------------------------------------------------------------- the gate

    @Test
    void aSecondInvocationWhileOneIsRunningIsRefusedRatherThanQueued() {
        // Two operators at once. The reload is reentered from inside the first one, which is exactly
        // the state the second arrives in and the only way to observe it deterministically.
        final RecordingAudience first = new RecordingAudience();
        final RecordingAudience second = new RecordingAudience();
        final AtomicReference<ReloadCommand> self = new AtomicReference<>();
        final AtomicInteger performed = new AtomicInteger();

        final ReloadCommand command = new ReloadCommand(
                CommandSettingsSource.fixed(odd()),
                () -> {
                    performed.incrementAndGet();
                    self.get().run(second);
                    return appliedReport(5);
                },
                DIRECT,
                CommandFailureSink.DISCARDING);
        self.set(command);

        command.run(first);

        assertAll(
                () -> assertEquals(1, performed.get(), "the reload ran twice"),
                () -> assertTrue(first.joined().contains("QQ-applied"), first.joined()),
                // Turned away rather than queued: no acknowledgement, no result, one sentence.
                () -> assertEquals(List.of(CommandFixtures.BUSY), second.lines()));
    }

    @Test
    void theGateIsLoweredAfterwardsSoTheCommandIsNotOneShot() {
        final RecordingAudience replies = new RecordingAudience();
        final AtomicInteger performed = new AtomicInteger();
        final ReloadCommand command = new ReloadCommand(
                CommandSettingsSource.fixed(odd()),
                () -> {
                    performed.incrementAndGet();
                    return appliedReport(1);
                },
                DIRECT,
                CommandFailureSink.DISCARDING);

        command.run(replies);
        command.run(replies);

        assertAll(
                () -> assertEquals(2, performed.get()),
                () -> assertFalse(replies.joined().contains("QQ-busy"), replies.joined()));
    }

    @Test
    void aReloadThatThrewLowersTheGateAndReportsBothWays() {
        final RecordingAudience replies = new RecordingAudience();
        final List<Throwable> logged = new ArrayList<>();
        final AtomicInteger attempts = new AtomicInteger();
        final ReloadCommand command = new ReloadCommand(
                CommandSettingsSource.fixed(odd()),
                () -> {
                    attempts.incrementAndGet();
                    throw new IllegalStateException("the rig directory is unreadable");
                },
                DIRECT,
                (message, thrown) -> logged.add(thrown));

        command.run(replies);
        command.run(replies);

        assertAll(
                // The operator gets a sentence and the console gets the stack trace: a failure that is
                // not a refusal is usually a defect, and the sentence alone names neither layer nor line.
                () -> assertTrue(replies.joined()
                        .contains("QQ-failed-QQ the rig directory is unreadable"), replies.joined()),
                () -> assertEquals(2, logged.size()),
                () -> assertEquals(2, attempts.get(), "a failure left the gate up"),
                () -> assertFalse(replies.joined().contains("QQ-busy"), replies.joined()));
    }

    // ---------------------------------------------------------------- the platform surface

    @Test
    void executeRepliesToTheSenderRatherThanTheExecutor() {
        final RecordingAudience replies = new RecordingAudience();
        final ReloadCommand command = new ReloadCommand(
                CommandSettingsSource.fixed(odd()),
                CommandFixtures.returning(appliedReport(1)),
                DIRECT,
                CommandFailureSink.DISCARDING);

        // Arguments are ignored rather than refused: a reload typed with trailing text is still a
        // reload, and a refusal would be a message key for a mistake with no consequence.
        command.execute(CommandFixtures.source(CommandFixtures.sender(replies)),
                new String[]{"unexpected", "extra"});

        assertTrue(replies.joined().contains("QQ-applied"), replies.joined());
    }

    @Test
    void onlyAHolderOfThePermissionMayUseIt() {
        final RecordingAudience ignored = new RecordingAudience();
        final ReloadCommand command = new ReloadCommand(
                CommandSettingsSource.fixed(odd()),
                CommandFixtures.returning(appliedReport(1)),
                DIRECT,
                CommandFailureSink.DISCARDING);

        assertAll(
                () -> assertEquals("integratedmodels.command.reload", command.permission()),
                () -> assertEquals(ReloadCommand.PERMISSION, command.permission()),
                // canUse is inherited and is exactly what is wanted: it turns permission() into both the
                // execution requirement and the visibility of the command in the client's tree.
                () -> assertTrue(command.canUse(
                        CommandFixtures.sender(ignored, ReloadCommand.PERMISSION))),
                () -> assertFalse(command.canUse(CommandFixtures.sender(ignored))),
                () -> assertFalse(command.canUse(
                        CommandFixtures.sender(ignored, "integratedmodels.command.somethingelse"))));
    }

    @Test
    void theLabelsAreUsableAndDoNotClaimALabelSomebodyElseOwns() {
        assertAll(
                () -> assertEquals("imreload", ReloadCommand.NAME),
                () -> assertFalse(ReloadCommand.ALIASES.isEmpty()),
                () -> assertFalse(ReloadCommand.DESCRIPTION.isBlank()),
                // Not Bukkit's own /reload, and not the root an eventual parent command would want.
                () -> assertFalse(ReloadCommand.NAME.equals("reload")),
                () -> assertFalse(ReloadCommand.ALIASES.contains("reload")),
                () -> assertFalse(ReloadCommand.ALIASES.contains("integratedmodels")),
                () -> assertFalse(ReloadCommand.ALIASES.contains(ReloadCommand.NAME)),
                () -> assertTrue(ReloadCommand.ALIASES.stream()
                        .allMatch(alias -> alias.equals(alias.toLowerCase(Locale.ROOT)))),
                // Immutable, because these are handed to the dispatcher and then kept.
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> ReloadCommand.ALIASES.add("whatever")));
    }

    @Test
    void everySeamIsRequired() {
        assertAll(
                () -> assertThrows(NullPointerException.class, () -> new ReloadCommand(
                        null, CommandFixtures.returning(appliedReport(1)), DIRECT,
                        CommandFailureSink.DISCARDING)),
                () -> assertThrows(NullPointerException.class, () -> new ReloadCommand(
                        CommandSettingsSource.fixed(odd()), null, DIRECT,
                        CommandFailureSink.DISCARDING)),
                () -> assertThrows(NullPointerException.class, () -> new ReloadCommand(
                        CommandSettingsSource.fixed(odd()),
                        CommandFixtures.returning(appliedReport(1)), null,
                        CommandFailureSink.DISCARDING)),
                () -> assertThrows(NullPointerException.class, () -> new ReloadCommand(
                        CommandSettingsSource.fixed(odd()),
                        CommandFixtures.returning(appliedReport(1)), DIRECT, null)));
    }
}
