package pandastic.studio.models.internal.config;

import pandastic.studio.core.service.api.config.ConfigFormat;
import pandastic.studio.core.service.api.config.ConfigValue;
import pandastic.studio.core.service.api.config.Configurable;

/**
 * Declaration of {@code command.yml}: every line a command of this plugin says back to whoever typed it.
 *
 * <p>Four commands exist, and the file has a section each. {@code reload.*} is the operator's entry point
 * into the hot-reload machinery; {@code menu.*} is the player-facing one that opens the model menu, which
 * has exactly one thing to say — a dialog cannot be shown to a sender that is not a player. {@code
 * status.*} is shared by the two reports, {@code /imhealth} and {@code /imstats}, because they are drawn
 * in the same box with the same columns and only differ in what goes in the rows.
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

    // =============================================================================================
    // status.*  — the health and the statistics report.
    //
    // Both are drawn as a box of aligned rows: [prefix][first column][second column][third column].
    // Alignment is computed from each cell's RENDERED width, so re-colouring a label needs no
    // re-counting and a column stays straight however an operator styles it. That is why the columns
    // are separate cell templates rather than one row template per report.
    //
    // Two switches rather than one, because they hide different kinds of thing: verbose decides
    // whether the parts and figures that have nothing to report are listed at all, and showKnownGaps
    // decides whether the report names the figures this plugin cannot measure. An operator may
    // reasonably want the short report and still want to be told what is missing from it.
    // =============================================================================================

    /**
     * Whether a status report lists the parts and figures that have nothing to report.
     *
     * <p>Off, the health report lists only the parts that are not working as configured — which is the
     * right answer to a command typed because something looks wrong — and the statistics report drops
     * its note column. On, every part is listed in a fixed order, which is what somebody comparing two
     * servers wants.
     *
     * <p>Off is the default because a healthy server then answers in two lines. Nothing is hidden
     * without being counted: the summary still says how many parts were checked and how many passed.
     *
     * @return whether to include what is fine
     */
    @ConfigValue(fieldName = "status.verbose",
            comment = "Whether a status report lists the parts and figures that have nothing to report. Off, the health report lists only the parts that are not working as configured and the statistics report drops its note column; the summary still counts everything either way. On, every part is listed in a fixed order, which is what comparing two servers wants.")
    default boolean statusVerbose() {
        return false;
    }

    /**
     * Whether the health report names the figures this plugin cannot measure.
     *
     * <p>Three of them, each with its own key below. They are worth saying out loud: a report that
     * silently omitted the per-viewer culler would be read as "culling is working", which is the one
     * wrong conclusion this command must not invite.
     *
     * @return whether to list the known gaps
     */
    @ConfigValue(fieldName = "status.showKnownGaps",
            comment = "Whether the health report names the three figures this plugin cannot measure (see status.health.gap*). On by default: a report that silently omitted the per-viewer culler reads as 'culling is working', which is the one wrong conclusion the command must not invite.")
    default boolean statusShowKnownGaps() {
        return true;
    }

    /** The line opening either report. Placeholder: {@code title}. */
    @ConfigValue(fieldName = "status.box.top",
            comment = "Line opening a status report. Placeholder: title. Blank sends the title on its own line instead, so the report never loses the line that says which of the two it is. MiniMessage.")
    default String statusBoxTop() {
        return "<dark_gray>┌──</dark_gray> <title>";
    }

    /** Drawn at the start of every row, which is what makes the box a box. */
    @ConfigValue(fieldName = "status.box.rowPrefix",
            comment = "Drawn at the start of every row of a status report. MiniMessage.")
    default String statusBoxRowPrefix() {
        return "<dark_gray>│</dark_gray> ";
    }

    /** The line closing either report, after its summary. Blank omits it. */
    @ConfigValue(fieldName = "status.box.bottom",
            comment = "Line closing a status report, after the summary. Blank omits it. MiniMessage.")
    default String statusBoxBottom() {
        return "";
    }

    /** Spaces between two columns, on top of the widest cell in the left one. */
    @ConfigValue(fieldName = "status.box.columnGap",
            comment = "Spaces between two columns of a status report, on top of the widest cell in the left one. Columns are aligned from each cell's rendered width, so re-colouring a label needs no re-counting.")
    default int statusBoxColumnGap() {
        return 2;
    }

    // ------------------------------------------------------------------ status.health.*

    /**
     * What the health report is about. Placeholders: {@code verdict}, {@code parts}, {@code degraded},
     * {@code down}.
     */
    @ConfigValue(fieldName = "status.health.title",
            comment = "Title of the health report. Placeholders: verdict (one of the three words below), parts (how many were checked), degraded, down. MiniMessage.")
    default String statusHealthTitle() {
        return "<aqua>Health</aqua> <dark_gray>·</dark_gray> <verdict> <dark_gray>·</dark_gray> "
                + "<gray><parts> part(s) checked</gray>";
    }

    /** The part column of a health row. Placeholder: {@code part}. */
    @ConfigValue(fieldName = "status.health.partCell",
            comment = "The part column of a health row. Placeholder: part, substituted with one of the status.health.part* labels. Styling it once here is what keeps all seven consistent. MiniMessage.")
    default String statusHealthPartCell() {
        return "<white><part>";
    }

    /** The state cell for a part working as configured. */
    @ConfigValue(fieldName = "status.health.stateOk",
            comment = "State cell for a part working as configured. MiniMessage.")
    default String statusHealthStateOk() {
        return "<green>● ok</green>";
    }

    /**
     * The state cell for a part working less than completely.
     *
     * <p>Deliberately distinct from the down cell: a server with no NMS adapter, or one running on files
     * its last reload refused, is in a state somebody can act on and nothing is broken.
     */
    @ConfigValue(fieldName = "status.health.stateDegraded",
            comment = "State cell for a part working less than completely — a missing optional adapter, a refused reload, a rate that has not been re-dispatched. Not a fault: the server is running and somebody can act on it. MiniMessage.")
    default String statusHealthStateDegraded() {
        return "<yellow>◐ degraded</yellow>";
    }

    /** The state cell for a part not doing its job at all. */
    @ConfigValue(fieldName = "status.health.stateDown",
            comment = "State cell for a part not doing its job at all. MiniMessage.")
    default String statusHealthStateDown() {
        return "<red>✖ down</red>";
    }

    /** The word substituted for {@code verdict} when every part is fine. */
    @ConfigValue(fieldName = "status.health.verdictHealthy",
            comment = "The word substituted for the verdict placeholder when every part is working as configured. MiniMessage.")
    default String statusHealthVerdictHealthy() {
        return "<green>healthy";
    }

    /** The word substituted for {@code verdict} when the worst part is degraded. */
    @ConfigValue(fieldName = "status.health.verdictDegraded",
            comment = "The word substituted for the verdict placeholder when the worst part is degraded. MiniMessage.")
    default String statusHealthVerdictDegraded() {
        return "<yellow>degraded";
    }

    /** The word substituted for {@code verdict} when a part is down. */
    @ConfigValue(fieldName = "status.health.verdictFaulted",
            comment = "The word substituted for the verdict placeholder when at least one part is down. MiniMessage.")
    default String statusHealthVerdictFaulted() {
        return "<red>faulted";
    }

    /** The part cell's name for the rendering backend. */
    @ConfigValue(fieldName = "status.health.partRenderer",
            comment = "Name of the rendering backend in the part column. MiniMessage.")
    default String statusHealthPartRenderer() {
        return "renderer";
    }

    /** The part cell's name for the configuration layer. */
    @ConfigValue(fieldName = "status.health.partConfiguration",
            comment = "Name of the configuration layer in the part column. MiniMessage.")
    default String statusHealthPartConfiguration() {
        return "configuration";
    }

    /** The part cell's name for the rigs read from disk. */
    @ConfigValue(fieldName = "status.health.partRigs",
            comment = "Name of the rig directory in the part column. MiniMessage.")
    default String statusHealthPartRigs() {
        return "rigs";
    }

    /** The part cell's name for the simulation loop. */
    @ConfigValue(fieldName = "status.health.partSimulation",
            comment = "Name of the simulation loop in the part column. MiniMessage.")
    default String statusHealthPartSimulation() {
        return "simulation";
    }

    /** The part cell's name for the decoration driver loop. */
    @ConfigValue(fieldName = "status.health.partDrivers",
            comment = "Name of the decoration driver loop in the part column. MiniMessage.")
    default String statusHealthPartDrivers() {
        return "drivers";
    }

    /** The part cell's name for the creature AI layer. */
    @ConfigValue(fieldName = "status.health.partAi",
            comment = "Name of the creature AI layer in the part column. MiniMessage.")
    default String statusHealthPartAi() {
        return "creature ai";
    }

    /** The part cell's name for the player input layer. */
    @ConfigValue(fieldName = "status.health.partInput",
            comment = "Name of the player input layer in the part column. MiniMessage.")
    default String statusHealthPartInput() {
        return "input";
    }

    /** Detail for a rendering backend that is driving. Placeholder: {@code renderer}. */
    @ConfigValue(fieldName = "status.health.rendererActive",
            comment = "Detail cell for a rendering backend that is driving. Placeholder: renderer, the renderer it drives. MiniMessage.")
    default String statusHealthRendererActive() {
        return "<gray>driving <white><renderer>";
    }

    /**
     * Detail for no rendering backend at all. Placeholder: {@code reason}.
     *
     * <p>In practice unreachable: plugin enable refuses outright when there is no backend, so a status
     * command running at all implies one was found. Kept because a report that cannot be wrong about the
     * one thing that must be true is cheaper to guarantee than to reason about.
     */
    @ConfigValue(fieldName = "status.health.rendererMissing",
            comment = "Detail cell for no rendering backend. Placeholder: reason, every provider that was passed over. In practice unreachable, because the plugin refuses to enable without a backend. MiniMessage.")
    default String statusHealthRendererMissing() {
        return "<red>no backend <dark_gray>·</dark_gray> <gray><reason>";
    }

    /** Detail for configuration in force. Placeholder: {@code revision}. */
    @ConfigValue(fieldName = "status.health.configurationLive",
            comment = "Detail cell for configuration that is in force. Placeholder: revision, the publication counter of the values in effect. MiniMessage.")
    default String statusHealthConfigurationLive() {
        return "<gray>revision <white><revision>";
    }

    /**
     * Detail for a last reload that was refused or threw. Placeholders: {@code revision},
     * {@code problems}.
     *
     * <p>The one degradation in this plugin that is otherwise completely invisible: a refused reload
     * leaves the previous values live, so a server running on refused files looks exactly like a server
     * nobody has edited.
     */
    @ConfigValue(fieldName = "status.health.configurationTrouble",
            comment = "Detail cell for a last reload that was refused or threw. Placeholders: revision (still in force — a refusal changes nothing), problems. This is the one degradation that is otherwise invisible: a server running on refused files looks exactly like one nobody edited. MiniMessage.")
    default String statusHealthConfigurationTrouble() {
        return "<yellow>last reload did not take <dark_gray>·</dark_gray> "
                + "<gray>still on revision <white><revision></white>, <white><problems></white> problem(s)";
    }

    /** Detail for the rig directory. Placeholders: {@code rigs}, {@code bones}. */
    @ConfigValue(fieldName = "status.health.rigsLoaded",
            comment = "Detail cell for the rig directory. Placeholders: rigs, bones. Zero rigs is a normal state: a consumer may bind entirely against rigs derived from the renderer's own models. MiniMessage.")
    default String statusHealthRigsLoaded() {
        return "<gray><white><rigs></white> loaded, <white><bones></white> bone(s)";
    }

    /** Detail for a live loop. Placeholders: {@code hz}, {@code registered}. */
    @ConfigValue(fieldName = "status.health.loopRunning",
            comment = "Detail cell for a live off-tick loop. Placeholders: hz, registered (simulations for the simulation loop, decorations for the driver loop). Shared by both loops, because what there is to say about a repeating task does not depend on which one it is. MiniMessage.")
    default String statusHealthLoopRunning() {
        return "<gray><white><hz></white> Hz, <white><registered></white> registered";
    }

    /** Detail for a loop whose task is not dispatched. */
    @ConfigValue(fieldName = "status.health.loopStopped",
            comment = "Detail cell for an off-tick loop whose repeating task is not dispatched. Nothing is simulated in this state. MiniMessage.")
    default String statusHealthLoopStopped() {
        return "<red>not dispatched";
    }

    /**
     * Detail for a loop running at a period the files no longer ask for. Placeholders: {@code hz},
     * {@code dispatched}, {@code registered}.
     *
     * <p>The one loop fault that is otherwise invisible. The platform owns a repeating task's rate and a
     * task cannot change its own, so a reload that moves a rate has to cancel and re-dispatch — and a
     * layer left out of the reload path keeps running at the old period forever, with nothing to report.
     */
    @ConfigValue(fieldName = "status.health.loopRedispatch",
            comment = "Detail cell for a loop whose live task is dispatched at a period the files no longer ask for. Placeholders: hz (configured), dispatched (actual), registered. A task cannot change its own rate, so a reload has to cancel and re-dispatch; this is what a layer missed by that path looks like. MiniMessage.")
    default String statusHealthLoopRedispatch() {
        return "<yellow>dispatched at <white><dispatched></white> Hz, configured <white><hz></white> Hz"
                + " <dark_gray>·</dark_gray> <gray>reload to re-dispatch";
    }

    /** Detail for a published AI service. Placeholder: {@code species}. */
    @ConfigValue(fieldName = "status.health.aiActive",
            comment = "Detail cell for a published creature AI service. Placeholder: species, how many creature species the adapter can install a plan on. MiniMessage.")
    default String statusHealthAiActive() {
        return "<gray><white><species></white> species supported";
    }

    /**
     * Detail for no AI service. Placeholder: {@code reason}.
     *
     * <p>Degraded rather than down: the rendering pipeline does not need NMS, so this is a reduced
     * feature set and not a fault — the same judgement the runtime makes when it logs the reason as a
     * warning at enable rather than refusing to start.
     */
    @ConfigValue(fieldName = "status.health.aiUnavailable",
            comment = "Detail cell for a server with no creature AI service. Placeholder: reason, naming the Minecraft version and every adapter that was passed over. Reported as degraded rather than down, because the rendering pipeline does not need NMS. MiniMessage.")
    default String statusHealthAiUnavailable() {
        return "<yellow>unavailable <dark_gray>·</dark_gray> <gray><reason>";
    }

    /** Detail for complete input capture. Placeholders: {@code capabilities}, {@code bindings}. */
    @ConfigValue(fieldName = "status.health.inputActive",
            comment = "Detail cell for complete input capture. Placeholders: capabilities (how many the started sources have between them), bindings (how many live bindings consumers hold). MiniMessage.")
    default String statusHealthInputActive() {
        return "<gray><white><capabilities></white> capability(ies), "
                + "<white><bindings></white> binding(s)";
    }

    /**
     * Detail for reduced input capture, or an audit with a complaint. Placeholders: {@code reason},
     * {@code capabilities}, {@code bindings}, {@code issues}.
     */
    @ConfigValue(fieldName = "status.health.inputDegraded",
            comment = "Detail cell for reduced input capture, or for an audit that has a complaint. Placeholders: reason, capabilities, bindings, issues. A binding the controller refuses to dispatch is a consumer's feature silently doing nothing, which is why the audit counts here. MiniMessage.")
    default String statusHealthInputDegraded() {
        return "<yellow><issues></yellow> <gray>audit issue(s), <white><bindings></white> binding(s) "
                + "<dark_gray>·</dark_gray> <gray><reason>";
    }

    /**
     * The line closing the health report. Placeholders: {@code verdict}, {@code ok}, {@code degraded},
     * {@code down}, {@code parts}.
     */
    @ConfigValue(fieldName = "status.health.summary",
            comment = "Line closing the health report. Placeholders: verdict, ok, degraded, down, parts. Sent even when no rows were, which is what makes the short form of a healthy report readable. MiniMessage.")
    default String statusHealthSummary() {
        return "<dark_gray>└──</dark_gray> <verdict> <dark_gray>·</dark_gray> "
                + "<green><ok> ok</green><dark_gray>,</dark_gray> "
                + "<yellow><degraded> degraded</yellow><dark_gray>,</dark_gray> "
                + "<red><down> down</red> <dark_gray>of <parts></dark_gray>";
    }

    /** One known gap, repeated once per gap. Placeholder: {@code gap}. */
    @ConfigValue(fieldName = "status.health.gapLine",
            comment = "One known gap under the health summary, repeated once per gap. Placeholder: gap, one of the three sentences below. Blank omits all of them, as does status.showKnownGaps: false. MiniMessage.")
    default String statusHealthGapLine() {
        return "<dark_gray>?</dark_gray> <gray><gap>";
    }

    /**
     * The sentence for the transform counter the engine cannot read.
     *
     * <p>The only emission counter in the tree lives on the backend's pose sink, and the engine may not
     * reference the backend — the dependency runs backend to engine to api, which is what makes a second
     * renderer a new module rather than a rewrite.
     */
    @ConfigValue(fieldName = "status.health.gapEmissionMetrics",
            comment = "Sentence naming the first known gap: emitted transforms are not counted anywhere the engine can read. The only counter lives on the backend's pose sink, and the engine may not reference the backend. The statistics report prints the nominal rate instead and says so. Blank omits this line. MiniMessage.")
    default String statusHealthGapEmissionMetrics() {
        return "Emitted transforms are not measured: the only counter lives in the rendering backend,"
                + " which the engine may not reference. /imstats prints the nominal rate instead.";
    }

    /** The sentence for rig diagnostics not being retained. */
    @ConfigValue(fieldName = "status.health.gapRigDiagnostics",
            comment = "Sentence naming the second known gap: rig diagnostics are logged at load and then dropped, so 'three rigs loaded' is exact and 'and one was refused' is unanswerable afterwards. Blank omits this line. MiniMessage.")
    default String statusHealthGapRigDiagnostics() {
        return "Rig diagnostics are not retained: a rig that failed to compile is simply absent from the"
                + " count. The console at load or reload time is where they were written.";
    }

    /** The sentence for the per-viewer culler having no loop. */
    @ConfigValue(fieldName = "status.health.gapViewCulling",
            comment = "Sentence naming the third known gap: the per-viewer culler is built and has no loop and no viewer sampler, so every key in view.yml is inert and a decoration is seen by exactly the players a consumer called show for. Blank omits this line — and removing it is what makes the report read as 'culling is working'. MiniMessage.")
    default String statusHealthGapViewCulling() {
        return "Per-viewer culling is not running: it has no loop and no viewer sampler, so view.yml is"
                + " inert and a decoration is seen by exactly the players a consumer showed it to.";
    }

    // ------------------------------------------------------------------ status.statistics.*

    /** What the statistics report is about. Placeholder: {@code registered}. */
    @ConfigValue(fieldName = "status.statistics.title",
            comment = "Title of the statistics report. Placeholder: registered, how many entries the published registry holds. MiniMessage.")
    default String statusStatisticsTitle() {
        return "<aqua>Statistics</aqua> <dark_gray>·</dark_gray> "
                + "<white><registered></white> <gray>registered model(s)</gray>";
    }

    /** The metric column of a statistics row. Placeholder: {@code metric}. */
    @ConfigValue(fieldName = "status.statistics.metricCell",
            comment = "The metric column of a statistics row. Placeholder: metric, substituted with one of the status.statistics.metric* labels. Emptying a metric label removes its whole row. MiniMessage.")
    default String statusStatisticsMetricCell() {
        return "<gray><metric>";
    }

    /** The value column of a statistics row. Placeholder: {@code value}. */
    @ConfigValue(fieldName = "status.statistics.valueCell",
            comment = "The value column of a statistics row. Placeholder: value. Carries no unit: a unit is prose and belongs in the metric label beside it. MiniMessage.")
    default String statusStatisticsValueCell() {
        return "<white><value>";
    }

    /** The note column of a statistics row. Placeholder: {@code note}. */
    @ConfigValue(fieldName = "status.statistics.noteCell",
            comment = "The note column of a statistics row. Placeholder: note, one of the status.statistics.note* lines. MiniMessage.")
    default String statusStatisticsNoteCell() {
        return "<dark_gray><note>";
    }

    /** Label for the model binder's live handles. */
    @ConfigValue(fieldName = "status.statistics.metricModels",
            comment = "Label for how many live handles the model binder has handed out, vehicle visuals included. Blank removes the row. MiniMessage.")
    default String statusStatisticsMetricModels() {
        return "models bound";
    }

    /** Label for the decoration binder's live handles. */
    @ConfigValue(fieldName = "status.statistics.metricDecorations",
            comment = "Label for how many live handles the decoration binder has handed out. Blank removes the row. MiniMessage.")
    default String statusStatisticsMetricDecorations() {
        return "decorations";
    }

    /** Label for the vehicle binder's live handles. */
    @ConfigValue(fieldName = "status.statistics.metricVehicles",
            comment = "Label for how many live handles the vehicle binder has handed out. Blank removes the row. MiniMessage.")
    default String statusStatisticsMetricVehicles() {
        return "vehicles";
    }

    /** Label for the published registry's entry count. */
    @ConfigValue(fieldName = "status.statistics.metricRegistered",
            comment = "Label for how many entries the published registry holds. Larger than the three kinds added up, by the vehicle count: a vehicle contributes itself and its visual. Blank removes the row. MiniMessage.")
    default String statusStatisticsMetricRegistered() {
        return "registry entries";
    }

    /** Label for how many simulations the loop steps. */
    @ConfigValue(fieldName = "status.statistics.metricSimulations",
            comment = "Label for how many simulations the off-tick loop steps per iteration. Blank removes the row. MiniMessage.")
    default String statusStatisticsMetricSimulations() {
        return "simulations stepped";
    }

    /** Label for how many decorations the driver loop publishes for. */
    @ConfigValue(fieldName = "status.statistics.metricDrivers",
            comment = "Label for how many decorations the driver loop publishes an ambient driver for. Blank removes the row. MiniMessage.")
    default String statusStatisticsMetricDrivers() {
        return "ambient drivers";
    }

    /** Label for how many rigs are loaded. */
    @ConfigValue(fieldName = "status.statistics.metricRigs",
            comment = "Label for how many rigs were read out of rigs/. Blank removes the row. MiniMessage.")
    default String statusStatisticsMetricRigs() {
        return "rigs loaded";
    }

    /** Label for how many bones those rigs declare. */
    @ConfigValue(fieldName = "status.statistics.metricRigBones",
            comment = "Label for how many bones the loaded rigs declare between them. Blank removes the row. MiniMessage.")
    default String statusStatisticsMetricRigBones() {
        return "rig bones declared";
    }

    /**
     * Label for how many bones are being posed right now.
     *
     * <p>The figure every per-step cost in this plugin is proportional to: the solver, the dirty
     * tracking and the emission are all per bone per step.
     */
    @ConfigValue(fieldName = "status.statistics.metricPosedBones",
            comment = "Label for how many bones are being posed right now, across every registered model's rig. The figure every per-step cost in this plugin is proportional to. Blank removes the row. MiniMessage.")
    default String statusStatisticsMetricPosedBones() {
        return "bones posed";
    }

    /** Label for the simulation rate. */
    @ConfigValue(fieldName = "status.statistics.metricSimulationRate",
            comment = "Label for the configured simulation rate. The unit belongs here rather than beside the figure. Blank removes the row. MiniMessage.")
    default String statusStatisticsMetricSimulationRate() {
        return "simulation rate (Hz)";
    }

    /** Label for the emission rate. */
    @ConfigValue(fieldName = "status.statistics.metricEmissionRate",
            comment = "Label for the configured emission rate. Deliberately below the simulation rate: a display consumes its pending-update flags once per client tick, so emitting faster buys nothing. Blank removes the row. MiniMessage.")
    default String statusStatisticsMetricEmissionRate() {
        return "emission rate (Hz)";
    }

    /** Label for the nominal transforms-per-second ceiling. */
    @ConfigValue(fieldName = "status.statistics.metricTransformRate",
            comment = "Label for the nominal transform rate — the emission rate times the posed bone count. A ceiling, not a measurement; see status.statistics.noteTransformRate. Blank removes the row. MiniMessage.")
    default String statusStatisticsMetricTransformRate() {
        return "bone transforms/s (max)";
    }

    /** Label for how many input bindings consumers hold. */
    @ConfigValue(fieldName = "status.statistics.metricInputBindings",
            comment = "Label for how many live input bindings consumers hold. Blank removes the row. MiniMessage.")
    default String statusStatisticsMetricInputBindings() {
        return "input bindings";
    }

    /** Label for how many bindings the controller's audit complains about. */
    @ConfigValue(fieldName = "status.statistics.metricInputIssues",
            comment = "Label for how many bindings the input controller's own audit complains about. Above zero, the health report reports the input layer as degraded. Blank removes the row. MiniMessage.")
    default String statusStatisticsMetricInputIssues() {
        return "input audit issues";
    }

    /**
     * Note on the registry row, because a vehicle contributes two entries. Placeholder:
     * {@code vehicles}.
     */
    @ConfigValue(fieldName = "status.statistics.noteVehicleEntries",
            comment = "Note on the registry row, drawn only in the verbose form and only when a vehicle is bound. Placeholder: vehicles. Says why the registry total is larger than the three kinds added up — an operator who found the discrepancy and no explanation would reasonably conclude the registry leaks. MiniMessage.")
    default String statusStatisticsNoteVehicleEntries() {
        return "includes <vehicles> vehicle visual(s)";
    }

    /** Note on the emission row. Placeholder: {@code ticks}. */
    @ConfigValue(fieldName = "status.statistics.noteEmissionWindow",
            comment = "Note on the emission row, drawn only in the verbose form. Placeholder: ticks, the interpolation window each emission asks the client to spread the motion over. A window shorter than the emission interval finishes early and the bone sits still until the next packet. MiniMessage.")
    default String statusStatisticsNoteEmissionWindow() {
        return "<ticks>-tick interpolation window";
    }

    /**
     * Note on the transform row, which says the figure is nominal. Blank omits it.
     *
     * <p>Drawn whatever the verbosity, and the one note that is: it is the only thing in either report
     * that says a figure is derived rather than measured, so hiding it leaves a number that reads like a
     * measurement.
     */
    @ConfigValue(fieldName = "status.statistics.noteTransformRate",
            comment = "Note on the transform row. Drawn whatever status.verbose says, because it is the only line in either report that admits a figure is derived rather than measured — emptying this key leaves a number that reads like a measurement. MiniMessage.")
    default String statusStatisticsNoteTransformRate() {
        return "nominal: rate x posed bones, before dirty tracking";
    }

    /**
     * Note on a rate row whose dispatched period has not caught up. Placeholder:
     * {@code dispatched}.
     */
    @ConfigValue(fieldName = "status.statistics.noteRedispatchPending",
            comment = "Note on the simulation rate row when the live task is dispatched at a period the files no longer ask for. Placeholder: dispatched. Drawn whatever status.verbose says, because it is what makes the figure beside it untrue. MiniMessage.")
    default String statusStatisticsNoteRedispatchPending() {
        return "dispatched at <dispatched> Hz; reload to re-dispatch";
    }

    /**
     * The line closing the statistics report. Placeholders: {@code registered}, {@code bones},
     * {@code transforms}.
     */
    @ConfigValue(fieldName = "status.statistics.summary",
            comment = "Line closing the statistics report. Placeholders: registered, bones, transforms (the nominal ceiling). MiniMessage.")
    default String statusStatisticsSummary() {
        return "<dark_gray>└──</dark_gray> <white><registered></white> <gray>model(s), </gray>"
                + "<white><bones></white> <gray>bone(s), up to </gray>"
                + "<white><transforms></white> <gray>transform(s)/s</gray>";
    }
}
