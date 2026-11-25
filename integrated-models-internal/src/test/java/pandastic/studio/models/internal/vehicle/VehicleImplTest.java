package pandastic.studio.models.internal.vehicle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.vehicle.RiderIntent;
import pandastic.studio.models.api.vehicle.VehicleSteering;
import pandastic.studio.models.internal.sim.SimulationLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The control loop's dispatch discipline, the mount rules, and the teardown.
 *
 * <p>Nothing here fakes a server. Everything a vehicle does to the world goes through the
 * {@link VehicleHost} seam, so what these tests observe is the sequence of operations the layer asks for
 * — which is exactly the thing a live server cannot be made to report on, because a position write from
 * the wrong thread and a write that left the owned chunks both fail without saying anything.
 */
class VehicleImplTest {

    private static final float EPSILON = 1.0E-5F;

    private final FakeVehicleHost host = new FakeVehicleHost();

    /**
     * The model's locators, which is where the fixture rig's single driver's seat lives.
     *
     * <p>Held as a field so a test can move the locator between two steps, which is what an animation
     * does and the reason a carrier is followed even when the vehicle has not moved.
     */
    private final FakeSeatLocators locators = VehicleFixtures.locators();

    private final FakeVehicleVisual visual =
            new FakeVehicleVisual(this.host.sharedJournal()).locators(this.locators);
    private final VehicleFixtures.SteppableClock clock = new VehicleFixtures.SteppableClock();
    private final List<Throwable> logged = new ArrayList<>();
    private final AtomicInteger stoppedCount = new AtomicInteger();

    private VehicleImpl vehicle() {
        return vehicle(VehicleSettingsSource.fixed(VehicleFixtures.settings()));
    }

    private VehicleImpl vehicle(VehicleSettingsSource settings) {
        return new VehicleImpl(VehicleFixtures.rig(), VehicleFixtures.profile(), this.host,
                this.visual, settings, this.clock,
                (message, thrown) -> this.logged.add(thrown), SeatChartSink.NONE,
                stopped -> this.stoppedCount.incrementAndGet());
    }

    private VehicleImpl started() {
        final VehicleImpl vehicle = vehicle();
        assertTrue(vehicle.start(), "the fixture host should allow a start");
        this.host.clearJournal();
        return vehicle;
    }

    /**
     * A started vehicle whose clock has been seeded by one step at zero elapsed time.
     *
     * <p>The seeding step is not a workaround: the first step of a vehicle's life genuinely has nothing
     * to difference against, which is asserted on its own below. Every test that is about what a vehicle
     * does <em>with</em> elapsed time has to get past it first.
     */
    private VehicleImpl primed() {
        final VehicleImpl vehicle = started();
        this.host.runOnce();
        this.host.clearJournal();
        return vehicle;
    }

    // ------------------------------------------------------------------ start order

    @Test
    void startSpawnsTheVisualBeforeSchedulingAnything() {
        // The visual is the step most likely to fail and the cheapest to unwind, because nothing is
        // scheduled yet.
        final VehicleImpl vehicle = vehicle();

        assertAll(
                () -> assertTrue(vehicle.start()),
                () -> assertEquals(List.of("visual.spawn", "repeat:3"), this.host.journal()),
                () -> assertTrue(vehicle.isAlive())
        );
    }

    @Test
    void startSeedsTheHeadingFromTheRootEntity() {
        // Starting at yaw zero would spin every vehicle to due south the moment it was created, which
        // looks like a bug in the model rather than in the vehicle.
        this.host.at(10.0D, 64.0D, 20.0D, 137F);
        final VehicleImpl vehicle = vehicle();
        vehicle.start();

        assertEquals(137F, vehicle.getState().yawDegrees(), EPSILON);
    }

