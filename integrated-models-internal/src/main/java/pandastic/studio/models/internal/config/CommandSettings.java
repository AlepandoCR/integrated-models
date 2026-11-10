package pandastic.studio.models.internal.config;

import pandastic.studio.core.service.api.config.ConfigFormat;
import pandastic.studio.core.service.api.config.ConfigValue;
import pandastic.studio.core.service.api.config.Configurable;

/**
 * Declaration of {@code command.yml}: every line a command of this plugin says back to whoever typed it.
 *
 * <p>Two commands exist, and the file has a section each. {@code reload.*} is the operator's entry point
 * into the hot-reload machinery; {@code menu.*} is the player-facing one that opens the model menu, which
 * has exactly one thing to say — a dialog cannot be shown to a sender that is not a player.
 *
 * <h2>Why the whole reply is configuration</h2>
 * For the reason {@link MenuSettings} gives about its labels, with one addition. A command's reply is
 * read by the one person on the server who is certain to have opinions about wording, and there is no
 * translation seam anywhere below this: a reply is serialised to its recipient as finished components,
 * so the server decides the wording. Hard-coding it would mean a fork per language and a rebuild per
 * typo — and in this case a rebuild of the plugin an operator is reloading precisely because they did
 * not want to restart.
 *
 * <p>Every key is <strong>MiniMessage</strong>, so colour, hover and emphasis come out of the same
 * string and no key needs a colour key beside it. The placeholders each key accepts are named in its
 * own documentation and are written in angle brackets, which is MiniMessage's own tag syntax; a
 * placeholder nobody substitutes renders as nothing rather than as the literal tag. Every substituted
 * value is inserted <em>unparsed</em>, because none of them is the operator's text — a diagnostic
 * carries a file name, a key name and a parser's complaint, any of which may contain angle brackets.
 *
 * <h2>What is deliberately not here</h2>
 * The command's <strong>name, its aliases and its description</strong>. All three are its identity in
 * the server's command dispatcher, which is resolved once when the plugin enables and never again, so a
 * key for any of them would take effect only on the next restart — in the one file whose entire contract
 * is that an edit takes effect without one. See {@code ReloadCommand} for the full argument and for the
 * names that were chosen.
 *
 * <p>There is also no cap on how many diagnostics a refusal lists. A refused reload changed nothing, so
 * the list is the only thing that tells the operator which file and which key to go and fix; a ceiling
 * on it would be a configurable way to hide the answer.
 *
 * <h2>Not to be confused with its namesake</h2>
 * {@link pandastic.studio.models.internal.command.CommandSettings} is a different type with the same
 * simple name: it is the assembled, already-validated value the command layer reads. This one is the
 * <em>file</em> — the keys, the comments and the defaults. The project has the same deliberate shadow
 * for render, simulation, AI, vehicle, menu, view and binding; nothing outside this package should need
 * to name this interface, because what the rest of the plugin sees is {@link ConfigSnapshot}.
 *
 * @see ConfigMapper
 * @see ConfigSnapshot
 */
@Configurable(name = "CommandConfig", format = ConfigFormat.YAML)
public interface CommandSettings {

    /** Reply sent when the player menu command is used by a non-player sender. */
    @ConfigValue(fieldName = "menu.notPlayerMessage",
            comment = "Reply sent when the model menu command is used from the console. MiniMessage.")
    default String menuNotPlayerMessage() {
        return "<red>Only players can open the model menu.";
    }

    /**
     * The line sent the moment a reload is accepted, before any file is read.
     *
     * <p>It exists because the command answers in two parts. Reading ten files is disk work, so it is
     * done off the thread that is ticking a region and the reply arrives later — without this line a
     * command that is working looks exactly like a command that did nothing. Blank turns it off, which
     * is the right choice on a server where the reload is fast enough that two lines read as one.
     *
     * <p>No placeholders.
     *
     * @return the acknowledgement line, or blank to send none
     */
    @ConfigValue(fieldName = "reload.startedMessage",
            comment = "Line sent as soon as a reload is accepted, before any file is read. The files are read off the ticking thread, so the result arrives in a second message; this one is what distinguishes a command that is working from one that did nothing. Leave blank to send only the result. MiniMessage.")
    default String reloadStartedMessage() {
        return "<gray>Reloading the Integrated Models configuration...";
    }

    /**
     * The line reporting a reload that was applied.
     *
     * <p>Placeholders: {@code <files>} (how many configuration files were re-read), {@code <rigs>} (how
     * many rigs are loaded now), {@code <revision>} (the publication counter of the values now in
     * effect) and {@code <summary>} (the configuration layer's own one-line summary).
     *
     * @return the success template
     */
    @ConfigValue(fieldName = "reload.appliedMessage",
            comment = "Line reporting a reload that was applied. Placeholders: files, rigs, revision, summary. MiniMessage.")
    default String reloadAppliedMessage() {
        return "<green>Configuration reloaded: <white><files></white> file(s) re-read, <white><rigs></white> rig(s) loaded, now at revision <white><revision></white>.";
    }

