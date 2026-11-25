package pandastic.studio.models.internal.vehicle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.rig.RigSeat;
import pandastic.studio.models.api.vehicle.VehicleSeat;
import pandastic.studio.models.internal.menu.MenuText;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Draws a {@link SeatChart} as the lines an operator reads in a console.
 *
 * <h2>Why this is worth a class</h2>
 * Because a seat that failed to resolve is the most common thing that will be wrong with a vehicle rig,
 * and the only way anybody finds out is this chart. A model in this repository carries no locator yet, so
 * the expected outcome today is a vehicle whose every declared seat is unusable — and an operator who
 * wrote a {@code seats:} section and got nothing has to be told which name failed and in which of the two
 * ways, not left to infer it from riders falling through the model.
 *
 * <h2>Pure, and that is deliberate</h2>
 * {@link #lines} returns components and writes nothing. The severity decision and the writing are
 * {@link #emit}'s, over {@link SeatChartSink}, which is what lets a test assert on the exact text of
 * every line without a logger — and what keeps the one piece of real logic in here, the column alignment,
 * exercisable.
 *
 * <h2>Alignment is computed, not configured</h2>
 * Each cell is rendered first and measured as <em>plain</em> text, then padded to the widest cell in its
 * column plus the configured gap. Measuring the rendered component rather than the template is the whole
 * trick: a template is MiniMessage, so {@code <green>LOCATOR} is seven visible characters and fourteen
 * written ones, and padding the source would leave a column that is straight in the file and crooked in
 * the terminal — differently crooked for every operator who re-colours a label.
 *
 * <p>Padding is appended as a sibling rather than to the cell, so a label an operator underlined does not
 * drag its decoration across the gap.
 */
public final class SeatChartReport {

    /**
     * Measures what a viewer sees.
     *
     * <p>Stateless and thread-safe by contract, so one instance serves every chart rather than one per
     * cell.
     */
    private static final @NotNull PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    private SeatChartReport() {
        throw new AssertionError("No instances.");
    }

    /**
     * Renders the chart, top line to bottom line.
     *
     * @param chart   every declared seat and what it turned out to be
     * @param rigName the rig the seats were declared in
     * @param modelId the model they were resolved against
     * @param labels  the configured templates
     * @return the lines, or empty when the rig declared no seats at all — a creature rig and a vehicle
     *         rig with no {@code seats:} section have nothing to report, and a chart that announced "0
     *         seats" for every model on the server would be noise rather than information
     */
    @Contract(pure = true)
    public static @NotNull List<Component> lines(
            @NotNull SeatChart chart,
            @NotNull String rigName,
            @NotNull String modelId,
            @NotNull SeatSettings.Chart labels
    ) {
        Objects.requireNonNull(chart, "chart");
        Objects.requireNonNull(rigName, "rigName");
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(labels, "labels");
        if (chart.isEmpty()) {
            return List.of();
        }

        final Component title = MenuText.of(labels.title(),
                MenuText.placeholder("rig", rigName),
                MenuText.placeholder("model", modelId),
                MenuText.placeholder("declared", chart.declared()));

        final List<Component> lines = new ArrayList<>(chart.declared() + 4);
        if (labels.top().isBlank()) {
            // An operator who emptied the opening line still gets the title: it is the only line that
            // says which rig and which model the rest of the chart is about.
            lines.add(title);
        } else {
            lines.add(MenuText.of(labels.top(), Placeholder.component("title", title)));
        }

        // Rendered once, measured, then padded. Three passes over a handful of seats, and the alternative
        // is a column whose width depends on which seat happened to come first.
        final List<Component> seatCells = new ArrayList<>(chart.declared());
        final List<Component> stateCells = new ArrayList<>(chart.declared());
        final List<Component> roleCells = new ArrayList<>(chart.declared());
        final List<Component> offsetCells = new ArrayList<>(chart.declared());
        for (final SeatChart.Entry entry : chart.entries()) {
            final VehicleSeat seat = entry.seat();
            // Three placeholders rather than one, because the chart is read top to bottom to answer
            // "which seat does the first player to board get" and the answer is a number. index is the
            // address a consumer passes to board(...); number is the same seat counted from one, which
            // is what a player is told.
            seatCells.add(MenuText.of(labels.seatCell(),
                    MenuText.placeholder("seat", seat.name()),
                    MenuText.placeholder("index", seat.index()),
                    MenuText.placeholder("number", seat.number())));
            stateCells.add(MenuText.of(stateTemplate(entry.kind(), labels)));
            roleCells.add(MenuText.of(seat.isDriver() ? labels.driverLabel() : labels.passengerLabel()));
            offsetCells.add(offsetCell(entry, labels));
        }

        final int seatWidth = widestOf(seatCells) + labels.columnGap();
        final int stateWidth = widestOf(stateCells) + labels.columnGap();
        final int roleWidth = widestOf(roleCells) + labels.columnGap();

        final Component prefix = MenuText.of(labels.rowPrefix());
        for (int index = 0; index < chart.declared(); index++) {
            lines.add(Component.text()
                    .append(prefix)
                    .append(padded(seatCells.get(index), seatWidth))
                    .append(padded(stateCells.get(index), stateWidth))
                    .append(padded(roleCells.get(index), roleWidth))
                    .append(offsetCells.get(index))
                    .build());
        }

        lines.add(MenuText.of(labels.summary(),
                MenuText.placeholder("usable", chart.usableCount()),
                MenuText.placeholder("unusable", chart.unusableCount()),
                MenuText.placeholder("declared", chart.declared())));

        // Only when there is something to act on, because a note repeated under every healthy chart is a
        // note nobody reads under the broken one.
        if (chart.unusableCount() > 0 && !labels.unusableNote().isBlank()) {
            lines.add(MenuText.of(labels.unusableNote(),
                    MenuText.placeholder("unusable", chart.unusableCount()),
                    MenuText.placeholder("declared", chart.declared())));
        }
        if (!labels.bottom().isBlank()) {
            lines.add(MenuText.of(labels.bottom()));
        }
        return List.copyOf(lines);
    }

    /**
     * Renders the chart and writes it, at one severity for the whole chart.
     *
     * <p>Warning when any declared seat is unusable, information otherwise; see {@link SeatChartSink} for
     * why the severity is per chart rather than per line. A chart with nothing in it writes nothing, and
     * an operator who switched the chart off passes {@link SeatChartSink#NONE} — the decision about
     * whether to report is the caller's, because the caller is the one holding the configuration.
     *
     * @return how many lines were written
     */
    public static int emit(
            @NotNull SeatChartSink sink,
            @NotNull SeatChart chart,
            @NotNull String rigName,
            @NotNull String modelId,
            @NotNull SeatSettings.Chart labels
    ) {
        Objects.requireNonNull(sink, "sink");
        final List<Component> lines = lines(chart, rigName, modelId, labels);
        final boolean broken = chart.unusableCount() > 0;
        for (final Component line : lines) {
            if (broken) {
                sink.warn(line);
            } else {
                sink.info(line);
            }
        }
        return lines.size();
    }

    private static @NotNull String stateTemplate(
            @NotNull SeatLocators.Kind kind,
            @NotNull SeatSettings.Chart labels
    ) {
        return switch (kind) {
            case LOCATOR -> labels.locatorLabel();
            case BONE -> labels.boneLabel();
            case UNRESOLVED -> labels.unresolvedLabel();
        };
    }

    /**
     * The offset cell, which is a word rather than three numbers for a seat that has no offset.
     *
     * <p>Numbers are written through {@link Float#toString}, not a format string: it is the shortest
     * representation that round-trips, so {@code 0.55} reads as {@code 0.55} and a figure the rig loader
     * clamped reads as whatever it actually clamped to rather than as a rounded version of it.
     *
     * <p>A seat that did not resolve and declares no offset gets nothing at all, because "on the
     * locator" is a sentence about a locator that was not found — it read as a contradiction next to the
     * state cell saying so. A declared offset is still printed for an unusable seat: it is a fact about
     * what the rigger wrote, and it will matter once the locator exists.
     */
    private static @NotNull Component offsetCell(
            @NotNull SeatChart.Entry entry,
            @NotNull SeatSettings.Chart labels
    ) {
        final RigSeat seat = entry.seat().definition();
        if (seat.isOnLocator()) {
            return entry.isUsable() ? MenuText.of(labels.onLocatorLabel()) : Component.empty();
        }
        return MenuText.of(labels.offsetCell(),
                MenuText.placeholder("x", Float.toString(seat.offsetX())),
                MenuText.placeholder("y", Float.toString(seat.offsetY())),
                MenuText.placeholder("z", Float.toString(seat.offsetZ())));
    }

    private static int widestOf(@NotNull List<Component> cells) {
        int widest = 0;
        for (final Component cell : cells) {
            widest = Math.max(widest, PLAIN.serialize(cell).length());
        }
        return widest;
    }

    private static @NotNull Component padded(@NotNull Component cell, int width) {
        final int visible = PLAIN.serialize(cell).length();
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
}
