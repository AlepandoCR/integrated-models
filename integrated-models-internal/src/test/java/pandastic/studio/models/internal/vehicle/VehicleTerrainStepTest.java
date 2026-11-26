package pandastic.studio.models.internal.vehicle;

import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.vehicle.RiderIntent;
import pandastic.studio.models.api.vehicle.TerrainResponse;
import pandastic.studio.models.api.vehicle.Vehicle;
import pandastic.studio.models.api.vehicle.VehicleKind;
import pandastic.studio.models.api.vehicle.VehicleProfile;
import pandastic.studio.models.internal.model.ModelManagerImpl;
import pandastic.studio.models.internal.sim.SimulationLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The terrain reaction inside a real control step: what is probed, what is written, and what is not.
 *
 * <p>{@link TerrainSolverTest} owns the arithmetic. This suite owns the three things only the step can
 * get wrong — whether a vehicle that says nothing about terrain pays for any of it, whether a rise
 * reaches the world as a displacement the client can interpolate across rather than as a teleport, and
 * whether the rotation is pushed at the rate the wire can actually carry.
 *
 * <p>Nothing here fakes a server. Every block read and every position write goes through the
 * {@link VehicleHost} seam, which is the only reason the look-ahead probe's refusal across a region
 * boundary can be exercised at all: on a live server that read does not fail, it silently returns
 * nonsense.
 */
class VehicleTerrainStepTest {

    private static final float EPSILON = 1.0E-5F;

    /** The surface the scripted vehicle stands on. */
    private static final double GROUND_Y = 64.0D;

    /** A climbing, leaning, slope-following cart that snaps to its targets. */
    private static final TerrainResponse RESPONSE =
            VehicleFixtures.snapping(20F, 24F, 0F, 1F, 4);

    private final FakeVehicleHost host = new FakeVehicleHost();
    private final FakeVehicleVisual visual =
            new FakeVehicleVisual(this.host.sharedJournal()).locators(VehicleFixtures.locators());
    private final VehicleFixtures.SteppableClock clock = new VehicleFixtures.SteppableClock();
    private final List<Throwable> logged = new ArrayList<>();
    private final AtomicInteger stoppedCount = new AtomicInteger();

    // ------------------------------------------------------------------ the inert vehicle pays nothing

    @Test
    void aVehicleThatSaysNothingAboutTerrainReadsNoBlocksAndTakesOverNoRotation() {
        // The compatibility claim, and the half of it that is not about arithmetic. Taking over the
        // renderer's whole-model rotation replaces the body rotation that was making the model follow
        // the entity's heading, so a vehicle with nothing to say must not touch it — it would be trading
        // the renderer's own smoothing for the value it already had.
        final VehicleImpl vehicle = primed(VehicleFixtures.profile());
        this.clock.advanceTicks(3);
        this.host.runOnce();

        assertAll(
                () -> assertEquals(0, this.host.probeCount()),
                () -> assertEquals(0, this.visual.rotationCount()),
                () -> assertTrue(vehicle.getAttitude().isLevel())
        );
    }

    // ------------------------------------------------------------------ the probe

    @Test
    void aTerrainActiveVehicleProbesOncePerStepWithTheReachItsOwnProfileImplies() {
        // One reading per step, read once — the rule the snapshot and the rider's input already follow.
        // The reach is derived from the profile's step height rather than configured, so the journal
        // line is the only place that derivation is visible.
        this.host.terrain(GROUND_Y, GROUND_Y);
        primed(terrain());
        this.host.clearJournal();

        this.clock.advanceTicks(1);
        this.host.runOnce();

        assertAll(
                () -> assertEquals(1,
                        this.host.journal().stream().filter(line -> line.startsWith("probe:")).count()),
                () -> assertTrue(this.host.journal().contains("probe:1.0"),
                        () -> "a one-block step height reaches one block: " + this.host.journal())
        );
    }

    // ------------------------------------------------------------------ the climb

    @Test
    void aClimbIsWrittenAsAVerticalDisplacementSpreadOverItsDuration() {
        // The protocol fact, end to end: the rise reaches the world as a quarter of a block per tick
        // through the in-place move, which is a displacement small enough for the client to draw across.
        // A whole block in one write is the thing this is arranged to avoid.
        this.host.at(0D, GROUND_Y, 0D, 0F).terrain(GROUND_Y, GROUND_Y + 1.0D);
        primed(terrain());

        final double before = this.host.y();
        this.clock.advanceTicks(1);
        this.host.runOnce();
        final double afterOne = this.host.y();
        this.clock.advanceTicks(1);
        this.host.runOnce();

        assertAll(
                () -> assertEquals(0.25D, afterOne - before, EPSILON),
                () -> assertEquals(0.5D, this.host.y() - before, EPSILON),
                () -> assertTrue(this.host.journal().contains("moveLocal"))
        );
    }

