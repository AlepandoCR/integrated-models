package pandastic.studio.models.internal.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.internal.menu.MenuText;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Draws a {@link StatusSnapshot} as the lines an operator reads.
 *
 * <h2>Pure, and that is deliberate</h2>
 * Both methods return components and write nothing: the sending is the command's. That is what lets a
 * test assert the exact text of every line — including which lines are absent at a given verbosity, which
 * is the half most likely to be got wrong — with no server, no logger and no snapshot of anything live.
 *
 * <h2>Alignment is computed, not configured</h2>
 * Each cell is rendered first and measured as <em>plain</em> text, then padded to the widest cell in its
 * column plus the configured gap. Measuring the rendered component rather than the template is the whole
 * trick: a template is MiniMessage, so {@code <green>OK} is two visible characters and nine written ones,
 * and padding the source would leave a column that is straight in the file and crooked in the terminal —
 * differently crooked for every operator who re-colours a label.
 *
 * <p>Padding is appended as a sibling rather than to the cell, so a label an operator underlined does not
 * drag its decoration across the gap. This is the same measuring {@code SeatChartReport} does, for the
 * same reason; the two keep their own copies because extracting fifteen lines would mean a shared home in
 * a package neither of them owns, and the thing worth sharing — "measure what a viewer sees" — is one
 * sentence rather than a dependency.
 *
 * <h2>Why both reports read the same in chat and in a console</h2>
 * Neither draws anything a proportional font cannot keep straight except within a row, and a row's
 * columns are padded with spaces. A console is monospaced and keeps the columns; a chat window is not and
 * loses the alignment while keeping the order, the colour and every figure. That is the degradation worth
 * accepting — the alternative is two layouts, and then two things to keep in step.
 */
public final class StatusReport {

    /**
     * Measures what a viewer sees.
     *
     * <p>Stateless and thread-safe by contract, so one instance serves every report rather than one per
     * cell.
     */
    private static final @NotNull PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    private StatusReport() {
        throw new AssertionError("No instances.");
    }

    /**
     * Renders the health report, top line to bottom line.
     *
     * <h2>What the verbosity switch actually hides</h2>
     * With {@code verbose} off, only the parts that are <strong>not</strong> fine are listed. That is the
     * right default for a command an operator types when something looks wrong: the answer is the short
     * list, and the summary line still says how many parts were checked and how many passed, so nothing
     * is hidden without being counted. With it on, every part is listed in a fixed order, which is what
     * somebody comparing two servers wants.
     *
     * <p>A healthy server with {@code verbose} off therefore produces a title, a summary and no rows at
     * all — which is the report reading correctly rather than a report with a hole in it.
     *
     * @param status   everything that was captured
     * @param settings the configured templates and switches
     * @return the lines, in order; empty when every template an operator could have kept is blank
     */
    @Contract(pure = true)
    public static @NotNull List<Component> health(
            @NotNull StatusSnapshot status,
            @NotNull StatusSettings settings
    ) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(settings, "settings");

        final StatusSettings.Health labels = settings.health();
        final StatusSnapshot.State verdict = status.verdict();
        final List<Component> lines = new ArrayList<>();

        addTitle(lines, settings.box(), MenuText.optional(labels.title(),
                MenuText.placeholder("verdict", labels.verdictWord(verdict)),
                MenuText.placeholder("parts", status.partCount()),
                MenuText.placeholder("degraded", status.countOf(StatusSnapshot.State.DEGRADED)),
                MenuText.placeholder("down", status.countOf(StatusSnapshot.State.DOWN))));

        final List<Row> rows = healthRows(status, labels, settings.verbose());
        lines.addAll(drawRows(rows, settings.box()));

        final Component summary = MenuText.optional(labels.summary(),
                MenuText.placeholder("verdict", labels.verdictWord(verdict)),
                MenuText.placeholder("ok", status.countOf(StatusSnapshot.State.OK)),
                MenuText.placeholder("degraded", status.countOf(StatusSnapshot.State.DEGRADED)),
                MenuText.placeholder("down", status.countOf(StatusSnapshot.State.DOWN)),
                MenuText.placeholder("parts", status.partCount()));
        if (summary != null) {
            lines.add(summary);
        }

        if (settings.showKnownGaps()) {
            for (final StatusSnapshot.KnownGap gap : StatusSnapshot.KnownGap.values()) {
                final String sentence = labels.gapSentence(gap);
                if (sentence.isBlank()) {
                    continue;
                }
                final Component line = MenuText.optional(labels.gapLine(),
                        Placeholder.component("gap", MenuText.of(sentence)));
                if (line != null) {
                    lines.add(line);
                }
            }
        }

