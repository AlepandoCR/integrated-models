package pandastic.studio.models.internal.command;

import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.input.InputCapability;
import pandastic.studio.models.api.rig.Rig;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the status snapshot concludes from what it was told.
 *
 * <h2>Why this is the suite that matters</h2>
 * Because every judgement a health report makes is here, and all of them are judgements about degrees:
 * whether a missing NMS adapter is a fault or a reduced feature set, whether a refused reload is worth
 * reporting at all, whether a loop running at the wrong period counts as running. Each of those has a
 * defensible answer and a wrong one, and a report that picked the wrong one would still look like a
 * working command — which is the only class of defect a status feature can actually ship.
 */
class StatusSnapshotTest {

    @Test
    void aServerWithEveryPartWorkingIsHealthy() {
        final StatusSnapshot status = StatusFixtures.healthy();

        assertAll(
                () -> assertEquals(StatusSnapshot.State.OK, status.verdict()),
                () -> assertEquals(status.partCount(), status.countOf(StatusSnapshot.State.OK)),
                () -> assertEquals(0, status.countOf(StatusSnapshot.State.DEGRADED)),
                () -> assertEquals(0, status.countOf(StatusSnapshot.State.DOWN)));
    }

    @Test
    void theVerdictIsTheWorstPartRatherThanTheAverage() {
        final StatusSnapshot stopped = StatusFixtures.withSimulation(StatusFixtures.stoppedLoop(60));

        assertAll(
                // Six parts fine and one down is not "mostly fine": the operator's next action depends
                // entirely on the one that is down.
                () -> assertEquals(StatusSnapshot.State.DOWN, stopped.verdict()),
                () -> assertEquals(1, stopped.countOf(StatusSnapshot.State.DOWN)),
                () -> assertEquals(stopped.partCount() - 1,
                        stopped.countOf(StatusSnapshot.State.OK)));
    }

    @Test
    void aMissingAiAdapterIsDegradedAndNeverDown() {
        final StatusSnapshot noAi = StatusFixtures.withAi(
                new StatusSnapshot.Ai(false, "no adapter for 1.99", 0));

        assertAll(
                // The rendering pipeline does not need NMS, so this is a feature set the operator may
                // never use rather than a broken server. Reporting it as down would send somebody
                // looking for a fault that is not there.
                () -> assertEquals(StatusSnapshot.State.DEGRADED, noAi.ai().state()),
                () -> assertEquals(StatusSnapshot.State.DEGRADED, noAi.verdict()),
                () -> assertEquals(0, noAi.countOf(StatusSnapshot.State.DOWN)));
    }

    @Test
    void aRefusedReloadIsReportedEvenThoughNothingChanged() {
        final StatusSnapshot refused = StatusFixtures.withConfiguration(
                new StatusSnapshot.Configuration(7L, ReloadOutcome.REFUSED, 3));
        final StatusSnapshot threw = StatusFixtures.withConfiguration(
                new StatusSnapshot.Configuration(7L, ReloadOutcome.FAILED, 0));

        assertAll(
                // The one degradation on the server that is otherwise invisible: the previous values
                // stay live by identity, so the files on disk and the values in force disagree and
                // nothing else will ever mention it.
                () -> assertEquals(StatusSnapshot.State.DEGRADED, refused.configuration().state()),
                () -> assertEquals(StatusSnapshot.State.DEGRADED, threw.configuration().state()),
                // And the revision is still the live one, because a refusal published nothing.
                () -> assertEquals(7L, refused.configuration().revision()));
    }

    @Test
    void noReloadObservedIsNotTheSameClaimAsAReloadThatWorked() {
        final StatusSnapshot fresh = StatusFixtures.withConfiguration(
                new StatusSnapshot.Configuration(0L, ReloadOutcome.UNKNOWN, 0));

        assertAll(
                () -> assertEquals(StatusSnapshot.State.OK, fresh.configuration().state()),
                () -> assertEquals(ReloadOutcome.UNKNOWN, fresh.configuration().lastReload()));
    }

    @Test
    void aLoopRunningAtAPeriodNobodyAskedForIsDegraded() {
        final StatusSnapshot.Loop stale = StatusFixtures.staleLoop(60, 20);

        assertAll(
                () -> assertTrue(stale.running()),
                () -> assertTrue(stale.isRedispatchPending()),
                () -> assertEquals(StatusSnapshot.State.DEGRADED, stale.state()),
                // Both numbers have to come out, because the report names the one that is wrong.
                () -> assertEquals(60, stale.configuredHz()),
                () -> assertEquals(20, stale.dispatchedHz()));
    }

    @Test
    void aRunningLoopAtTheConfiguredRateIsNotPendingARedispatch() {
        final StatusSnapshot.Loop live = StatusFixtures.runningLoop(60, 3);

        assertAll(
                () -> assertFalse(live.isRedispatchPending()),
                () -> assertEquals(StatusSnapshot.State.OK, live.state()),
                // 60 Hz is 16.666 ms, and the period is held in nanoseconds precisely so that the rate
                // does not have to be quantised to a whole millisecond. Reporting 59 for it would be a
                // worse lie than rounding.
                () -> assertEquals(60, live.dispatchedHz()));
    }