    @Test
    void startDispatchesAtTheConfiguredPeriodRatherThanALiteral() {
        final VehicleImpl vehicle = vehicle(
                VehicleSettingsSource.fixed(VehicleFixtures.settingsWithPeriod(7L)));
        vehicle.start();

        assertAll(
                () -> assertEquals(7L, this.host.period()),
                () -> assertEquals(7L, vehicle.scheduledPeriodTicks())
        );
    }

    @Test
    void startRefusesFromAThreadThatDoesNotOwnTheRoot() {
        // The platform will not guard this: a position write or a mount from a foreign region thread is
        // undefined rather than racy, and it fails silently.
        this.host.owned(false);
        final VehicleImpl vehicle = vehicle();

        assertAll(
                () -> assertTrue(assertThrows(IllegalStateException.class, vehicle::start)
                        .getMessage().contains("thread")),
                () -> assertEquals(List.of(), this.host.journal(),
                        "nothing should have been touched before the thread check")
        );
    }

    @Test
    void aVisualThatRefusesToSpawnLeavesNothingScheduled() {
        final VehicleImpl vehicle = new VehicleImpl(VehicleFixtures.rig(), VehicleFixtures.profile(),
                this.host, new FakeVehicleVisual(this.host.sharedJournal()).refuseSpawn(),
                VehicleSettingsSource.fixed(VehicleFixtures.settings()), this.clock,
                SimulationLogger.DISCARDING, SeatChartSink.NONE,
                stopped -> this.stoppedCount.incrementAndGet());

        assertAll(
                () -> assertFalse(vehicle.start()),
                () -> assertFalse(vehicle.isAlive()),
                () -> assertEquals(List.of("visual.spawn"), this.host.journal())
        );
    }

    @Test
    void aVisualThatThrowsUnwindsAndDoesNotLeaveTheVehicleAlive() {
        final FakeVehicleVisual broken =
                new FakeVehicleVisual(this.host.sharedJournal()).throwOnSpawn();
        final VehicleImpl vehicle = new VehicleImpl(VehicleFixtures.rig(), VehicleFixtures.profile(),
                this.host, broken, VehicleSettingsSource.fixed(VehicleFixtures.settings()), this.clock,
                SimulationLogger.DISCARDING, SeatChartSink.NONE,
                stopped -> this.stoppedCount.incrementAndGet());

        assertAll(
                () -> assertThrows(IllegalStateException.class, vehicle::start),
                () -> assertFalse(vehicle.isAlive()),
                () -> assertFalse(this.host.isTaskAlive(), "nothing should have been scheduled")
        );
    }

    @Test
    void aRootThatVanishesBeforeTheDispatchLeavesTheVisualReleased() {
        // Scheduling can fail, returning null, and in that branch neither callback will ever run — so
        // nothing would tear the vehicle down and the unwind is the only exit. A tracker left open is a
        // model standing in the world that nothing holds a reference to.
        this.host.refuseSchedule(true);
        final VehicleImpl vehicle = vehicle();

        assertAll(
                () -> assertFalse(vehicle.start()),
                () -> assertFalse(vehicle.isAlive()),
                () -> assertEquals(1, this.visual.despawnCount())
        );
    }

    @Test
    void aRootAlreadyGoneIsRefusedBeforeTheVisualIsEvenSpawned() {
        final VehicleImpl vehicle = vehicle();
        this.host.alive(false);

        assertAll(
                () -> assertFalse(vehicle.start()),
                () -> assertEquals(List.of(), this.host.journal()),
                () -> assertEquals(0, this.visual.despawnCount())
        );
    }

    @Test
    void aStoppedHandleRefusesToStartAgain() {
        final VehicleImpl vehicle = started();
        vehicle.despawn();

        assertTrue(assertThrows(IllegalStateException.class, vehicle::start)
                .getMessage().contains("single use"));
    }

    // ------------------------------------------------------------------ the seat chart