    @Test
    void aVehicleWithNothingToDoButClimbIsNotJudgedIdle() {
        // The ordering inside the plan: the rise is folded into the move before its kind is taken, so a
        // parked vehicle climbing onto a block has a local displacement rather than nothing at all.
        // Reversed, a vehicle that was pushed against a step would never climb it.
        this.host.at(0D, GROUND_Y, 0D, 0F).terrain(GROUND_Y, GROUND_Y + 1.0D);
        final VehicleImpl vehicle = primed(terrain());

        this.clock.advanceTicks(1);
        this.host.runOnce();

        assertAll(
                () -> assertTrue(vehicle.getSteering().isNeutral(), "nobody is driving it"),
                () -> assertEquals(0D, vehicle.getState().forwardSpeedBlocksPerTick(), EPSILON),
                () -> assertEquals(GROUND_Y + 0.25D, this.host.y(), EPSILON)
        );
    }

    @Test
    void aStepWhoseWriteHasToTeleportCarriesNoRiseAtAll() {
        // A teleport applies an absolute position with no interpolation, so a block of rise inside one
        // would snap however carefully the rest was spread. The climb is dropped rather than deferred,
        // because a relocation puts the vehicle on terrain nobody has probed.
        this.host.at(0D, GROUND_Y, 0D, 0F).terrain(GROUND_Y, GROUND_Y + 1.0D)
                .holding(RiderIntent.ofBits(RiderIntent.FORWARD_BIT));
        final VehicleImpl vehicle = primed(terrain());
        seat(vehicle);

        // Six ticks at a block a tick is six blocks, well past the two-block local-step threshold.
        this.clock.advanceTicks(6);
        this.host.runOnce();

        assertAll(
                () -> assertTrue(this.host.hasPendingTeleport()),
                () -> assertEquals(GROUND_Y, this.host.y(), EPSILON, "no rise through a teleport"),
                () -> assertFalse(vehicle.getAttitude().isClimbing(),
                        "a relocation lands on terrain nobody probed, so the climb is forgotten"),
                () -> assertEquals(0F, vehicle.getAttitude().bankDegrees(), EPSILON,
                        "and the angles survive it, because they are about how it sits")
        );
    }

    @Test
    void aProbeThatCrossedARegionBoundaryStopsTheClimbAndNotTheVehicle() {
        // The Folia decision, asserted. The column ahead is refused rather than read illegally, so the
        // vehicle arms no climb for that step — and keeps driving, which is the half that matters: a
        // refused probe happens every time a vehicle crosses a chunk boundary.
        this.host.at(0D, GROUND_Y, 0D, 0F).terrain(GROUND_Y, GROUND_Y + 1.0D).aheadUnknown();
        final VehicleImpl vehicle = primed(terrain());

        this.clock.advanceTicks(1);
        this.host.runOnce();

        assertAll(
                () -> assertTrue(this.host.probeCount() > 0, "the column under it is never refused"),
                () -> assertEquals(GROUND_Y, this.host.y(), EPSILON),
                () -> assertFalse(vehicle.getAttitude().isClimbing())
        );
    }

    // ------------------------------------------------------------------ the rotation push

    @Test
    void theRotationCarriesThePitchTheHeadingAndTheRollTheBackendCannotShow() {
        // All three angles leave this layer. The roll is dropped by the shipped renderer, whose
        // whole-model rotation is a pitch and a yaw — so asserting it here is asserting the one half
        // that is this project's to get right.
        this.host.at(0D, GROUND_Y, 0D, 30F).terrain(GROUND_Y, GROUND_Y + 0.5D);
        primed(terrain());

        this.clock.advanceTicks(1);
        this.host.runOnce();

        assertAll(
                // Half a block of rise over a one-block run is half of the slope ceiling, nose up.
                () -> assertEquals(-12F, this.visual.pitchDegrees(), EPSILON),
                () -> assertEquals(30F, this.visual.yawDegrees(), EPSILON),
                () -> assertEquals(0F, this.visual.rollDegrees(), EPSILON, "it is not turning")
        );
    }

