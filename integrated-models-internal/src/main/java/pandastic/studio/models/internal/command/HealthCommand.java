package pandastic.studio.models.internal.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * Answers one question: is Integrated Models working right now, and if not, which part.
 *
 * <h2>Why the answer is assembled rather than computed</h2>
 * Every layer in this plugin that can be in a reduced state already knows it and already says so once, at
 * enable, in a line nobody can scroll back to a week later. The rendering backend carries which renderer
 * it found and everything it passed over; the AI layer carries the sentence naming the server version and
 * every adapter it rejected; the input layer carries which of three reasons left it without packet-level
 * capture; both off-tick loops know the period they were dispatched at as well as the one the files now
 * ask for. So this command invents no health model of its own — it reads the seven things that already
 * know and prints them beside each other. The one figure nothing kept is what the last reload did, which
 * is why {@link ReloadHistory} exists and is the only instrumentation this feature added.
 *
 * <h2>What it refuses to claim</h2>
 * Three figures are genuinely not readable from here and are named as gaps rather than guessed at; see
 * {@link StatusSnapshot.KnownGap}. The per-viewer culler in particular is built, tested and has no loop
 * and no viewer sampler, so a report that listed it among the working parts would be the worst possible
 * lie this command could ship: it would be read as "culling is on" by the one person deciding whether
 * their view settings do anything.
 *
 * <h2>The name, the aliases and the description are not configuration</h2>
 * The same argument {@code ReloadCommand} sets out in full, and it applies unchanged. All three are this
 * command's identity in the server's command dispatcher, which is handed them exactly once when the
 * plugin enables, so a key for any of them would take effect only on the next restart — in the one file
 * whose entire contract is that an edit takes effect without one. Everything the command <em>says</em> is
 * configuration; what it is <em>called</em> is not.
 *
 * <p>{@code imhealth} follows the labels the two existing commands established: the plugin's initials and
 * the noun, with a separated alias for anyone who reaches for one. Paper also registers the namespaced
 * form, {@code integrated-models:imhealth}, so a collision costs an operator a prefix rather than the
 * command.
 *
 * <h2>Permission, and why there is no {@code plugin.yml} entry</h2>
 * {@link #PERMISSION} is returned from {@link #permission()}, which the inherited
 * {@link BasicCommand#canUse(org.bukkit.command.CommandSender)} turns into the requirement for both
 * running the command and seeing it in the client's command tree. The node is deliberately
 * <strong>not</strong> declared anywhere: Bukkit's default for an undeclared permission is
 * {@code PermissionDefault.OP}, so the command is operator-only out of the box and granting it to a role
 * stays something a permissions plugin can do. Operator-only is the right floor rather than a
 * conservative one — the report names the server's Minecraft version, every adapter that was passed over
 * and the reason each was, which is reconnaissance rather than a scoreboard.
 *
 * <h2>Threading: inline, unlike the reload</h2>
 * A capture does no I/O. Every figure is an in-memory read of a counter, a copied collection or one
 * volatile reference, so there is nothing to get off the ticking thread and the command answers in the
 * tick it was typed in. That is the opposite decision to {@code ReloadCommand}'s and for the opposite
 * reason; see {@link StatusSource} for the one rule a capture has to obey to make it true.
 */
public final class HealthCommand implements BasicCommand {

    /** The command's label. See the class documentation for why this is not a configuration key. */
    public static final String NAME = "imhealth";

    /** Alternative labels, registered alongside {@link #NAME}. */
    public static final List<String> ALIASES = List.of("im-health");

    /** What the server shows beside the command in its own listings. */
    public static final String DESCRIPTION =
            "Reports whether Integrated Models is working, and which part is not";

    /**
     * The permission required to run it.
     *
     * <p>Undeclared on purpose, which makes it operator-only by default; see the class documentation.
     */
    public static final String PERMISSION = "integratedmodels.command.health";

    private final @NotNull StatusSource status;
    private final @NotNull CommandSettingsSource settings;

    /**
     * A command over its two seams.
     *
     * @param status   where what this server is doing is read from, once per invocation
     * @param settings where the wording is read from, once per invocation
     */
    public HealthCommand(
            @NotNull StatusSource status,
            @NotNull CommandSettingsSource settings
    ) {
        this.status = Objects.requireNonNull(status, "status");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Arguments are ignored rather than refused, for the reason {@code ReloadCommand} gives: there is
     * nothing to parse, and a refusal would be a message key for a mistake with no consequence. The
     * verbosity that would otherwise be an argument is configuration, because an operator who wants the
     * long form wants it every time and should not have to remember a flag.
     *
     * <p>The sender rather than the executor is replied to: a status report is about the server, and the
     * person who asked is the one who wants it.
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
     * <p>Separate from {@link #execute} so that the capture, the verdict and every line of the reply are
     * exercisable against an {@link Audience} that records what it was sent, with no command source, no
     * sender and no server. {@code execute} is then one statement, which is the amount of untested code
     * that is acceptable at a platform boundary.
     *
     * <p>Both seams are read <strong>once</strong>, here. A reload landing between two reads of the
     * settings would answer with a header from one generation and rows from the next; a second capture
     * would let a bind land between the title and the row that counts it.
     *
     * @param recipient whoever typed it
     */
    void run(@NotNull Audience recipient) {
        final CommandSettings text = this.settings.current();
        final StatusSnapshot snapshot = this.status.capture();

        for (final Component line : StatusReport.health(snapshot, text.status())) {
            recipient.sendMessage(line);
        }
    }
}