    @Test
    void startingLogsTheSeatChartAfterTheVisualHasSpawned() {
        // After the visual because a seat is a locator of a live model, and before the loop because a
        // seat that did not resolve has to be reported whether or not the vehicle is ever driven.
        final RecordingChartSink sink = new RecordingChartSink();
        final VehicleImpl vehicle = new VehicleImpl(VehicleFixtures.seatedRig(),
                VehicleFixtures.profile(), this.host,
                new FakeVehicleVisual(this.host.sharedJournal())
                        .locators(VehicleFixtures.seatedLocators()),
                VehicleSettingsSource.fixed(VehicleFixtures.settings()), this.clock,
                SimulationLogger.DISCARDING, sink,
                stopped -> this.stoppedCount.incrementAndGet());

        assertTrue(vehicle.start());

        assertAll(
                () -> assertEquals(0, sink.info.size(),
                        "two of the four seats are unusable, so the whole chart is a warning"),
                () -> assertEquals(8, sink.warn.size(),
                        () -> "top, four seats, summary, note, bottom: " + sink.warn),
                () -> assertTrue(sink.warn.get(3).contains("BONE"),
                        () -> "the seat naming a bone has to be named: " + sink.warn),
                () -> assertTrue(sink.warn.get(4).contains("MISSING"),
                        () -> "and so does the one naming nothing: " + sink.warn),
                () -> assertTrue(sink.warn.get(5).contains("2 unusable"), () -> sink.warn.toString())
        );
    }

    @Test
    void anOperatorWhoTurnedTheChartOffGetsNoSeatLinesAtAll() {
        // Including the ones about seats that failed, which is the cost of switching it off and is
        // deliberate: a server that has read its chart once should be able to have its console back.
        final RecordingChartSink sink = new RecordingChartSink();
        final VehicleImpl vehicle = new VehicleImpl(VehicleFixtures.seatedRig(),
                VehicleFixtures.profile(), this.host,
                new FakeVehicleVisual(this.host.sharedJournal())
                        .locators(VehicleFixtures.seatedLocators()),
                VehicleSettingsSource.fixed(
                        VehicleFixtures.settingsWithSeats(VehicleFixtures.silentSeatSettings())),
                this.clock, SimulationLogger.DISCARDING, sink,
                stopped -> this.stoppedCount.incrementAndGet());

        assertTrue(vehicle.start());

        assertAll(
                () -> assertEquals(0, sink.info.size()),
                () -> assertEquals(0, sink.warn.size()),
                () -> assertEquals(2, vehicle.getSeatChart().usableCount(),
                        "the seats are still resolved; only the reporting is off")
        );
    }

    // ------------------------------------------------------------------ mounting

    @Test
    void mountingSeatsTheRiderOnACarrierAtTheLocatorAndPublishesThem() {
        // The whole point of the layer: the rider does not ride the root entity, they ride an invisible
        // carrier standing on the model's locator. The fixture's locator is half a block above the
        // model's origin and the configured carrier trim is another quarter.
        final VehicleImpl vehicle = started();
        final Player rider = StubPlayer.named("alepando");

        assertAll(
                () -> assertTrue(vehicle.mount(rider)),
                () -> assertSame(rider, vehicle.getRider().orElseThrow()),
                () -> assertEquals(List.of(rider), vehicle.getRiders()),
                () -> assertEquals(0.75D, this.host.onlyCarrier().y(), EPSILON,
                        "the carrier is spawned at the seat rather than moved there a tick later"),
                () -> assertSame(rider, this.host.onlyCarrier().rider()),
                () -> assertFalse(this.host.wasEjected(),
                        "nothing may ride the root entity, so nothing is ejected from it")
        );
    }