        addBottom(lines, settings.box());
        return List.copyOf(lines);
    }

    /**
     * Renders the statistics report, top line to bottom line.
     *
     * <p>Here {@code verbose} controls the note column rather than which rows are drawn. Every figure is
     * worth printing even at zero — "no models bound" is an answer — while the notes are the part that
     * explains a figure rather than giving one, and an operator who has read them once should be able to
     * have their console back.
     *
     * <p><strong>The nominal-transform note is the exception worth knowing about.</strong> It is the only
     * thing in either report that says a figure is derived rather than measured, so hiding it leaves a
     * number that reads like a measurement. That is why it is drawn whenever the row is, regardless of
     * verbosity; emptying its key is the operator's way to remove it, and the key's own comment says what
     * that costs.
     *
     * @param status   everything that was captured
     * @param settings the configured templates and switches
     * @return the lines, in order
     */
    @Contract(pure = true)
    public static @NotNull List<Component> statistics(
            @NotNull StatusSnapshot status,
            @NotNull StatusSettings settings
    ) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(settings, "settings");

        final StatusSettings.Statistics labels = settings.statistics();
        final StatusSnapshot.World world = status.world();
        final List<Component> lines = new ArrayList<>();

        addTitle(lines, settings.box(), MenuText.optional(labels.title(),
                MenuText.placeholder("registered", world.registered())));

        final List<Row> rows = statisticsRows(status, labels, settings.verbose());
        lines.addAll(drawRows(rows, settings.box()));

        final Component summary = MenuText.optional(labels.summary(),
                MenuText.placeholder("registered", world.registered()),
                MenuText.placeholder("bones", world.posedBones()),
                MenuText.placeholder("transforms", Long.toString(status.nominalTransformsPerSecond())));
        if (summary != null) {
            lines.add(summary);
        }

        // No gap lines here, and not an omission: the one figure in this report that is derived rather
        // than measured carries its caveat as that row's own note, where it sits beside the number it is
        // about. The health report is where the layers that cannot report at all are named.
        addBottom(lines, settings.box());
        return List.copyOf(lines);
    }

    // ------------------------------------------------------------------ rows

    /**
     * One row of either report: three cells, the last of which may be empty.
     *
     * @param label the left column, already rendered
     * @param value the middle column, already rendered
     * @param note  the right column, already rendered; {@link Component#empty()} for none
     */
    private record Row(@NotNull Component label, @NotNull Component value, @NotNull Component note) {
    }

    /**
     * The health rows, in the fixed order the parts are reported in.
     *
     * <p>Fixed rather than sorted by severity, deliberately: the order is "from the outside in" — the
     * renderer, then the configuration it reads, then the rigs, then the loops that step them, then the
     * two optional layers — so the first row that is not fine is the most upstream thing that is wrong,
     * and an operator reading top to bottom meets causes before symptoms.
     */
    private static @NotNull List<Row> healthRows(
            @NotNull StatusSnapshot status,
            @NotNull StatusSettings.Health labels,
            boolean verbose
    ) {
        final List<Row> rows = new ArrayList<>();

        final StatusSnapshot.Renderer renderer = status.renderer();
        add(rows, verbose, labels, labels.partRenderer(), renderer.state(),
                renderer.present()
                        ? MenuText.optional(labels.rendererActive(),
                                MenuText.placeholder("renderer", renderer.rendererName()))
                        : MenuText.optional(labels.rendererMissing(),
                                MenuText.placeholder("reason", renderer.reason())));

        final StatusSnapshot.Configuration configuration = status.configuration();
        add(rows, verbose, labels, labels.partConfiguration(), configuration.state(),
                configuration.state() == StatusSnapshot.State.OK
                        ? MenuText.optional(labels.configurationLive(),
                                MenuText.placeholder("revision",
                                        Long.toString(configuration.revision())))
                        : MenuText.optional(labels.configurationTrouble(),
                                MenuText.placeholder("revision",
                                        Long.toString(configuration.revision())),
                                MenuText.placeholder("problems", configuration.problems())));

        final StatusSnapshot.Rigs rigs = status.rigs();
        add(rows, verbose, labels, labels.partRigs(), rigs.state(),
                MenuText.optional(labels.rigsLoaded(),
                        MenuText.placeholder("rigs", rigs.loaded()),
                        MenuText.placeholder("bones", rigs.bones())));

        add(rows, verbose, labels, labels.partSimulation(), status.simulation().state(),
                loopDetail(status.simulation(), labels));
        add(rows, verbose, labels, labels.partDrivers(), status.drivers().state(),
                loopDetail(status.drivers(), labels));

        final StatusSnapshot.Ai ai = status.ai();
        add(rows, verbose, labels, labels.partAi(), ai.state(),
                ai.available()
                        ? MenuText.optional(labels.aiActive(),
                                MenuText.placeholder("species", ai.supportedSpecies()))
                        : MenuText.optional(labels.aiUnavailable(),
                                MenuText.placeholder("reason", ai.unavailableReason())));

        final StatusSnapshot.Input input = status.input();
        add(rows, verbose, labels, labels.partInput(), input.state(),
                input.state() == StatusSnapshot.State.OK
                        ? MenuText.optional(labels.inputActive(),
                                MenuText.placeholder("capabilities", input.capabilityCount()),
                                MenuText.placeholder("bindings", input.bindings()))
                        : MenuText.optional(labels.inputDegraded(),
                                MenuText.placeholder("reason", input.unavailableReason()),
                                MenuText.placeholder("capabilities", input.capabilityCount()),
                                MenuText.placeholder("bindings", input.bindings()),
                                MenuText.placeholder("issues", input.issues())));

        return rows;
    }

    /** The detail cell of one loop, chosen by what there is to say about it. */
    private static @Nullable Component loopDetail(
            @NotNull StatusSnapshot.Loop loop,
            @NotNull StatusSettings.Health labels
    ) {
        if (!loop.running()) {
            return MenuText.optional(labels.loopStopped());
        }
        if (loop.isRedispatchPending()) {
            return MenuText.optional(labels.loopRedispatch(),
                    MenuText.placeholder("hz", loop.configuredHz()),
                    MenuText.placeholder("dispatched", loop.dispatchedHz()),
                    MenuText.placeholder("registered", loop.registrations()));
        }
        return MenuText.optional(labels.loopRunning(),
                MenuText.placeholder("hz", loop.configuredHz()),
                MenuText.placeholder("registered", loop.registrations()));
    }

    /**
     * Adds one health row, unless it has nothing to report and the operator asked for the short form.
     *
     * <p>The part cell is rendered through {@code partCell} with the part's own name substituted, so an
     * operator styles all seven once rather than seven times — the same split the seat chart's seat cell
     * uses.
     */
    private static void add(
            @NotNull List<Row> rows,
            boolean verbose,
            @NotNull StatusSettings.Health labels,
            @NotNull String partName,
            @NotNull StatusSnapshot.State state,
            @Nullable Component detail
    ) {
        if (!verbose && state == StatusSnapshot.State.OK) {
            return;
        }
        rows.add(new Row(
                orEmpty(MenuText.optional(labels.partCell(),
                        MenuText.placeholder("part", partName))),
                orEmpty(MenuText.optional(labels.stateCell(state))),
                orEmpty(detail)));
    }

    /** The statistics rows, in the fixed order they are reported in. */
    private static @NotNull List<Row> statisticsRows(
            @NotNull StatusSnapshot status,
            @NotNull StatusSettings.Statistics labels,
            boolean verbose
    ) {
        final StatusSnapshot.World world = status.world();
        final StatusSnapshot.Loop simulation = status.simulation();
        final StatusSnapshot.Emission emission = status.emission();
        final List<Row> rows = new ArrayList<>();

        metric(rows, labels, labels.metricModels(), Integer.toString(world.models()), null);
        metric(rows, labels, labels.metricDecorations(), Integer.toString(world.decorations()), null);
        metric(rows, labels, labels.metricVehicles(), Integer.toString(world.vehicles()), null);
        metric(rows, labels, labels.metricRegistered(), Integer.toString(world.registered()),
                verbose && world.vehicles() > 0
                        ? MenuText.optional(labels.noteVehicleEntries(),
                                MenuText.placeholder("vehicles", world.vehicles()))
                        : null);

        metric(rows, labels, labels.metricSimulations(), Integer.toString(simulation.registrations()),
                null);
        metric(rows, labels, labels.metricDrivers(),
                Integer.toString(status.drivers().registrations()), null);

        metric(rows, labels, labels.metricRigs(), Integer.toString(status.rigs().loaded()), null);
        metric(rows, labels, labels.metricRigBones(), Integer.toString(status.rigs().bones()), null);
        metric(rows, labels, labels.metricPosedBones(), Integer.toString(world.posedBones()), null);

        metric(rows, labels, labels.metricSimulationRate(), Integer.toString(simulation.configuredHz()),
                simulation.isRedispatchPending()
                        // Drawn whatever the verbosity: a rate that is configured and not dispatched is
                        // the one thing on this row that makes the figure beside it untrue.
                        ? MenuText.optional(labels.noteRedispatchPending(),
                                MenuText.placeholder("dispatched", simulation.dispatchedHz()))
                        : null);
        metric(rows, labels, labels.metricEmissionRate(), Integer.toString(emission.emissionHz()),
                verbose
                        ? MenuText.optional(labels.noteEmissionWindow(),
                                MenuText.placeholder("ticks", emission.interpolationTicks()))
                        : null);
        metric(rows, labels, labels.metricTransformRate(),
                Long.toString(status.nominalTransformsPerSecond()),
                // Never hidden by verbosity; see the method documentation.
                MenuText.optional(labels.noteTransformRate()));

        metric(rows, labels, labels.metricInputBindings(),
                Integer.toString(status.input().bindings()), null);
        metric(rows, labels, labels.metricInputIssues(),
                Integer.toString(status.input().issues()), null);

        return rows;
    }

    /**
     * Adds one statistics row, unless the operator emptied its label.
     *
     * <p>An emptied label removes the row rather than leaving a blank one: a metric nobody wants named is
     * a metric nobody wants, and the value on its own would be a number with no question beside it.
     */
    private static void metric(
            @NotNull List<Row> rows,
            @NotNull StatusSettings.Statistics labels,
            @NotNull String metricName,
            @NotNull String value,
            @Nullable Component note
    ) {
        if (metricName.isBlank()) {
            return;
        }
        rows.add(new Row(
                orEmpty(MenuText.optional(labels.metricCell(),
                        MenuText.placeholder("metric", metricName))),
                orEmpty(MenuText.optional(labels.valueCell(),
                        MenuText.placeholder("value", value))),
                note == null
                        ? Component.empty()
                        : orEmpty(MenuText.optional(labels.noteCell(),
                                Placeholder.component("note", note)))));
    }

    // ------------------------------------------------------------------ drawing

    /**
     * Opens the box, keeping the title either way.
     *
     * <p>An operator who emptied the opening line still gets the title: it is the only line that says
     * which report the rest of the lines belong to, which matters more here than in the seat chart,
     * because two of these can be typed one after the other.
     */
    private static void addTitle(
            @NotNull List<Component> lines,
            @NotNull StatusSettings.Box box,
            @Nullable Component title
    ) {
        if (title == null) {
            return;
        }
        if (box.top().isBlank()) {
            lines.add(title);
            return;
        }
        final Component framed = MenuText.optional(box.top(), Placeholder.component("title", title));
        lines.add(framed == null ? title : framed);
    }

    private static void addBottom(@NotNull List<Component> lines, @NotNull StatusSettings.Box box) {
        final Component bottom = MenuText.optional(box.bottom());
        if (bottom != null) {
            lines.add(bottom);
        }
    }

    /**
     * Pads every row's columns to the widest cell in each and prefixes them.
     *
     * <p>Three passes over a handful of rows, and the alternative is a column whose width depends on
     * which row happened to come first.
     */
    private static @NotNull List<Component> drawRows(
            @NotNull List<Row> rows,
            @NotNull StatusSettings.Box box
    ) {
        if (rows.isEmpty()) {
            return List.of();
        }

        int labelWidth = 0;
        int valueWidth = 0;
        for (final Row row : rows) {
            labelWidth = Math.max(labelWidth, visibleWidth(row.label()));
            valueWidth = Math.max(valueWidth, visibleWidth(row.value()));
        }
        labelWidth += box.columnGap();
        valueWidth += box.columnGap();

        final Component prefix = MenuText.of(box.rowPrefix());
        final List<Component> lines = new ArrayList<>(rows.size());
        for (final Row row : rows) {
            lines.add(Component.text()
                    .append(prefix)
                    .append(padded(row.label(), labelWidth))
                    .append(padded(row.value(), valueWidth))
                    .append(row.note())
                    .build());
        }
        return lines;
    }

    private static int visibleWidth(@NotNull Component cell) {
        return PLAIN.serialize(cell).length();
    }

    private static @NotNull Component padded(@NotNull Component cell, int width) {
        final int visible = visibleWidth(cell);
        if (visible >= width) {
            // Already as wide as the column, which happens when the gap is zero and this is the widest
            // cell. One space would be a gap the operator did not ask for.
            return cell;
        }
        final TextComponent.Builder row = Component.text();
        row.append(cell);
        row.append(Component.text(" ".repeat(width - visible)));
        return row.build();
    }

    /** {@code candidate}, or an empty component when the template it came from was blank. */
    private static @NotNull Component orEmpty(@Nullable Component candidate) {
        return candidate == null ? Component.empty() : candidate;
    }
}
