package pandastic.studio.models.internal.vehicle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.rig.RigSeat;
import pandastic.studio.models.api.vehicle.VehicleSeat;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the operator reads, and the one piece of real logic in drawing it.
 *
 * <h2>Why the output is asserted at all</h2>
 * Because for a vehicle whose seats did not resolve, this chart <em>is</em> the behaviour. No model in
 * this repository contains a locator, so the common outcome is a vehicle that carries nobody, and the
 * only way anybody learns which seat failed and in which of the two ways is these lines. A silent empty
 * chart would be indistinguishable from a working one.
 */
class SeatChartReportTest {

    private static final @NotNull PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    private final SeatSettings.Chart labels = VehicleFixtures.chartLabels();

    /**
     * A chart whose seats are numbered by position, which is how production builds one: the seat plan
     * assigns an index per declared seat in file order, driver first.
     *
     * <p>The entries arrive from {@link #spec} carrying a placeholder index and are renumbered here, so
     * a call site states only what it is actually testing — the declaration and how its name resolved.
     * Nothing in this suite asserts on an index; every assertion is about a name, a state, a role or an
     * offset, which is why positional numbering is enough.
     */
    private static @NotNull SeatChart chart(@NotNull SeatChart.Entry... entries) {
        final List<SeatChart.Entry> numbered = new ArrayList<>(entries.length);
        for (int index = 0; index < entries.length; index++) {
            final SeatChart.Entry entry = entries[index];
            numbered.add(new SeatChart.Entry(
                    new VehicleSeat(index, entry.seat().definition()), entry.kind()));
        }
        return new SeatChart(numbered);
    }

    /** A declared seat and how its name resolved; {@link #chart} supplies the index. */
    private static @NotNull SeatChart.Entry spec(
            @NotNull RigSeat seat,
            @NotNull SeatLocators.Kind kind
    ) {
        return new SeatChart.Entry(new VehicleSeat(0, seat), kind);
    }

    private @NotNull List<String> plainLines(@NotNull SeatChart chart) {
        final List<String> lines = new ArrayList<>();
        for (final Component line : SeatChartReport.lines(chart, "coach", "coach-model", this.labels)) {
            lines.add(PLAIN.serialize(line));
        }
        return lines;
    }

    @Test
    void aRigWithNoSeatsSaysNothingAtAll() {
        // Announcing "0 seats" for every creature and every decoration on the server would be noise,
        // and noise is what makes the chart that matters unreadable.
        assertAll(
                () -> assertEquals(List.of(), SeatChartReport.lines(
                        SeatChart.EMPTY, "deer", "deer-model", this.labels)),
                () -> assertEquals(0, SeatChartReport.emit(
                        SeatChartSink.NONE, SeatChart.EMPTY, "deer", "deer-model", this.labels))
        );
    }

    @Test
    void everyDeclaredSeatGetsOneLineWithItsStateItsRoleAndItsOffset() {
        final List<String> lines = plainLines(chart(
                spec(RigSeat.of("driver", "saddle", true),
                        SeatLocators.Kind.LOCATOR),
                spec(new RigSeat("pillion", "pillion", false, 0F, 0.5F, -0.25F),
                        SeatLocators.Kind.LOCATOR),
                spec(RigSeat.of("crate", "mount", false), SeatLocators.Kind.BONE),
                spec(RigSeat.of("ghost", "nowhere", false),
                        SeatLocators.Kind.UNRESOLVED)));

        assertAll(
                () -> assertEquals(8, lines.size(),
                        () -> "top, four seats, summary, note, bottom: " + lines),
                () -> assertEquals("top chart coach on coach-model (4)", lines.getFirst()),
                () -> assertTrue(lines.get(1).startsWith("| driver"), () -> lines.get(1)),
                () -> assertTrue(lines.get(1).contains("LOCATOR"), () -> lines.get(1)),
                () -> assertTrue(lines.get(1).contains("driver"), () -> lines.get(1)),
                () -> assertTrue(lines.get(1).endsWith("on-locator"), () -> lines.get(1)),
                () -> assertTrue(lines.get(2).contains("offset 0.0 0.5 -0.25"), () -> lines.get(2)),
                () -> assertTrue(lines.get(2).contains("passenger"), () -> lines.get(2)),
                () -> assertTrue(lines.get(3).contains("BONE"), () -> lines.get(3)),
                () -> assertTrue(lines.get(4).contains("MISSING"), () -> lines.get(4)),
                () -> assertEquals("end 2/4 usable, 2 unusable", lines.get(5)),
                () -> assertEquals("fix the locator", lines.get(6)),
                () -> assertEquals("bottom", lines.get(7))
        );
    }

    @Test
    void anUnresolvedSeatWithNoOffsetSaysNothingAboutItsLocator() {
        // "on the locator" next to "no such locator" read as a contradiction. A declared offset is still
        // printed, because it is a fact about what the rigger wrote and will matter once the locator
        // exists.
        final List<String> lines = plainLines(chart(
                spec(RigSeat.of("ghost", "nowhere", false),
                        SeatLocators.Kind.UNRESOLVED),
                spec(new RigSeat("crate", "mount", false, 0F, 0.5F, 0F),
                        SeatLocators.Kind.BONE)));

        assertAll(
                () -> assertFalse(lines.get(1).contains("on-locator"), () -> lines.get(1)),
                () -> assertTrue(lines.get(2).contains("offset 0.0 0.5 0.0"), () -> lines.get(2))
        );
    }