    @Test
    void aSecondRiderIsRefusedWhenTheRigDeclaresOneSeat() {
        final VehicleImpl vehicle = started();
        final Player rider = StubPlayer.named("alepando");
        assertTrue(vehicle.mount(rider));

        assertAll(
                () -> assertFalse(vehicle.mount(StubPlayer.named("someone-else")),
                        "a rig with one seat carries one rider"),
                () -> assertSame(rider, vehicle.getRider().orElseThrow()),
                () -> assertEquals(1, this.host.carriers().size(),
                        "a refused mount must not leave a carrier behind")
        );
    }

    @Test
    void aSeatThatResolvedToNothingCarriesNobody() {
        // The normal case today: no model in this repository contains a locator, so a rig's seats
        // resolve to nothing. A refusal rather than a seat on the root entity, which is the fallback
        // this design deliberately does not have.
        this.visual.locators(new FakeSeatLocators());
        final VehicleImpl vehicle = started();

        assertAll(
                () -> assertFalse(vehicle.mount(StubPlayer.named("alepando"))),
                () -> assertTrue(vehicle.getRider().isEmpty()),
                () -> assertEquals(List.of(), this.host.carriers()),
                () -> assertEquals(1, vehicle.getSeatChart().unusableCount())
        );
    }

    @Test
    void aSeatThatResolvedToABoneCarriesNobodyAndIsReportedAsTheMistakeItIs() {
        // The mistake the locator policy exists to catch. Four models in this repository carry a bone
        // called 'mount'; it is not a seat, and naming it must not quietly work.
        this.visual.locators(new FakeSeatLocators().bone("saddle"));
        final VehicleImpl vehicle = started();

        assertAll(
                () -> assertFalse(vehicle.mount(StubPlayer.named("alepando"))),
                () -> assertEquals(List.of(), this.host.carriers()),
                () -> assertEquals(SeatLocators.Kind.BONE,
                        vehicle.getSeatChart().entries().getFirst().kind())
        );
    }

    @Test
    void aDriverTheServerWouldHandToTheClientIsUndoneAndRefusedLoudly() {
        // The design trap, and the reason the check is here rather than at creation: the server's answer
        // depends on who is riding what, so before the mount there is nothing to ask about. Asked about
        // the carrier as well as the root, because with a carrier in between the root's answer is about
        // the carrier and not about the player.
        final VehicleImpl vehicle = started();
        this.host.carrierClientSteered(true);

        final IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> vehicle.mount(StubPlayer.named("alepando")));

