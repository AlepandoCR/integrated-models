package pandastic.studio.models.internal.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.internal.config.ConfigDiagnostic;
import pandastic.studio.models.internal.config.ConfigFile;
import pandastic.studio.models.internal.config.ConfigReloadResult;
import pandastic.studio.models.internal.menu.MenuText;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The one operator action this plugin offers: re-read every configuration file and every rig.
 *
 * <h2>Why it exists</h2>
 * The hot-reload machinery underneath it is complete — all-or-nothing validation, snapshot publishing,
 * diagnostics that name the file and the key, and a rebuild signal for the layers that own a repeating
 * task. Until this command there was no way to trigger any of it from a server console, so the practical
 * way to apply a configuration change was a restart, which made most of that machinery unexercisable.
 *
 * <h2>The name, the aliases and the description are not configuration</h2>
 * Deliberately, and this is the one place in the project where a human-readable literal is not a defect.
 * All three are the command's identity in the server's command dispatcher, and the dispatcher is handed
 * them exactly once, when the plugin enables. A key for any of them would therefore take effect only on
 * the next restart — in the one file whose entire contract is that an edit takes effect without one — so
 * it would be a setting that lies about when it applies. Everything the command <em>says</em> is
 * configuration; what it is <em>called</em> is not.
 *
 * <p>The names themselves were chosen against three constraints:
 * <ul>
 *   <li>Not {@code reload}. Bukkit owns that label, and a plugin registering it would either lose the
 *       collision or shadow a command operators already know.</li>
 *   <li>Not {@code integratedmodels}. That is the natural root for a parent command, and a reload is one
 *       leaf of what such a command would eventually carry; taking the root for the leaf would have to
 *       be undone later.</li>
 *   <li>Short enough to type at a console. {@code imreload} is the plugin's initials and the verb;
 *       {@code im-reload} is the same thing for anyone who reaches for a separator.</li>
 * </ul>
 * Paper also registers the namespaced form, {@code integrated-models:imreload}, which is unambiguous
 * whatever else is installed — so a collision costs an operator a prefix rather than the command.
 *
 * <h2>Why {@link BasicCommand} rather than a Brigadier tree</h2>
 * Because there is nothing to parse. The command takes no arguments, and arguments it is given anyway
 * are ignored rather than refused: a reload typed with trailing text is still a reload, and a refusal
 * would be a message key for a mistake with no consequence. {@code BasicCommand} is the level of the API
 * that says exactly that.
 *
 * <h2>Permission, and why there is no {@code plugin.yml} entry</h2>
 * {@link #PERMISSION} is returned from {@link #permission()}, which the inherited
 * {@link BasicCommand#canUse(org.bukkit.command.CommandSender)} turns into the requirement for both
 * running the command and seeing it in the client's command tree. The node is deliberately
 * <strong>not</strong> declared anywhere: Bukkit's default for a permission no plugin declared is
 * {@code PermissionDefault.OP} — see {@code Permission.DEFAULT_PERMISSION} — so an undeclared node is
 * already operator-only, and granting it to a role remains something a permissions plugin can do. A
 * reload re-dispatches every sampler on the server, so operator-only is the right floor rather than a
 * conservative one.
 *
 * <h2>Threading</h2>
 * {@link ReloadOperation#perform()} reads ten files off disk. A command body runs on a region thread, or
 * on the global one for a console sender, so doing that inline would put blocking I/O on a thread that
 * is ticking a region. The work is handed to an {@link Executor} instead — in production the platform's
 * asynchronous scheduler, through {@link #asyncOn(Plugin)} — and the answer is sent from there.
 *
 * <p><strong>Sending a message is region-independent</strong>, which is why there is no hop back. A
 * player is an {@link Audience} whose {@code sendMessage} writes a packet to that player's connection,
 * and the console's is a log write; neither touches a region's state, so neither needs to be on a
 * particular thread. The next reader will assume the opposite, which is why it is written here.
 *
 * <h2>One reload at a time</h2>
 * Two operators typing this at the same moment must not start two reloads.
 * {@link pandastic.studio.models.internal.config.ConfigService} already serialises its own reloads
 * through a future chain, so the <em>configuration</em> half would be safe — but a reload is more than
 * that: the runtime also re-dispatches four layers' repeating tasks and re-reads the rig directory,
 * clearing the rig map before it refills it. Two of those overlapping would re-dispatch twice and read
 * the directory into a map the other one was emptying.
 *
 * <p>So the second caller is <strong>turned away rather than queued</strong>, through one
 * {@link AtomicBoolean}. Queueing would read the same files the first reload has just read and report to
 * somebody who has stopped looking; being told that a reload is already running is both cheaper and
 * more honest.
 */
public final class ReloadCommand implements BasicCommand {

    /** The command's label. See the class documentation for why this is not a configuration key. */
    public static final String NAME = "imreload";

    /** Alternative labels, registered alongside {@link #NAME}. */
    public static final List<String> ALIASES = List.of("im-reload");

    /** What the server shows beside the command in its own listings. */
    public static final String DESCRIPTION =
            "Re-reads every Integrated Models configuration file and every rig";

    /**
     * The permission required to run it.
     *
     * <p>Undeclared on purpose, which makes it operator-only by default; see the class documentation.
     */
    public static final String PERMISSION = "integratedmodels.command.reload";

    private final @NotNull CommandSettingsSource settings;
    private final @NotNull ReloadOperation operation;
    private final @NotNull Executor executor;
    private final @NotNull CommandFailureSink failures;

    /** Raised for the duration of one reload, so a second caller can be turned away. */
    private final @NotNull AtomicBoolean running = new AtomicBoolean();

    /**
     * A command over its four seams.
     *
     * @param settings  where the reply text is read from, once per invocation
     * @param operation what a reload actually is
     * @param executor  where that work runs; never the calling thread in production
     * @param failures  where a reload that threw is reported, beside the operator's own line
     */
    public ReloadCommand(
            @NotNull CommandSettingsSource settings,
            @NotNull ReloadOperation operation,
            @NotNull Executor executor,
            @NotNull CommandFailureSink failures
    ) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.operation = Objects.requireNonNull(operation, "operation");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.failures = Objects.requireNonNull(failures, "failures");
    }

    /**
     * The platform's asynchronous scheduler, as an {@link Executor}.
     *
     * <p>A plain {@code Executor} rather than an interface of this project's own, because the adapter is
     * one lambda and the JDK already declares the shape. The scheduler is resolved here rather than per
     * dispatch for the reason {@code AsyncSimulationScheduler} documents: its executors are built in the
     * server's constructor, so it is usable at {@code onEnable()} — unlike the main scheduler, which
     * only dispatches once the server ticks, and unlike {@code Bukkit.getScheduler()}, which throws
     * outright on Folia.
     *
     * <p>The returned {@code ScheduledTask} is discarded. That is safe here and would not be for a
     * repeating task: a one-shot async task completes on its own, while region- and entity-scheduled
     * repeating tasks are never swept for a plugin and have to be cancelled by hand.
     *
     * @param plugin the plugin the task is attributed to
     * @return an executor that runs its argument off the ticking threads
     */
    @Contract(value = "_ -> new", pure = true)
    public static @NotNull Executor asyncOn(@NotNull Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        final AsyncScheduler scheduler = Bukkit.getAsyncScheduler();
        return body -> scheduler.runNow(plugin, ignoredTask -> body.run());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Arguments are ignored; see the class documentation. The sender rather than the executor is
     * replied to, because a reload is a server-wide action and the person who asked for it is the one
     * who wants the answer — {@code /execute as @e run imreload} would otherwise report to an entity.
     */
    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String @NotNull [] arguments) {
        run(source.getSender());
    }

    @Override
    public @NotNull String permission() {
        return PERMISSION;
    }

    /**
     * The command's body, over the only thing it needs from a sender.
     *
     * <p>Separate from {@link #execute} so that the decisions here — the gate, the dispatch, the
     * reporting — are exercisable against an {@link Audience} that records what it was sent, with no
     * command source, no sender and no server. {@code execute} is then one statement, which is the
     * amount of untested code that is acceptable at a platform boundary.
     *
     * <p>The settings are read <strong>once</strong>, here, and the value is held for both halves of the
     * invocation. A reload that edits this command's own wording would otherwise answer with a header
     * from one generation and diagnostics from the next.
     *
     * @param recipient whoever typed it
     */
    void run(@NotNull Audience recipient) {
        final CommandSettings text = this.settings.current();

        if (!this.running.compareAndSet(false, true)) {
            recipient.sendMessage(MenuText.of(text.busyMessage()));
            return;
        }

        // Sent from the calling thread, before the dispatch, so that it cannot arrive after the result
        // it is announcing.
        if (text.hasStartedMessage()) {
            recipient.sendMessage(MenuText.of(text.startedMessage()));
        }

        try {
            this.executor.execute(() -> reload(recipient, text));
        } catch (final RuntimeException rejected) {
            // The body never ran, so nothing else will lower the gate.
            this.running.set(false);
            fail(recipient, text, rejected);
        }
    }

    /**
     * Performs the reload and reports it. Runs on the executor's thread, never on a region thread.
     *
     * @param recipient whoever typed it
     * @param text      the wording read when the command was accepted
     */
    private void reload(@NotNull Audience recipient, @NotNull CommandSettings text) {
        try {
            report(recipient, text, this.operation.perform());
        } catch (final RuntimeException failure) {
            // A refusal is not an exception, so anything caught here is a failure the configuration
            // layer does not model: an unreadable directory, or the plugin disabled mid-reload. The
            // previous values are still live either way.
            fail(recipient, text, failure);
        } finally {
            this.running.set(false);
        }
    }

    /**
     * Says what the reload did, in as many lines as it takes.
     *
     * <p>A refusal lists <strong>every</strong> diagnostic. That is the whole point of the validation
     * layer: a refused reload changed nothing, so the list is the only thing that tells the operator
     * which file and which key to go and edit, and an operator who is only told "reload failed" has
     * gained nothing over a restart.
     *
     * @param recipient whoever typed it
     * @param text      the wording read when the command was accepted
     * @param report    what the reload did
     */
    private void report(
            @NotNull Audience recipient,
            @NotNull CommandSettings text,
            @NotNull ReloadReport report
    ) {
        final ConfigReloadResult result = report.result();
        // The live revision either way: the fresh snapshot when this was applied, and the untouched
        // previous one when it was refused.
        final String revision = Long.toString(result.snapshot().revision());

        if (!result.applied()) {
            final List<ConfigDiagnostic> problems = result.diagnostics();
            recipient.sendMessage(MenuText.of(text.refusedMessage(),
                    MenuText.placeholder("count", problems.size()),
                    MenuText.placeholder("revision", revision),
                    MenuText.placeholder("summary", result.summary())));
            for (final ConfigDiagnostic problem : problems) {
                final String key = problem.key();
                recipient.sendMessage(MenuText.of(text.diagnosticLine(),
                        MenuText.placeholder("diagnostic", problem.format()),
                        MenuText.placeholder("file", problem.file().fileName()),
                        // Null means the complaint is about the file as a whole rather than one key.
                        MenuText.placeholder("key", key == null ? "" : key),
                        MenuText.placeholder("detail", problem.message())));
            }
            return;
        }

        recipient.sendMessage(MenuText.of(text.appliedMessage(),
                // Structure rather than a tunable: the file count is how many files there are.
                MenuText.placeholder("files", ConfigFile.values().length),
                MenuText.placeholder("rigs", report.rigs()),
                MenuText.placeholder("revision", revision),
                MenuText.placeholder("summary", result.summary())));

        if (result.requiresSimulationRebuild() && text.hasRebuildMessage()) {
            recipient.sendMessage(MenuText.of(text.rebuildMessage()));
        }
    }

    /**
     * Reports a failure to the console and to the operator, in that order.
     *
     * <p>The console first, because rendering a configured template is the one step left that could
     * itself throw — a MiniMessage template is operator input — and a stack trace that was already
     * written is one that survives it.
     *
     * @param recipient whoever typed it
     * @param text      the wording read when the command was accepted
     * @param failure   what went wrong
     */
    private void fail(
            @NotNull Audience recipient,
            @NotNull CommandSettings text,
            @NotNull RuntimeException failure
    ) {
        this.failures.failed("The /" + NAME + " command could not reload the configuration", failure);
        recipient.sendMessage(MenuText.of(text.failedMessage(),
                MenuText.placeholder("error", describe(failure))));
    }

    /**
     * The most specific message an exception chain carries.
     *
     * <p>The same unwrapping {@code ConfigService} does, and for the same reason: the outer message is
     * usually a wrapper's — {@code CompletionException} contributes nothing at all — while the cause is
     * where whatever actually failed explains itself.
     *
     * @param failure the exception to describe
     * @return a non-blank human readable description
     */
    private static @NotNull String describe(@NotNull Throwable failure) {
        Throwable deepest = failure;
        while (deepest.getCause() != null) {
            deepest = deepest.getCause();
        }
        final String message = deepest.getMessage();
        return message == null || message.isBlank() ? deepest.toString() : message;
    }
}