    /**
     * The extra line sent when the applied reload invalidated every live simulation state.
     *
     * <p>Sent only when the substep rate changed, because that is the one edit that cannot be absorbed
     * by the running simulations: a physics state is sized and stability-checked against a step size
     * when it is built, so every one of them was discarded and rebuilt. Worth saying out loud, since the
     * visible symptom is every model's secondary motion starting again from rest.
     *
     * <p>Blank omits the line. No placeholders.
     *
     * @return the rebuild notice, or blank to omit it
     */
    @ConfigValue(fieldName = "reload.rebuildMessage",
            comment = "Extra line sent when the applied reload changed the substep rate, which discards and rebuilds every live simulation state — so every model's secondary motion restarts from rest. Leave blank to omit it. MiniMessage.")
    default String reloadRebuildMessage() {
        return "<yellow>The substep rate changed, so every live simulation state was rebuilt from rest.";
    }

    /**
     * The header of a refused reload, sent before the diagnostics themselves.
     *
     * <p>Says that nothing changed first, because that is the fact the operator acts on: the previous
     * values are still live, so the server is in a state a file still describes.
     *
     * <p>Placeholders: {@code <count>} (how many problems follow), {@code <revision>} (the publication
     * counter of the values that stayed live) and {@code <summary>}.
     *
     * @return the refusal template
     */
    @ConfigValue(fieldName = "reload.refusedMessage",
            comment = "Header of a refused reload. A refusal changes nothing, so the previous values are still live. Placeholders: count, revision, summary. MiniMessage.")
    default String reloadRefusedMessage() {
        return "<red>Reload refused, nothing changed: still running revision <white><revision></white>. <white><count></white> problem(s) to fix:";
    }

    /**
     * One problem, sent once per diagnostic under the refusal header.
     *
     * <p>Placeholders: {@code <diagnostic>} (the whole complaint, already naming its file and key),
     * {@code <file>}, {@code <key>} (empty when the problem is with the file as a whole rather than with
     * one key) and {@code <detail>} (the complaint without the file and key in front of it).
     *
     * <p>The default uses {@code <diagnostic>} on purpose: it is the form that cannot accidentally be
     * written without the file name, and an operator who wants a different layout has the three parts
     * separately.
     *
     * @return the diagnostic template
     */
    @ConfigValue(fieldName = "reload.diagnosticLine",
            comment = "One problem, repeated once per diagnostic under the refusal header. Placeholders: diagnostic (the whole complaint, file and key included), file, key (empty for a whole-file problem), detail. MiniMessage.")
    default String reloadDiagnosticLine() {
        return "<red>- <white><diagnostic>";
    }

    /**
     * The line sent to a second caller while a reload is still running.
     *
     * <p>Two operators typing the command at the same moment must not start two reloads: the second one
     * would re-read the rig directory and re-dispatch every repeating task while the first was doing the
     * same. So the second caller is turned away rather than queued — a queued reload reads the same files
     * the first one just read, and reports its answer to somebody who has stopped looking.
     *
     * <p>No placeholders.
     *
     * @return the busy template
     */
    @ConfigValue(fieldName = "reload.busyMessage",
            comment = "Line sent to a second caller while a reload is still running. The second invocation is refused rather than queued: it would re-read the same files and re-dispatch the same tasks. MiniMessage.")
    default String reloadBusyMessage() {
        return "<yellow>A reload is already running. Nothing was started; try again when it reports back.";
    }

    /**
     * The line sent when the reload itself threw.
     *
     * <p>Not the same thing as a refusal. A refusal is operator input the validator understood and said
     * no to; this is a failure the layer does not model — an unreadable directory, or the plugin being
     * disabled while the reload was in flight. The configuration is left as it was either way, and the
     * stack trace goes to the console because the message alone is rarely enough to act on.
     *
     * <p>Placeholder: {@code <error>}, the most specific message the failure carries.
     *
     * @return the failure template
     */
    @ConfigValue(fieldName = "reload.failedMessage",
            comment = "Line sent when the reload threw rather than being refused — an unreadable directory, or the plugin disabled mid-reload. The configuration is unchanged and the stack trace goes to the console. Placeholder: error. MiniMessage.")
    default String reloadFailedMessage() {
        return "<red>The reload failed and nothing changed: <white><error></white>. The console has the stack trace.";
    }
}
