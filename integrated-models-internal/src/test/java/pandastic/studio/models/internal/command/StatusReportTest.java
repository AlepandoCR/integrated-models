package pandastic.studio.models.internal.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the two reports actually print.
 *
 * <h2>Why the assertions are on plain text</h2>
 * Because what an operator reads is the rendered line, and the templates are MiniMessage: asserting on
 * the source would pass for a template that never reached the output and would fail for a colour change
 * that nobody can see. Flattening to plain text asserts the one thing that matters — the figure is in the
 * line — and is also how the column alignment is measurable at all, since the padding is computed from
 * exactly this width.
 */
class StatusReportTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    @Test
    void theShortFormOfAHealthyServerIsATitleAndASummary() {
        final List<String> lines = plain(StatusReport.health(
                StatusFixtures.healthy(), StatusFixtures.withoutGaps()));

        assertAll(
                // No rows at all, and that is the report reading correctly rather than a hole in it:
                // with the verbosity off only the parts that are not fine are listed.
                () -> assertEquals(2, lines.size(), lines.toString()),
                () -> assertTrue(lines.get(0).contains("healthy"), lines.get(0)),
                () -> assertTrue(lines.get(1).contains("healthy"), lines.get(1)),
                // Nothing is hidden without being counted.
                () -> assertTrue(lines.get(1).contains("7 ok"), lines.get(1)));
    }

    @Test
    void theLongFormListsEverySevenPartsEvenWhenNoneHasAnythingToReport() {
        final StatusSettings verbose = StatusFixtures.verbose();
        final List<String> lines = plain(StatusReport.health(StatusFixtures.healthy(), verbose));
        final String joined = String.join("\n", lines);

        assertAll(
                () -> assertTrue(joined.contains(verbose.health().partRenderer()), joined),
                () -> assertTrue(joined.contains(verbose.health().partConfiguration()), joined),
                () -> assertTrue(joined.contains(verbose.health().partRigs()), joined),
                () -> assertTrue(joined.contains(verbose.health().partSimulation()), joined),
                () -> assertTrue(joined.contains(verbose.health().partDrivers()), joined),
                () -> assertTrue(joined.contains(verbose.health().partAi()), joined),
                () -> assertTrue(joined.contains(verbose.health().partInput()), joined));
    }

    @Test
    void theShortFormListsOnlyThePartThatIsWrong() {
        final StatusSettings settings = StatusFixtures.withoutGaps();
        final List<String> lines = plain(StatusReport.health(
                StatusFixtures.withSimulation(StatusFixtures.stoppedLoop(60)), settings));
        final String joined = String.join("\n", lines);

        assertAll(
                () -> assertEquals(3, lines.size(), joined),
                () -> assertTrue(joined.contains(settings.health().partSimulation()), joined),
                // The parts that are fine are not named, which is the whole point of the short form.
                () -> assertFalse(joined.contains(settings.health().partAi()), joined),
                () -> assertTrue(joined.contains("faulted"), joined));
    }

    @Test
    void aStaleLoopNamesBothRatesSoTheOperatorKnowsWhichOneIsWrong() {
        final List<String> lines = plain(StatusReport.health(
                StatusFixtures.withSimulation(StatusFixtures.staleLoop(60, 20)),
                StatusFixtures.withoutGaps()));
        final String joined = String.join("\n", lines);

        assertAll(
                () -> assertTrue(joined.contains("20"), joined),
                () -> assertTrue(joined.contains("60"), joined),
                () -> assertTrue(joined.contains("degraded"), joined));
    }

    @Test
    void aMissingAdaptersWholeSentenceReachesTheOperator() {
        final String reason = "no adapter shipped for 1.99, and the module was left out";
        final List<String> lines = plain(StatusReport.health(
                StatusFixtures.withAi(new StatusSnapshot.Ai(false, reason, 0)),
                StatusFixtures.withoutGaps()));

        // Truncating it would leave the one line that says which of the three reasons it was saying
        // none of them.
        assertTrue(String.join("\n", lines).contains(reason), lines.toString());
    }

    @Test
    void theRefusedReloadRowCarriesTheRevisionThatStayedLive() {
        final List<String> lines = plain(StatusReport.health(
                StatusFixtures.withConfiguration(
                        new StatusSnapshot.Configuration(12L, ReloadOutcome.REFUSED, 4)),
                StatusFixtures.withoutGaps()));
        final String joined = String.join("\n", lines);

        assertAll(
                () -> assertTrue(joined.contains("12"), joined),
                () -> assertTrue(joined.contains("4"), joined));
    }

    @Test
    void theKnownGapsAreNamedWhenAskedForAndOmittedWhenNot() {
        final StatusSettings settings = StatusFixtures.settings();
        final String withGaps = String.join("\n",
                plain(StatusReport.health(StatusFixtures.healthy(), settings)));
        final String withoutGaps = String.join("\n",
                plain(StatusReport.health(StatusFixtures.healthy(), StatusFixtures.withoutGaps())));

        assertAll(
                // On by default, and the culling one is the reason: a report that silently omitted it
                // reads as "culling is working" to the person deciding whether view.yml does anything.
                () -> assertTrue(settings.showKnownGaps()),
                () -> assertTrue(withGaps.contains("culling"), withGaps),
                () -> assertTrue(withGaps.contains("Emitted transforms"), withGaps),
                () -> assertTrue(withGaps.contains("Rig diagnostics"), withGaps),
                () -> assertFalse(withoutGaps.contains("culling"), withoutGaps));
    }

    @Test
    void everyFigureTheStatisticsReportCollectedIsPrinted() {
        final StatusSnapshot status = StatusFixtures.withWorld(
                new StatusSnapshot.World(4, 2, 1, 7, 48));
        final List<String> lines = plain(StatusReport.statistics(status, StatusFixtures.verbose()));
        final String joined = String.join("\n", lines);

        assertAll(
                () -> assertTrue(joined.contains("models bound"), joined),
                () -> assertTrue(joined.contains("decorations"), joined),
                () -> assertTrue(joined.contains("vehicles"), joined),
                () -> assertTrue(joined.contains("registry entries"), joined),
                () -> assertTrue(joined.contains("bones posed"), joined),
                () -> assertTrue(joined.contains("48"), joined),
                // The nominal ceiling, 20 Hz across 48 bones.
                () -> assertTrue(joined.contains("960"), joined));
    }

    @Test
    void theTransformRowAdmitsItIsNominalWhateverTheVerbosity() {
        final String terse = String.join("\n",
                plain(StatusReport.statistics(StatusFixtures.healthy(), StatusFixtures.settings())));
        final String verbose = String.join("\n",
                plain(StatusReport.statistics(StatusFixtures.healthy(), StatusFixtures.verbose())));

        assertAll(
                // The only line in either report that says a figure is derived rather than measured.
                // Hiding it at low verbosity would leave a number that reads like a measurement.
                () -> assertTrue(terse.contains("nominal"), terse),
                () -> assertTrue(verbose.contains("nominal"), verbose));
    }

    @Test
    void theRegistryRowExplainsWhyItDoesNotAddUpOnlyInTheLongForm() {
        final StatusSnapshot status = StatusFixtures.withWorld(
                new StatusSnapshot.World(4, 2, 1, 7, 48));
        final String verbose = String.join("\n",
                plain(StatusReport.statistics(status, StatusFixtures.verbose())));
        final String terse = String.join("\n",
                plain(StatusReport.statistics(status, StatusFixtures.settings())));

        assertAll(
                // A vehicle contributes itself and its visual, so the registry is larger than the three
                // kinds added up. An operator who found that and no explanation would reasonably
                // conclude the registry leaks.
                () -> assertTrue(verbose.contains("vehicle visual"), verbose),
                () -> assertFalse(terse.contains("vehicle visual"), terse));
    }

    @Test
    void aPendingRedispatchIsNotedOnTheRateRowWhateverTheVerbosity() {
        final StatusSnapshot stale = StatusFixtures.withSimulation(StatusFixtures.staleLoop(60, 20));
        final String terse = String.join("\n",
                plain(StatusReport.statistics(stale, StatusFixtures.settings())));

        // It is what makes the figure beside it untrue, so it is not a detail the short form may drop.
        assertTrue(terse.contains("re-dispatch"), terse);
    }

    @Test
    void theColumnsAreAlignedOnRenderedWidthRatherThanOnTemplateLength() {
        final List<String> rows = plain(StatusReport.health(
                StatusFixtures.healthy(), StatusFixtures.verbose()));

        // Drop the title and the summary and the gap lines: the padded rows are the ones carrying the
        // row prefix, and every one of them has to put its state cell at the same column.
        final List<String> padded = rows.stream()
                .filter(line -> line.startsWith("│"))
                .toList();
        final int first = padded.get(0).indexOf("ok");

        assertAll(
                () -> assertEquals(7, padded.size(), rows.toString()),
                () -> assertTrue(first > 0, padded.get(0)),
                () -> assertTrue(padded.stream().allMatch(line -> line.indexOf("ok") == first),
                        padded.toString()));
    }

    @Test
    void emptyingAMetricLabelRemovesItsWholeRow() {
        final StatusSettings defaults = StatusFixtures.verbose();
        final StatusSettings.Statistics labels = defaults.statistics();
        final StatusSettings withoutVehicles = new StatusSettings(
                defaults.verbose(), defaults.showKnownGaps(), defaults.box(), defaults.health(),
                new StatusSettings.Statistics(labels.title(), labels.metricCell(), labels.valueCell(),
                        labels.noteCell(), labels.metricModels(), labels.metricDecorations(),
                        "", labels.metricRegistered(), labels.metricSimulations(),
                        labels.metricDrivers(), labels.metricRigs(), labels.metricRigBones(),
                        labels.metricPosedBones(), labels.metricSimulationRate(),
                        labels.metricEmissionRate(), labels.metricTransformRate(),
                        labels.metricInputBindings(), labels.metricInputIssues(),
                        labels.noteVehicleEntries(), labels.noteEmissionWindow(),
                        labels.noteTransformRate(), labels.noteRedispatchPending(), labels.summary()));

        final int withRow = StatusReport.statistics(StatusFixtures.healthy(), defaults).size();
        final int withoutRow =
                StatusReport.statistics(StatusFixtures.healthy(), withoutVehicles).size();

        // A value with no question beside it is worse than no row, so an emptied label takes the row.
        assertEquals(withRow - 1, withoutRow);
    }

    @Test
    void aFullySilencedReportPrintsNothing() {
        final StatusSettings silent = StatusSettings.SILENT;

        assertAll(
                () -> assertTrue(StatusReport.health(StatusFixtures.healthy(), silent).isEmpty()),
                () -> assertTrue(StatusReport.statistics(StatusFixtures.healthy(), silent).isEmpty()));
    }

    @Test
    void anInputLayerWithNoCapabilitiesStillReportsItsBindingCount() {
        final List<String> lines = plain(StatusReport.health(
                StatusFixtures.withInput(new StatusSnapshot.Input(Set.of(), "packets off", 9, 1)),
                StatusFixtures.withoutGaps()));
        final String joined = String.join("\n", lines);

        assertAll(
                () -> assertTrue(joined.contains("9"), joined),
                () -> assertTrue(joined.contains("packets off"), joined));
    }

    private static @NotNull List<String> plain(@NotNull List<Component> lines) {
        return lines.stream().map(PLAIN::serialize).toList();
    }
}