    @Test
    void aStoppedLoopIsDownAndNeverPendingARedispatch() {
        final StatusSnapshot.Loop stopped = StatusFixtures.stoppedLoop(60);

        assertAll(
                () -> assertEquals(StatusSnapshot.State.DOWN, stopped.state()),
                // A stopped loop reports whatever it was last dispatched at, which is history rather
                // than a disagreement with the configuration.
                () -> assertFalse(stopped.isRedispatchPending()),
                () -> assertEquals(0, stopped.dispatchedHz()));
    }

    @Test
    void anInputAuditComplaintIsADegradationEvenWithEveryCapabilityPresent() {
        final StatusSnapshot withIssues = StatusFixtures.withInput(new StatusSnapshot.Input(
                Set.of(InputCapability.values()[0]), "", 4, 2));
        final StatusSnapshot clean = StatusFixtures.withInput(new StatusSnapshot.Input(
                Set.of(InputCapability.values()[0]), "", 4, 0));

        assertAll(
                // A binding the controller refuses to dispatch is a consumer's feature silently doing
                // nothing, which is exactly what this command exists to surface.
                () -> assertEquals(StatusSnapshot.State.DEGRADED, withIssues.input().state()),
                () -> assertEquals(StatusSnapshot.State.OK, clean.input().state()),
                () -> assertEquals(1, clean.input().capabilityCount()));
    }

    @Test
    void aMissingRenderingBackendIsTheOnlyFatalPart() {
        final StatusSnapshot.Renderer missing = new StatusSnapshot.Renderer(
                false, "", List.of("a provider failed to link", "another threw"));

        assertAll(
                () -> assertEquals(StatusSnapshot.State.DOWN, missing.state()),
                // The rejections are the finding, and they are joined rather than dropped: "no backend"
                // has three causes that look identical from outside and need three different responses.
                () -> assertTrue(missing.reason().contains("failed to link")),
                () -> assertTrue(missing.reason().contains("another threw")));
    }

    @Test
    void aPresentBackendHasNothingToExplain() {
        assertEquals("", StatusFixtures.healthy().renderer().reason());
    }

    @Test
    void zeroRigsIsANormalStateRatherThanAFault() {
        final StatusSnapshot.Rigs none = StatusSnapshot.Rigs.of(List.of());

        assertAll(
                // A consumer may bind entirely against rigs derived from the renderer's own models and
                // never write a rig file, so an empty directory is not a finding.
                () -> assertEquals(StatusSnapshot.State.OK, none.state()),
                () -> assertEquals(0, none.loaded()),
                () -> assertEquals(0, none.bones()));
    }

    @Test
    void theRigBoneTotalIsTheSumOverEveryLoadedRig() {
        final List<Rig> rigs = List.of(
                StatusFixtures.rig("one", 3),
                StatusFixtures.rig("two", 5));

        final StatusSnapshot.Rigs counted = StatusSnapshot.Rigs.of(rigs);

        assertAll(
                () -> assertEquals(2, counted.loaded()),
                () -> assertEquals(8, counted.bones()));
    }

    @Test
    void thePosedBoneTotalIsTheSumOverEveryRegisteredModelsRig() {
        assertEquals(9, StatusSnapshot.World.posedBones(
                StatusFixtures.modelsWithBones(2, 3, 4)));
    }

    @Test
    void thePosedBoneTotalOfAnEmptyServerIsZero() {
        assertEquals(0, StatusSnapshot.World.posedBones(List.of()));
    }

    @Test
    void theTransformRateIsTheEmissionRateTimesThePosedBones() {
        // 20 Hz across 48 bones. A ceiling rather than a measurement, which is why the report says so.
        assertEquals(960L, StatusFixtures.healthy().nominalTransformsPerSecond());
    }

    @Test
    void theWorstOfTwoStatesIsTheMoreSevereOne() {
        assertAll(
                () -> assertEquals(StatusSnapshot.State.DOWN, StatusSnapshot.State.worseOf(
                        StatusSnapshot.State.DEGRADED, StatusSnapshot.State.DOWN)),
                () -> assertEquals(StatusSnapshot.State.DOWN, StatusSnapshot.State.worseOf(
                        StatusSnapshot.State.DOWN, StatusSnapshot.State.OK)),
                () -> assertEquals(StatusSnapshot.State.DEGRADED, StatusSnapshot.State.worseOf(
                        StatusSnapshot.State.OK, StatusSnapshot.State.DEGRADED)),
                () -> assertEquals(StatusSnapshot.State.OK, StatusSnapshot.State.worseOf(
                        StatusSnapshot.State.OK, StatusSnapshot.State.OK)));
    }

    @Test
    void aNonsensicalFigureIsRefusedRatherThanReported() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new StatusSnapshot.Rigs(-1, 0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new StatusSnapshot.Configuration(-1L, ReloadOutcome.APPLIED, 0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new StatusSnapshot.Loop(true, 0, 1L, 1L, 0)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new StatusSnapshot.Emission(0, 2)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new StatusSnapshot.World(0, 0, 0, 0, -3)));
    }
}