    @Test
    void theColumnsLineUpOnTheRenderedWidthRatherThanOnTheTemplate() {
        // The one piece of logic in here. A template is MiniMessage, so padding the source would leave a
        // column that is straight in the file and crooked in the terminal.
        final List<String> lines = plainLines(chart(
                spec(RigSeat.of("a", "one", true), SeatLocators.Kind.LOCATOR),
                spec(RigSeat.of("a-very-long-seat-name", "two", false),
                        SeatLocators.Kind.LOCATOR)));

        final int firstState = lines.get(1).indexOf("LOCATOR");
        final int secondState = lines.get(2).indexOf("LOCATOR");
        assertAll(
                () -> assertEquals(firstState, secondState,
                        () -> "the state column has to start at the same place on both lines: " + lines),
                () -> assertEquals("| a".length() + "-very-long-seat-name".length() + 2, firstState,
                        "the widest seat name plus the configured two-space gap")
        );
    }

    @Test
    void aColouredLabelDoesNotMoveTheColumnItIsIn() {
        // The regression this design exists to prevent: an operator re-colouring one state label must not
        // shift the column for every other row.
        final SeatSettings.Chart coloured = new SeatSettings.Chart(
                this.labels.top(), this.labels.title(), this.labels.rowPrefix(), this.labels.seatCell(),
                "<green><bold>LOCATOR</bold></green>", this.labels.boneLabel(),
                this.labels.unresolvedLabel(), this.labels.driverLabel(),
                this.labels.passengerLabel(), this.labels.offsetCell(), this.labels.onLocatorLabel(),
                this.labels.summary(), this.labels.unusableNote(), this.labels.bottom(),
                this.labels.columnGap());

        final SeatChart chart = chart(
                spec(RigSeat.of("one", "one", true), SeatLocators.Kind.LOCATOR),
                spec(RigSeat.of("two", "two", false), SeatLocators.Kind.BONE));
        final List<String> plain = new ArrayList<>();
        for (final Component line : SeatChartReport.lines(chart, "coach", "coach-model", coloured)) {
            plain.add(PLAIN.serialize(line));
        }

        final int firstRole = plain.get(1).indexOf("driver");
        final int secondRole = plain.get(2).indexOf("passenger");
        assertAll(
                () -> assertEquals(firstRole, secondRole,
                        () -> "the role column has to start at the same place on both lines: " + plain),
                () -> assertEquals("| one  LOCATOR  ".length(), firstRole,
                        "the padding is the label's rendered width, not its template's length — the"
                                + " template here is thirty-odd characters of MiniMessage for seven"
                                + " visible ones")
        );
    }

    @Test
    void aChartWithAnUnusableSeatIsAWarningAndOneWithoutIsNot() {
        // Severity per chart rather than per line: a console prefixes every line with its own level, so
        // a box drawn half at INFO and half at WARNING arrives with its borders broken.
        final RecordingSink sink = new RecordingSink();

        SeatChartReport.emit(sink, chart(
                spec(RigSeat.of("one", "one", true), SeatLocators.Kind.LOCATOR)),
                "coach", "coach-model", this.labels);
        assertAll(
                () -> assertEquals(4, sink.info.size(), () -> sink.info.toString()),
                () -> assertEquals(0, sink.warn.size())
        );

        sink.info.clear();
        SeatChartReport.emit(sink, chart(
                spec(RigSeat.of("one", "one", true), SeatLocators.Kind.UNRESOLVED)),
                "coach", "coach-model", this.labels);
        assertAll(
                () -> assertEquals(0, sink.info.size()),
                () -> assertEquals(5, sink.warn.size(),
                        () -> "every line of a broken chart, note included: " + sink.warn)
        );
    }

    @Test
    void anEmptiedTemplateRemovesItsLineRatherThanLeavingABlankOne() {
        final SeatSettings.Chart quiet = new SeatSettings.Chart(
                "", this.labels.title(), this.labels.rowPrefix(), this.labels.seatCell(),
                this.labels.locatorLabel(), this.labels.boneLabel(), this.labels.unresolvedLabel(),
                this.labels.driverLabel(), this.labels.passengerLabel(), this.labels.offsetCell(),
                this.labels.onLocatorLabel(), this.labels.summary(), "", "", 0);

        final List<Component> lines = SeatChartReport.lines(chart(
                spec(RigSeat.of("one", "one", true), SeatLocators.Kind.UNRESOLVED)),
                "coach", "coach-model", quiet);

        assertAll(
                () -> assertEquals(3, lines.size(),
                        "the title on its own, the seat, the summary"),
                () -> assertEquals("chart coach on coach-model (1)",
                        PLAIN.serialize(lines.getFirst()),
                        "an emptied top line still leaves the title, which is the only line naming the"
                                + " rig and the model")
        );
    }

    /** A sink that keeps what it was told, per severity. */
    private static final class RecordingSink implements SeatChartSink {

        private final List<String> info = new ArrayList<>();
        private final List<String> warn = new ArrayList<>();

        @Override
        public void info(@NotNull Component line) {
            this.info.add(PLAIN.serialize(line));
        }

        @Override
        public void warn(@NotNull Component line) {
            this.warn.add(PLAIN.serialize(line));
        }
    }
}