    @Test
    void anAngleThatHasNotMovedByAByteOfTheWireCostsNoPushAtAll() {
        // The emission gate. The first push is unconditional, because until one has happened the
        // renderer is still using its own body rotation and there is nothing to compare against. After
        // that a step on unchanged ground sends nothing: two angles inside one rotation byte are the
        // same rotation to everything downstream.
        this.host.at(0D, GROUND_Y, 0D, 0F).terrain(GROUND_Y, GROUND_Y + 0.5D);
        primed(terrain());
        this.clock.advanceTicks(1);
        this.host.runOnce();
        final int afterFirst = this.visual.rotationCount();

        this.clock.advanceTicks(1);
        this.host.runOnce();
        final int afterSecond = this.visual.rotationCount();

        // Then the ground changes under it, which is a pitch the client can be shown.
        this.host.terrain(GROUND_Y, GROUND_Y - 1.0D);
        this.clock.advanceTicks(1);
        this.host.runOnce();

        assertAll(
                () -> assertEquals(afterFirst, afterSecond, "unchanged ground is not worth a packet"),
                () -> assertEquals(afterSecond + 1, this.visual.rotationCount()),
                () -> assertEquals(24F, this.visual.pitchDegrees(), EPSILON, "nose down off a ledge")
        );
    }

    @Test
    void theRotationIsPushedBeforeTheSeatsAreFollowed() {
        // A whole-model rotation moves the locators a seat hangs off, so a follow computed first would
        // put every rider a step behind the lean.
        this.host.at(0D, GROUND_Y, 0D, 0F).terrain(GROUND_Y, GROUND_Y + 0.5D)
                .holding(RiderIntent.ofBits(RiderIntent.FORWARD_BIT));
        final VehicleImpl vehicle = primed(terrain());
        seat(vehicle);
        this.host.clearJournal();

        // The ground changes and the vehicle is being driven, so this step has both a rotation to push
        // and a carrier far enough from its seat to be worth moving.
        this.host.terrain(GROUND_Y, GROUND_Y - 1.0D);
        this.clock.advanceTicks(1);
        this.host.runOnce();

        final List<String> journal = this.host.journal();
        final int rotated = journal.indexOf("visual.rotate");
        int followed = -1;
        for (int line = 0; line < journal.size(); line++) {
            if (journal.get(line).startsWith("carrier")) {
                followed = line;
                break;
            }
        }
        final int firstFollow = followed;
        assertAll(
                () -> assertTrue(rotated >= 0, () -> "nothing was rotated: " + journal),
                () -> assertTrue(firstFollow >= 0, () -> "nothing followed: " + journal),
                () -> assertTrue(firstFollow > rotated,
                        () -> "the seats have to follow the rotated model: " + journal)
        );
    }

    @Test
    void theRotationIsPushedOnADisplacedStepAndNotOnlyWhileParked() {
        // The defect this suite exists to guard against from now on. A vehicle that is being driven is
        // precisely the vehicle whose lean is worth showing, and the step that writes a displacement is
        // the step that was skipping the push.
        this.host.at(0D, GROUND_Y, 0D, 0F).terrain(GROUND_Y, GROUND_Y + 0.5D)
                .holding(RiderIntent.ofBits(RiderIntent.FORWARD_BIT));
        final VehicleImpl vehicle = primed(terrain());
        seat(vehicle);
        this.visual.clearRotations();
        this.host.clearJournal();

        this.clock.advanceTicks(1);
        this.host.runOnce();

        assertAll(
                () -> assertTrue(this.host.journal().contains("moveLocal"),
                        () -> "the step has to have displaced the vehicle: " + this.host.journal()),
                () -> assertEquals(1, this.visual.rotationCount()),
                () -> assertEquals(-12F, this.visual.pitchDegrees(), EPSILON, "nose up the half-block"),
                () -> assertFalse(vehicle.isRelocating())
        );
    }

    @Test
    void theRotationIsPushedOnTheStepThatStartsARelocationToo() {
        // The owner's rule, and the hardest case for it: with no in-place move every displacement is a
        // relocation, so a vehicle that only pushed while parked pushed essentially never. It is safe
        // because the push reaches no entity — it ends at the renderer's whole-model rotation, which is a
        // value the renderer's own tick reads — so a root that is transiently out of the world is not
        // something this call can touch. See VehicleImpl#pushRotation.
        this.host.localMove(false);
        this.host.at(0D, GROUND_Y, 0D, 0F).terrain(GROUND_Y, GROUND_Y + 0.5D)
                .holding(RiderIntent.ofBits(RiderIntent.FORWARD_BIT));
        final VehicleImpl vehicle = primed(terrain());
        seat(vehicle);
        this.visual.clearRotations();
        this.host.clearJournal();

        this.clock.advanceTicks(1);
        this.host.runOnce();

        assertAll(
                () -> assertTrue(this.host.hasPendingTeleport(), "no in-place move, so it relocated"),
                () -> assertTrue(vehicle.isRelocating()),
                () -> assertEquals(1, this.visual.rotationCount(),
                        "the rotation is maintained even while the vehicle is moving"),
                () -> assertEquals(-12F, this.visual.pitchDegrees(), EPSILON),
                () -> assertEquals(0F, this.visual.yawDegrees(), EPSILON)
        );
    }

