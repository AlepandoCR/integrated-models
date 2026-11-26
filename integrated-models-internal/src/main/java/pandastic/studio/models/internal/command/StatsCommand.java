package pandastic.studio.models.internal.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * What Integrated Models has standing in the world, and what it costs to keep it there.
 *
 * <h2>Every figure here was already being counted</h2>
 * The three binders keep their own live sets because they have to despawn them; the registry is a
 * {@code ConcurrentHashMap} whose size is free; both loops know how many registrations they iterate,
 * because that is what they iterate; the rigs are a map; the input controller counts its bindings and
 * already audits them. So this command adds no counters — it reads what the plugin was already keeping,
 * which is the difference between a statistics command and a profiler.
 *
 * <h2>The two figures that are not what they look like</h2>
 * <ol>
 *   <li><strong>The registry total is larger than the three kinds added up</strong>, by the vehicle
 *       count. A vehicle contributes two entries: itself, and its visual, which is a real rigged model
 *       bound to its root entity. Both are reported, and the registry row carries the note saying why
 *       they disagree — an operator who found the discrepancy and no explanation would reasonably
 *       conclude the registry leaks.</li>
 *   <li><strong>The transform rate is nominal, not measured.</strong> It is the emission rate times the
 *       posed bone count, which is the ceiling the dirty tracker subtracts from rather than what goes out
 *       on the wire. The one real counter in the tree lives on the backend's pose sink, and the engine
 *       may not reference the backend — the dependency runs backend to engine to api, which is what makes
 *       a second renderer a new module rather than a rewrite. So the figure is printed with the caveat
 *       attached to the row it is on; see {@link StatusSnapshot.KnownGap#EMISSION_METRICS}.</li>
 * </ol>
 *
 * <h2>Why this is a second command rather than a flag on the first</h2>
 * Because the two answer different questions and are typed at different times. A health report is typed
 * when something looks wrong and wants to be short; a statistics report is typed when nothing is wrong
 * and wants every figure. Folding them together would mean the operator with a problem reads fifteen
 * rows of counters to find the one line that says which layer is down, and the verbosity switch that
 * fixed that would be the flag this split removes.
 *
 * <h2>The name, the permission and the threading</h2>
 * All three follow {@link HealthCommand}, which sets out each argument in full: the label is identity in
 * the dispatcher and therefore deliberately not configuration, {@link #PERMISSION} is undeclared and so
 * operator-only by Bukkit's own default, and the capture does no I/O so the command answers inline rather
 * than hopping off the ticking thread.
 */
public final class StatsCommand implements BasicCommand {

    /** The command's label. See {@link HealthCommand} for why this is not a configuration key. */
    public static final String NAME = "imstats";

    /** Alternative labels, registered alongside {@link #NAME}. */
    public static final List<String> ALIASES = List.of("im-stats");

    /** What the server shows beside the command in its own listings. */
    public static final String DESCRIPTION =
            "Reports what Integrated Models has in the world and what it costs";

    /**
     * The permission required to run it.
     *
     * <p>Undeclared on purpose, which makes it operator-only by default. A separate node from the health
     * command's rather than one shared one: these are two commands, and an operator granting a moderator
     * the counters should not have to also hand over the line that names every NMS adapter this server
     * was built against.
     */
    public static final String PERMISSION = "integratedmodels.command.stats";

    private final @NotNull StatusSource status;
    private final @NotNull CommandSettingsSource settings;

    /**
     * A command over its two seams.
     *
     * @param status   where what this server is doing is read from, once per invocation
     * @param settings where the wording is read from, once per invocation
     */
    public StatsCommand(
            @NotNull StatusSource status,
            @NotNull CommandSettingsSource settings
    ) {
        this.status = Objects.requireNonNull(status, "status");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * {@inheritDoc}
     *
     * <p>Arguments are ignored rather than refused; see {@link HealthCommand#execute}.
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
     * <p>Both seams are read once, for the reasons {@link HealthCommand#run} sets out: one capture so
     * that no two rows can describe different moments, and one settings read so that a reload cannot
     * change this report's own wording halfway through it.
     *
     * @param recipient whoever typed it
     */
    void run(@NotNull Audience recipient) {
        final CommandSettings text = this.settings.current();
        final StatusSnapshot snapshot = this.status.capture();

        for (final Component line : StatusReport.statistics(snapshot, text.status())) {
            recipient.sendMessage(line);
        }
    }
}
