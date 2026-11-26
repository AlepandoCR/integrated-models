package pandastic.studio.models.internal.command;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Every line the two status commands say, and the two switches that decide how much they say.
 *
 * <h2>Why all of it is configuration</h2>
 * For the reason the reload command's wording is: a status report is read by the one person on the server
 * who is certain to have opinions about it, there is no translation seam below this, and a reply is
 * serialised to its recipient as finished components. Hard-coding any of it would mean a fork per
 * language and a rebuild per typo — in a plugin whose whole configuration contract is that an edit needs
 * neither.
 *
 * <p>Every template is <strong>MiniMessage source</strong> rather than a parsed component, for the reason
 * {@link pandastic.studio.models.internal.menu.MenuSettings} gives: almost every one carries a
 * placeholder whose value only exists when the command answers, so a component parsed at reload time
 * would have to be re-parsed anyway. Keeping them as text also keeps this record free of Adventure, which
 * is what lets {@code ConfigMapper} build it without knowing how text is rendered.
 *
 * <h2>Blank means absent, everywhere</h2>
 * No component here is refused for being blank — only for being {@code null}, which is what a key present
 * with no value at all reads back as. Emptying a key is how an operator removes a line, so a report whose
 * every template is empty is a report that says nothing, and that is a state an operator is entitled to
 * ask for. It is also what makes {@link #SILENT} the identity value of this record rather than a set of
 * hidden defaults.
 *
 * <h2>What is deliberately not here</h2>
 * The commands' names, aliases and descriptions. All three are their identity in the server's command
 * dispatcher, which is handed them once when the plugin enables, so a key for any of them would take
 * effect only on the next restart. See {@link HealthCommand} for the full argument; it is the same one
 * {@code ReloadCommand} makes, and it is the one thing in this project that is deliberately not
 * configuration.
 *
 * @param verbose       whether a report lists the parts and figures that have nothing to report
 * @param showKnownGaps whether a report names the figures it cannot measure
 * @param box           the lines and the column gap both reports are drawn with
 * @param health        what the health report says
 * @param statistics    what the statistics report says
 */
public record StatusSettings(
        boolean verbose,
        boolean showKnownGaps,
        @NotNull Box box,
        @NotNull Health health,
        @NotNull Statistics statistics
) {

    /**
     * The identity value: a status report that says nothing at all.
     *
     * <p>Not a set of defaults, and the distinction matters — a default in a value type is exactly the
     * hardcoded tunable this project's configuration rule exists to remove. Every template below is the
     * empty string, which this record's own contract defines as "omit this line", so the result is a
     * report with no lines rather than a report with opinions nobody configured.
     *
     * <p>It exists for the compatibility constructor of {@link CommandSettings}, which serves callers
     * that configure only the reload command's wording. A caller that has not configured the status
     * commands is not entitled to an opinion about what they say.
     */
    public static final @NotNull StatusSettings SILENT = new StatusSettings(
            false,
            false,
            new Box("", "", "", 0),
            new Health("", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
                    "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", "", ""),
            new Statistics("", "", "", "", "", "", "", "", "", "", "", "", "",
                    "", "", "", "", "", "", "", "", "", ""));

    public StatusSettings {
        Objects.requireNonNull(box, "box");
        Objects.requireNonNull(health, "health");
        Objects.requireNonNull(statistics, "statistics");
    }

    /**
     * The box both reports are drawn in, and the one number in this file.
     *
     * <h2>Why the columns are cells and not one row template</h2>
     * The same reason the seat chart's are. Alignment is computed from each cell's <em>rendered</em>
     * plain width, which is the only way a column stays straight when one operator writes {@code OK} and
     * another writes {@code <green><bold>healthy}; a single row template would make the padding the
     * operator's problem and it would be wrong on the first colour tag.
     *
     * @param top       the line opening the box. Placeholder: {@code title}. Blank sends the title on its
     *                  own line instead, so the report never loses the line that says what it is about
     * @param rowPrefix drawn at the start of every row, which is what makes the box a box
     * @param bottom    the line closing the box, after the summary. Blank omits it
     * @param columnGap spaces between two columns, on top of the widest cell in the left one
     */
    public record Box(
            @NotNull String top,
            @NotNull String rowPrefix,
            @NotNull String bottom,
            int columnGap
    ) {

        public Box {
            Objects.requireNonNull(top, "top");
            Objects.requireNonNull(rowPrefix, "rowPrefix");
            Objects.requireNonNull(bottom, "bottom");
            if (columnGap < 0) {
                throw new IllegalArgumentException("columnGap must not be negative but was " + columnGap);
            }
        }
    }

    /**
     * Every line of the health report.
     *
     * <h2>One detail template per state, not one per part per state</h2>
     * A part's row is {@code [prefix][part][state][detail]}, where the part cell names the layer, the
     * state cell is one of three words, and the detail template is chosen by what that part had to say.
     * The two loops share their three detail templates, because what there is to say about a repeating
     * task — running at this rate driving this many things, stopped, or owed a re-dispatch — does not
     * depend on which of the two it is, and the part cell beside it already says.
     *
     * @param title                what the report is about. Placeholders: {@code verdict}, {@code parts},
     *                             {@code degraded}, {@code down}
     * @param partCell             the part column of a row. Placeholder: {@code part}
     * @param stateOk              the state cell for a part working as configured
     * @param stateDegraded        the state cell for a part working less than completely
     * @param stateDown            the state cell for a part not doing its job at all
     * @param verdictHealthy       the word substituted for {@code verdict} when every part is fine
     * @param verdictDegraded      the word substituted for {@code verdict} when the worst part is degraded
     * @param verdictFaulted       the word substituted for {@code verdict} when a part is down
     * @param partRenderer         the part cell's name for the rendering backend
     * @param partConfiguration    the part cell's name for the configuration layer
     * @param partRigs             the part cell's name for the rigs read from disk
     * @param partSimulation       the part cell's name for the simulation loop
     * @param partDrivers          the part cell's name for the decoration driver loop
     * @param partAi               the part cell's name for the creature AI layer
     * @param partInput            the part cell's name for the player input layer
     * @param rendererActive       detail for a backend that is driving. Placeholder: {@code renderer}
     * @param rendererMissing      detail for no backend at all. Placeholder: {@code reason}
     * @param configurationLive    detail for configuration in force. Placeholder: {@code revision}
     * @param configurationTrouble detail for a last reload that was refused or threw. Placeholders:
     *                             {@code revision}, {@code problems}
     * @param rigsLoaded           detail for the rig directory. Placeholders: {@code rigs}, {@code bones}
     * @param loopRunning          detail for a live loop. Placeholders: {@code hz}, {@code registered}
     * @param loopStopped          detail for a loop whose task is not dispatched
     * @param loopRedispatch       detail for a loop running at a period the files no longer ask for.
     *                             Placeholders: {@code hz}, {@code dispatched}, {@code registered}
     * @param aiActive             detail for a published AI service. Placeholder: {@code species}
     * @param aiUnavailable        detail for no AI service. Placeholder: {@code reason}
     * @param inputActive          detail for complete input capture. Placeholders:
     *                             {@code capabilities}, {@code bindings}
     * @param inputDegraded        detail for reduced input capture, or an audit with a complaint.
     *                             Placeholders: {@code reason}, {@code capabilities}, {@code bindings},
     *                             {@code issues}
     * @param summary              the line closing the report. Placeholders: {@code verdict},
     *                             {@code ok}, {@code degraded}, {@code down}, {@code parts}
     * @param gapLine              one known gap, repeated. Placeholder: {@code gap}
     * @param gapEmissionMetrics   the sentence for the transform counter the engine cannot read
     * @param gapRigDiagnostics    the sentence for rig diagnostics not being retained
     * @param gapViewCulling       the sentence for the per-viewer culler having no loop
     */
    public record Health(
            @NotNull String title,
            @NotNull String partCell,
            @NotNull String stateOk,
            @NotNull String stateDegraded,
            @NotNull String stateDown,
            @NotNull String verdictHealthy,
            @NotNull String verdictDegraded,
            @NotNull String verdictFaulted,
            @NotNull String partRenderer,
            @NotNull String partConfiguration,
            @NotNull String partRigs,
            @NotNull String partSimulation,
            @NotNull String partDrivers,
            @NotNull String partAi,
            @NotNull String partInput,
            @NotNull String rendererActive,
            @NotNull String rendererMissing,
            @NotNull String configurationLive,
            @NotNull String configurationTrouble,
            @NotNull String rigsLoaded,
            @NotNull String loopRunning,
            @NotNull String loopStopped,
            @NotNull String loopRedispatch,
            @NotNull String aiActive,
            @NotNull String aiUnavailable,
            @NotNull String inputActive,
            @NotNull String inputDegraded,
            @NotNull String summary,
            @NotNull String gapLine,
            @NotNull String gapEmissionMetrics,
            @NotNull String gapRigDiagnostics,
            @NotNull String gapViewCulling
    ) {

        public Health {
            requirePresent(title, "title");
            requirePresent(partCell, "partCell");
            requirePresent(stateOk, "stateOk");
            requirePresent(stateDegraded, "stateDegraded");
            requirePresent(stateDown, "stateDown");
            requirePresent(verdictHealthy, "verdictHealthy");
            requirePresent(verdictDegraded, "verdictDegraded");
            requirePresent(verdictFaulted, "verdictFaulted");
            requirePresent(partRenderer, "partRenderer");
            requirePresent(partConfiguration, "partConfiguration");
            requirePresent(partRigs, "partRigs");
            requirePresent(partSimulation, "partSimulation");
            requirePresent(partDrivers, "partDrivers");
            requirePresent(partAi, "partAi");
            requirePresent(partInput, "partInput");
            requirePresent(rendererActive, "rendererActive");
            requirePresent(rendererMissing, "rendererMissing");
            requirePresent(configurationLive, "configurationLive");
            requirePresent(configurationTrouble, "configurationTrouble");
            requirePresent(rigsLoaded, "rigsLoaded");
            requirePresent(loopRunning, "loopRunning");
            requirePresent(loopStopped, "loopStopped");
            requirePresent(loopRedispatch, "loopRedispatch");
            requirePresent(aiActive, "aiActive");
            requirePresent(aiUnavailable, "aiUnavailable");
            requirePresent(inputActive, "inputActive");
            requirePresent(inputDegraded, "inputDegraded");
            requirePresent(summary, "summary");
            requirePresent(gapLine, "gapLine");
            requirePresent(gapEmissionMetrics, "gapEmissionMetrics");
            requirePresent(gapRigDiagnostics, "gapRigDiagnostics");
            requirePresent(gapViewCulling, "gapViewCulling");
        }

        /**
         * The word standing in for {@code verdict}.
         *
         * @param verdict the worst state any part is in
         * @return the configured word
         */
        public @NotNull String verdictWord(@NotNull StatusSnapshot.State verdict) {
            return switch (verdict) {
                case OK -> this.verdictHealthy;
                case DEGRADED -> this.verdictDegraded;
                case DOWN -> this.verdictFaulted;
            };
        }

        /**
         * The state cell for one part.
         *
         * @param state how that part is doing
         * @return the configured cell template
         */
        public @NotNull String stateCell(@NotNull StatusSnapshot.State state) {
            return switch (state) {
                case OK -> this.stateOk;
                case DEGRADED -> this.stateDegraded;
                case DOWN -> this.stateDown;
            };
        }

        /**
         * The sentence for one gap this plugin cannot measure.
         *
         * @param gap which gap
         * @return the configured sentence, blank to omit it
         */
        public @NotNull String gapSentence(@NotNull StatusSnapshot.KnownGap gap) {
            return switch (gap) {
                case EMISSION_METRICS -> this.gapEmissionMetrics;
                case RIG_DIAGNOSTICS -> this.gapRigDiagnostics;
                case VIEW_CULLING -> this.gapViewCulling;
            };
        }
    }

    /**
     * Every line of the statistics report.
     *
     * <p>A row is {@code [prefix][metric][value][note]}. The value is a number this plugin counted, so it
     * carries no unit — a unit is prose and belongs in the metric label, which is why the defaults write
     * the hertz there rather than beside the figure.
     *
     * @param title                 what the report is about. Placeholder: {@code registered}
     * @param metricCell            the metric column of a row. Placeholder: {@code metric}
     * @param valueCell             the value column of a row. Placeholder: {@code value}
     * @param noteCell              the note column of a row. Placeholder: {@code note}
     * @param metricModels          label for the model binder's live handles
     * @param metricDecorations     label for the decoration binder's live handles
     * @param metricVehicles        label for the vehicle binder's live handles
     * @param metricRegistered      label for the published registry's entry count
     * @param metricSimulations     label for how many simulations the loop steps
     * @param metricDrivers         label for how many decorations the driver loop publishes for
     * @param metricRigs            label for how many rigs are loaded
     * @param metricRigBones        label for how many bones those rigs declare
     * @param metricPosedBones      label for how many bones are being posed right now
     * @param metricSimulationRate  label for the simulation rate
     * @param metricEmissionRate    label for the emission rate
     * @param metricTransformRate   label for the nominal transforms-per-second ceiling
     * @param metricInputBindings   label for how many input bindings consumers hold
     * @param metricInputIssues     label for how many of them the controller's audit complains about
     * @param noteVehicleEntries    note on the registry row, because a vehicle contributes two entries.
     *                              Placeholder: {@code vehicles}
     * @param noteEmissionWindow    note on the emission row. Placeholder: {@code ticks}
     * @param noteTransformRate     note on the transform row, which says the figure is nominal. Blank
     *                              omits it, and emptying it is the one edit that makes this report claim
     *                              a measurement it does not have
     * @param noteRedispatchPending note on a rate row whose dispatched period has not caught up.
     *                              Placeholder: {@code dispatched}
     * @param summary               the line closing the report. Placeholders: {@code registered},
     *                              {@code bones}, {@code transforms}
     */
    public record Statistics(
            @NotNull String title,
            @NotNull String metricCell,
            @NotNull String valueCell,
            @NotNull String noteCell,
            @NotNull String metricModels,
            @NotNull String metricDecorations,
            @NotNull String metricVehicles,
            @NotNull String metricRegistered,
            @NotNull String metricSimulations,
            @NotNull String metricDrivers,
            @NotNull String metricRigs,
            @NotNull String metricRigBones,
            @NotNull String metricPosedBones,
            @NotNull String metricSimulationRate,
            @NotNull String metricEmissionRate,
            @NotNull String metricTransformRate,
            @NotNull String metricInputBindings,
            @NotNull String metricInputIssues,
            @NotNull String noteVehicleEntries,
            @NotNull String noteEmissionWindow,
            @NotNull String noteTransformRate,
            @NotNull String noteRedispatchPending,
            @NotNull String summary
    ) {

        public Statistics {
            requirePresent(title, "title");
            requirePresent(metricCell, "metricCell");
            requirePresent(valueCell, "valueCell");
            requirePresent(noteCell, "noteCell");
            requirePresent(metricModels, "metricModels");
            requirePresent(metricDecorations, "metricDecorations");
            requirePresent(metricVehicles, "metricVehicles");
            requirePresent(metricRegistered, "metricRegistered");
            requirePresent(metricSimulations, "metricSimulations");
            requirePresent(metricDrivers, "metricDrivers");
            requirePresent(metricRigs, "metricRigs");
            requirePresent(metricRigBones, "metricRigBones");
            requirePresent(metricPosedBones, "metricPosedBones");
            requirePresent(metricSimulationRate, "metricSimulationRate");
            requirePresent(metricEmissionRate, "metricEmissionRate");
            requirePresent(metricTransformRate, "metricTransformRate");
            requirePresent(metricInputBindings, "metricInputBindings");
            requirePresent(metricInputIssues, "metricInputIssues");
            requirePresent(noteVehicleEntries, "noteVehicleEntries");
            requirePresent(noteEmissionWindow, "noteEmissionWindow");
            requirePresent(noteTransformRate, "noteTransformRate");
            requirePresent(noteRedispatchPending, "noteRedispatchPending");
            requirePresent(summary, "summary");
        }
    }

    /**
     * Requires a template to be present, allowing it to be blank.
     *
     * <p>Only {@code null} is refusable, for the reason {@link CommandSettings} documents: a generated
     * configuration reads a text key through {@code getString}, which answers {@code null} for a key that
     * is present with an empty value, and a line an operator emptied has to produce an omission rather
     * than a {@link NullPointerException} from {@code isBlank()}.
     */
    private static void requirePresent(@Nullable String value, @NotNull String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null; use an empty string to omit it");
        }
    }
}