    @Test
    void theRidersFollowAcrossARelocationRatherThanBeingLeftWhereTheVehicleWas() {
        // The visible half of the same early return, and the worse half: a seat carrier is not a
        // passenger of the root, so the platform's own teleport does not bring it along. A step that
        // returned before the follow therefore left every rider of a relocating vehicle standing where
        // the vehicle used to be — on a host with no in-place move, for the whole of every journey.
        this.host.localMove(false);
        this.host.at(0D, GROUND_Y, 0D, 0F).terrain(GROUND_Y, GROUND_Y)
                .holding(RiderIntent.ofBits(RiderIntent.FORWARD_BIT));
        final VehicleImpl vehicle = primed(terrain());
        seat(vehicle);
        final double before = this.host.onlyCarrier().z();
        this.host.clearJournal();

        this.clock.advanceTicks(1);
        this.host.runOnce();

        assertAll(
                () -> assertTrue(this.host.hasPendingTeleport()),
                // A quarter of a block of acceleration in one tick, which is where the root was sent.
                () -> assertEquals(0.25D, this.host.z(), EPSILON),
                () -> assertEquals(0D, before, EPSILON, "the carrier was spawned at the root"),
                () -> assertEquals(0.25D, this.host.onlyCarrier().z(), EPSILON,
                        "the rider goes where the vehicle is going, not where it was"),
                () -> assertTrue(this.host.journal().contains("carrier0.moveLocal"),
                        () -> "the carrier has its own move and its own region check: "
                                + this.host.journal())
        );
    }

    // ------------------------------------------------------------------ the inherited response

    @Test
    void aProfileThatDeclaredNoResponseInheritsTheOneItsKindIsGivenByTheServer() {
        // A profile is a statement about a thing and the defaults are a statement about a server, so a
        // plugin that said nothing picks the server's answer up. The identity of TerrainResponse.NONE is
        // what "said nothing" means, which is why the shared constant matters.
        final VehicleBinderImpl binder = binder();
        final Vehicle vehicle = binder.create(VehicleFixtures.rig(), VehicleFixtures.profile(),
                StubEntity.plain());

        assertAll(
                () -> assertEquals(8F, vehicle.getProfile().terrain().bankDegrees(), EPSILON),
                () -> assertEquals(20F, vehicle.getProfile().terrain().slopePitchDegrees(), EPSILON),
                () -> assertSame(VehicleKind.GROUND, vehicle.getProfile().kind())
        );
    }

    @Test
    void aProfileThatSpelledOutItsOwnResponseKeepsIt() {
        // Including one that spelled out an all-zero response of its own: a plugin that went to the
        // trouble of saying "this does not react to terrain" has said something, and the operator's file
        // fills gaps rather than overriding statements.
        final VehicleBinderImpl binder = binder();
        final Vehicle vehicle = binder.create(VehicleFixtures.rig(), terrain(), StubEntity.plain());

        assertEquals(20F, vehicle.getProfile().terrain().bankDegrees(), EPSILON);
    }

    // ------------------------------------------------------------------ helpers

    /** The cart this suite drives: ground, climbing, leaning, snapping to its targets. */
    private static VehicleProfile terrain() {
        return VehicleFixtures.terrainProfile(VehicleKind.GROUND, RESPONSE);
    }

    private VehicleBinderImpl binder() {
        return new VehicleBinderImpl(root -> this.host, (rig, root) -> this.visual,
                VehicleSettingsSource.fixed(VehicleFixtures.settingsWithTerrainDefaults()), this.clock,
                SimulationLogger.DISCARDING, SeatChartSink.NONE, new ModelManagerImpl());
    }

    /**
     * A started vehicle whose clock has been seeded by one step at zero elapsed time.
     *
     * <p>The seeding step is not a workaround: the first step of a vehicle's life genuinely has nothing
     * to difference against, and every test about what a vehicle does <em>with</em> elapsed time has to
     * get past it. The journal is cleared afterwards so an ordering assertion is about one step.
     */
    private VehicleImpl primed(VehicleProfile profile) {
        final VehicleImpl vehicle = new VehicleImpl(VehicleFixtures.rig(), profile, this.host,
                this.visual, VehicleSettingsSource.fixed(VehicleFixtures.settings()), this.clock,
                (message, thrown) -> this.logged.add(thrown), SeatChartSink.NONE,
                stopped -> this.stoppedCount.incrementAndGet());
        assertTrue(vehicle.start(), "the fixture host should allow a start");
        this.host.runOnce();
        this.host.clearJournal();
        this.visual.clearRotations();
        return vehicle;
    }

    /** Puts somebody in the driver's seat, so the follow pass has a carrier to move. */
    private static void seat(VehicleImpl vehicle) {
        assertTrue(vehicle.board(StubPlayer.named("driver")).isAboard(),
                "the fixture rig declares a driver's seat on a locator the fixture model has");
    }
}