        assertAll(
                () -> assertTrue(thrown.getMessage().contains("controlling passenger")),
                () -> assertTrue(thrown.getMessage().contains("driver")),
                () -> assertTrue(vehicle.getRider().isEmpty()),
                () -> assertEquals(1, this.host.onlyCarrier().releases(),
                        "the carrier has to go back rather than stay in the world unridden")
        );
    }

    @Test
    void aMountThePlatformRefusesIsAReturnValueRatherThanAnException() {
        // The platform guards a mount across a region boundary. That is a race a caller cannot avoid, so
        // it must not look like a mistake it made — and the carrier spawned for it must not survive it.
        final VehicleImpl vehicle = started();
        this.host.carrierRefusesMount(true);

        assertAll(
                () -> assertFalse(vehicle.mount(StubPlayer.named("alepando"))),
                () -> assertTrue(vehicle.getRider().isEmpty()),
                () -> assertEquals(1, this.host.onlyCarrier().releases())
        );
    }

    @Test
    void aSeatWithNoCarrierIsAReturnValueRatherThanAnException() {
        // What an unrecognised carrier entity type in vehicle.yml looks like from here, and what a host
        // with no carrier support at all looks like.
        final VehicleImpl vehicle = started();
        this.host.refuseCarrier(true);

        assertAll(
                () -> assertFalse(vehicle.mount(StubPlayer.named("alepando"))),
                () -> assertTrue(vehicle.getRider().isEmpty())
        );
    }

    @Test
    void mountingAndDismountingRefuseFromAForeignThread() {
        final VehicleImpl vehicle = started();
        this.host.owned(false);

        assertAll(
                () -> assertThrows(IllegalStateException.class,
                        () -> vehicle.mount(StubPlayer.named("alepando"))),
                () -> assertThrows(IllegalStateException.class, vehicle::dismount)
        );
    }

    @Test
    void dismountingClearsTheRiderAndTheSteering() {
        final VehicleImpl vehicle = started();
        vehicle.mount(StubPlayer.named("alepando"));
        this.host.holding(RiderIntent.ofBits(RiderIntent.FORWARD_BIT));
        this.clock.advanceTicks(1.0D);
        this.host.runOnce();

        assertAll(
                () -> assertTrue(vehicle.dismount()),
                () -> assertTrue(vehicle.getRider().isEmpty()),
                () -> assertEquals(VehicleSteering.NEUTRAL, vehicle.getSteering()),
                () -> assertFalse(vehicle.dismount(), "there is nobody left to put down")
        );
    }

    @Test
    void aRiderWhoLeftWithoutAskingIsNoticedOnTheNextStepAndTheirCarrierGoes() {
        // Re-checked every step rather than tracked through events, so a rider who held sneak,
        // disconnected, died or was removed is noticed with nothing having had to be delivered. The
        // carrier goes with them: one exists per occupied seat and that seat is no longer occupied.
        final VehicleImpl vehicle = started();
        vehicle.mount(StubPlayer.named("alepando"));
        this.host.onlyCarrier().seat(null);

        this.clock.advanceTicks(1.0D);
        this.host.runOnce();

        assertAll(
                () -> assertTrue(vehicle.getRider().isEmpty()),
                () -> assertEquals(List.of(), vehicle.getRiders()),
                () -> assertEquals(1, this.host.onlyCarrier().releases())
        );
    }

    @Test
    void somebodyElseAppearingOnACarrierIsNotDrivenFor() {
        // A passenger who never went through mount() never had the authority check run against them, so
        // this layer gives the seat up rather than treating the carrier as the source of truth.
        final VehicleImpl vehicle = started();
        vehicle.mount(StubPlayer.named("alepando"));
        this.host.onlyCarrier().seat(StubPlayer.named("intruder"));

        this.clock.advanceTicks(1.0D);
        this.host.runOnce();

        assertAll(
                () -> assertTrue(vehicle.getRider().isEmpty()),
                () -> assertEquals(1, this.host.onlyCarrier().releases())
        );
    }

    // ------------------------------------------------------------------ stepping

    @Test
    void theFirstStepHasNoElapsedTimeAndThereforeNoMovement() {
        // The clock has nothing to difference against, and inventing a gap would make a vehicle's first
        // tick depend on when it happened to be created.
        final VehicleImpl vehicle = started();
        vehicle.mount(StubPlayer.named("alepando"));
        this.host.holding(RiderIntent.ofBits(RiderIntent.FORWARD_BIT));
        this.host.clearJournal();

        this.host.runOnce();

        assertAll(
                () -> assertEquals(0F, vehicle.getState().forwardSpeedBlocksPerTick()),
                () -> assertEquals(List.of(), this.host.rootJournal())
        );
    }

    @Test
    void anOrdinaryStepWritesTheHeadingBeforeTheDisplacement() {
        // The order the solver integrated them in: a vehicle that turns while accelerating travels along
        // the heading it ends the step with, so applying the translation first would put it a half-step
        // behind its own controls.
        final VehicleImpl vehicle = primed();
        vehicle.mount(StubPlayer.named("alepando"));
        this.host.holding(
                RiderIntent.ofBits(RiderIntent.FORWARD_BIT | RiderIntent.RIGHT_BIT));
        this.host.clearJournal();

        this.clock.advanceTicks(1.0D);
        this.host.runOnce();
        this.clock.advanceTicks(1.0D);
        this.host.runOnce();

        assertAll(
                () -> assertEquals(List.of("setYaw:10.0", "moveLocal", "setYaw:20.0", "moveLocal"),
                        this.host.rootJournal()),
                () -> assertEquals(20F, vehicle.getState().yawDegrees(), EPSILON)
        );
    }

    @Test
    void anIdleVehicleWritesNothingAtAll() {
        final VehicleImpl vehicle = started();

        for (int step = 0; step < 5; step++) {
            this.clock.advanceTicks(1.0D);
            this.host.runOnce();
        }

        assertAll(
                () -> assertEquals(List.of(), this.host.journal()),
                () -> assertTrue(vehicle.isAlive())
        );
    }

    @Test
    void aTeleportSizedStepGoesThroughTheRelocationAndStopsDecidingUntilItCompletes() {
        // The riskiest path in the layer. A cross-region teleport removes the entity and its whole
        // passenger tree from the world before re-adding it, so continuing to move it in the meantime
        // fails confusingly later rather than immediately.
        final VehicleImpl vehicle = primed();
        vehicle.mount(StubPlayer.named("alepando"));
        this.host.holding(RiderIntent.ofBits(RiderIntent.FORWARD_BIT));
        this.host.at(0.0D, 64.0D, 0.0D, 0F);
        this.host.clearJournal();

        // Six clamped ticks of acceleration is well past the two-block local threshold in the fixture.
        this.clock.advanceTicks(100.0D);
        this.host.runOnce();

        assertAll(
                () -> assertTrue(this.host.journal().contains("teleport")),
                () -> assertTrue(vehicle.isRelocating()),
                () -> assertTrue(this.host.hasPendingTeleport())
        );

        this.host.clearJournal();
        this.clock.advanceTicks(1.0D);
        this.host.runOnce();
        assertEquals(List.of(), this.host.journal(),
                "nothing may be written while the entity is transiently removed");

        this.host.completeTeleport(true);
        assertFalse(vehicle.isRelocating());
    }

    @Test
    void aRelocationThatFailedStillClearsTheGate() {
        // Otherwise the vehicle never decides anything again, which is a worse outcome than a teleport
        // that did not happen.
        final VehicleImpl vehicle = primed();
        vehicle.mount(StubPlayer.named("alepando"));
        this.host.holding(RiderIntent.ofBits(RiderIntent.FORWARD_BIT));
        this.clock.advanceTicks(100.0D);
        this.host.runOnce();

        this.host.completeTeleport(false);

        assertFalse(vehicle.isRelocating());
    }

    @Test
    void aSkippedStepDiscardsItsElapsedTimeRatherThanHandingItOn() {
        // The alternative lets a vehicle "catch up" across a relocation, which is exactly the jump the
        // clamp exists to prevent.
        final VehicleImpl vehicle = primed();
        vehicle.mount(StubPlayer.named("alepando"));
        this.host.holding(RiderIntent.ofBits(RiderIntent.FORWARD_BIT));
        this.clock.advanceTicks(100.0D);
        this.host.runOnce();

        // A long pause while the relocation is in flight, then one ordinary tick after it completes.
        this.clock.advanceTicks(100.0D);
        this.host.runOnce();
        this.host.completeTeleport(true);
        this.host.clearJournal();
        this.clock.advanceTicks(1.0D);
        this.host.runOnce();

        // One tick at the top speed is one block, which is inside the local threshold. Had the skipped
        // step's hundred ticks been carried forward, this would have been another relocation.
        assertTrue(this.host.journal().contains("moveLocal"),
                () -> "expected an in-place write but got " + this.host.journal());
    }

    @Test
    void aHostWithNoInPlaceMoveRoutesEverythingThroughTheRelocation() {
        // What a server running with no Mojang-mapped adapter looks like: expensive rather than wrong.
        this.host.localMove(false);
        final VehicleImpl vehicle = primed();
        vehicle.mount(StubPlayer.named("alepando"));
        this.host.holding(RiderIntent.ofBits(RiderIntent.FORWARD_BIT));
        this.host.clearJournal();

        this.clock.advanceTicks(1.0D);
        this.host.runOnce();

        assertAll(
                () -> assertTrue(this.host.journal().contains("teleport")),
                () -> assertFalse(this.host.journal().contains("moveLocal"))
        );
    }

    @Test
    void aStepThatThrowsIsReportedAndTheLoopSurvives() {
        // The platform swallows anything that escapes a scheduled body and keeps the task alive, so an
        // uncaught failure would be a vehicle that quietly stops responding and never explains itself.
        final boolean[] explode = {false};
        final VehicleImpl vehicle = vehicle(() -> {
            if (explode[0]) {
                throw new IllegalStateException("configuration exploded");
            }
            return VehicleFixtures.settings();
        });
        assertTrue(vehicle.start());
        this.host.runOnce();

        explode[0] = true;
        this.clock.advanceTicks(1.0D);
        this.host.runOnce();

        assertAll(
                () -> assertEquals(1, this.logged.size(), "the failure has to reach the log"),
                () -> assertTrue(vehicle.isAlive(),
                        "one bad step must not silently stop the vehicle for good"),
                () -> assertTrue(this.host.isTaskAlive())
        );
    }

    // ------------------------------------------------------------------ teardown

    @Test
    void despawningCancelsTheLoopThenReleasesTheCarriersThenTheVisual() {
        // The carriers go first and from whatever thread this is, because a leaked carrier is an
        // invisible entity holding a player that nothing will ever remove — so it must not be behind
        // either of the two steps that can fail.
        final VehicleImpl vehicle = started();
        vehicle.mount(StubPlayer.named("alepando"));
        this.host.clearJournal();

        vehicle.despawn();

        assertAll(
                () -> assertFalse(vehicle.isAlive()),
                () -> assertFalse(this.host.isTaskAlive()),
                () -> assertEquals(List.of("carrier0.release", "dismount", "visual.despawn"),
                        this.host.journal(),
                        "stop deciding, then release what was being decided about"),
                () -> assertEquals(1, this.stoppedCount.get()),
                () -> assertTrue(vehicle.getRiders().isEmpty())
        );
    }

    @Test
    void despawningIsIdempotentHoweverManyDirectionsItArrivesFrom() {
        final VehicleImpl vehicle = started();

        vehicle.despawn();
        vehicle.despawn();
        vehicle.close();

        assertAll(
                () -> assertEquals(1, this.visual.despawnCount()),
                () -> assertEquals(1, this.stoppedCount.get()),
                () -> assertTrue(vehicle.isStopped())
        );
    }

    @Test
    void despawningFromAForeignThreadDefersTheWorldMutation() {
        // Ejecting a passenger is a world mutation, so it has to reach the owning thread — but the
        // visual releases itself from anywhere, which is why it is the one thing that happens in every
        // case.
        final VehicleImpl vehicle = started();
        vehicle.mount(StubPlayer.named("alepando"));
        this.host.clearJournal();
        this.host.owned(false);

        vehicle.despawn();

        assertAll(
                () -> assertEquals(List.of("carrier0.release", "submit"), this.host.journal(),
                        "a carrier releases itself through its own scheduler, so it does not wait"),
                () -> assertEquals(0, this.visual.despawnCount())
        );

        this.host.drainSubmitted();
        assertEquals(List.of("carrier0.release", "submit", "dismount", "visual.despawn"),
                this.host.journal());
    }

    @Test
    void despawningWithNoThreadLeftStillReleasesTheVisual() {
        final VehicleImpl vehicle = started();
        this.host.owned(false).refuseSubmit(true);
        this.host.clearJournal();

        vehicle.despawn();

        assertEquals(List.of("visual.despawn"), this.host.journal());
    }

    @Test
    void theRetiredCallbackReleasesTheVisualAndDoesNotTouchTheWorld() {
        // The platform documents a retired callback as critical code that may not remove entities, and
        // it is unschedulable because the scheduler that would run the deferred work is the one that has
        // just retired. There is also nobody left to eject: the root entity is what has gone.
        final VehicleImpl vehicle = started();
        vehicle.mount(StubPlayer.named("alepando"));
        this.host.clearJournal();

        this.host.retire();

        assertAll(
                () -> assertFalse(vehicle.isAlive()),
                () -> assertEquals(List.of("carrier0.release", "visual.despawn"), this.host.journal(),
                        "the carriers are still released: each has its own entity scheduler, which is"
                                + " the whole reason this path can clean them up at all"),
                () -> assertEquals(1, this.stoppedCount.get())
        );
    }

    @Test
    void aStepThatFindsItsRootGoneTearsTheVehicleDown() {
        // The other side of the exactly-once guarantee: a root that is dead but whose scheduler has not
        // retired yet is noticed here rather than never.
        final VehicleImpl vehicle = started();
        this.host.alive(false);

        this.clock.advanceTicks(1.0D);
        this.host.runOnce();

        assertAll(
                () -> assertFalse(vehicle.isAlive()),
                () -> assertTrue(vehicle.isStopped()),
                () -> assertEquals(1, this.stoppedCount.get())
        );
    }

    @Test
    void aStoppedVehicleReportsItselfStoppedWithoutForgettingItsHeading() {
        this.host.at(0.0D, 64.0D, 0.0D, 45F);
        final VehicleImpl vehicle = vehicle();
        vehicle.start();

        vehicle.despawn();

        assertAll(
                () -> assertEquals(0F, vehicle.getState().forwardSpeedBlocksPerTick()),
                () -> assertEquals(45F, vehicle.getState().yawDegrees(), EPSILON)
        );
    }

    // ------------------------------------------------------------------ reload

    @Test
    void reloadRedispatchesOnlyWhenTheConfiguredPeriodMoved() {
        final long[] period = {3L};
        final VehicleImpl vehicle =
                vehicle(() -> VehicleFixtures.settingsWithPeriod(period[0]));
        vehicle.start();

        assertAll(
                () -> assertFalse(vehicle.reload(), "nothing moved, so nothing is re-dispatched"),
                () -> assertEquals(1, this.host.scheduleCount())
        );

        period[0] = 5L;
        assertAll(
                () -> assertTrue(vehicle.reload()),
                () -> assertEquals(2, this.host.scheduleCount()),
                () -> assertEquals(5L, this.host.period()),
                () -> assertEquals(5L, vehicle.scheduledPeriodTicks())
        );
    }

    @Test
    void reloadOnAStoppedVehicleDoesNothing() {
        final VehicleImpl vehicle = started();
        vehicle.despawn();

        assertFalse(vehicle.reload());
    }

    @Test
    void aReloadThatFindsTheRootGoneStopsTheVehicle() {
        // The cancel has already removed the only task that would have told us, so nothing else will.
        final long[] period = {3L};
        final VehicleImpl vehicle = vehicle(() -> VehicleFixtures.settingsWithPeriod(period[0]));
        vehicle.start();
        period[0] = 5L;
        this.host.alive(false);

        assertAll(
                () -> assertFalse(vehicle.reload()),
                () -> assertTrue(vehicle.isStopped())
        );
    }

    /** A chart sink that keeps the plain text of what it was told, per severity. */
    private static final class RecordingChartSink implements SeatChartSink {

        private final List<String> info = new ArrayList<>();
        private final List<String> warn = new ArrayList<>();

        @Override
        public void info(@NotNull Component line) {
            this.info.add(PlainTextComponentSerializer.plainText().serialize(line));
        }

        @Override
        public void warn(@NotNull Component line) {
            this.warn.add(PlainTextComponentSerializer.plainText().serialize(line));
        }
    }
}
