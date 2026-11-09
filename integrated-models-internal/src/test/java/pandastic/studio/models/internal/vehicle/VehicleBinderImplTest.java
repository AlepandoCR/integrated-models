package pandastic.studio.models.internal.vehicle;

import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Boat;
import org.bukkit.entity.ChestBoat;
import org.bukkit.entity.Camel;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Llama;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Strider;
import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.vehicle.ControlScheme;
import pandastic.studio.models.api.vehicle.VehicleProfile;
import pandastic.studio.models.internal.sim.SimulationLogger;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the binder refuses, in which order, and what it remembers so that shutdown can reach it.
 *
 * <p>The registry is not bookkeeping for its own sake: an entity-scheduled repeating task is never
 * cancelled for a plugin on disable, so a control loop whose handle was dropped keeps writing positions
 * after the plugin's classloader has been closed. That is the failure this class exists to make
 * impossible, and {@link #despawnAllStopsEveryVehicle()} is the assertion that holds it to it.
 */
class VehicleBinderImplTest {

    private final FakeVehicleHost host = new FakeVehicleHost();
    private final FakeVehicleVisual visual = new FakeVehicleVisual(this.host.sharedJournal());
    private final VehicleFixtures.SteppableClock clock = new VehicleFixtures.SteppableClock();

    private VehicleBinderImpl binder() {
        return new VehicleBinderImpl(root -> this.host, (rig, root) -> this.visual,
                VehicleSettingsSource.fixed(VehicleFixtures.settings()), this.clock,
                SimulationLogger.DISCARDING);
    }

    // ------------------------------------------------------------------ the authority refusal

    @Test
    void everyRootTheServerWouldHandToTheClientIsRefusedByType() {
        // Over-inclusion costs a refusal for an entity that would have worked; under-inclusion costs
        // rubber-banding that looks like a network fault. So each of these is asserted individually
        // rather than through one representative, because the list is the kind of thing that loses an
        // entry in a refactor.
        final List<Class<? extends Entity>> steered = List.of(
                AbstractHorse.class, Camel.class, Llama.class, Boat.class, ChestBoat.class,
                Pig.class, Strider.class, HappyGhast.class);

        assertAll(steered.stream().map(type -> () -> assertTrue(
                ClientSteeredRoots.isKnownClientSteered(StubEntity.of(type)),
                () -> type.getSimpleName() + " is client-steered and must not be a vehicle root")));
    }

    @Test
    void aPlainEntityIsAcceptableAsARoot() {
        assertFalse(ClientSteeredRoots.isKnownClientSteered(StubEntity.plain()));
    }

    @Test
    void creatingOnAClientSteeredRootIsRefusedWithAnExplanation() {
        final VehicleBinderImpl binder = binder();

        final IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> binder.create(VehicleFixtures.rig(), VehicleFixtures.profile(),
                        StubEntity.of(Pig.class)));

        assertAll(
                () -> assertTrue(thrown.getMessage().contains("controlling passenger")),
                () -> assertEquals(0, binder.size()),
                () -> assertEquals(List.of(), this.host.journal(),
                        "the refusal must come before anything is spawned")
        );
    }

    // ------------------------------------------------------------------ the other refusals

    @Test
    void aProfileOutsideTheOperatorsBoundsIsRefusedFirst() {
        // Pure, so it costs nothing to do first, and it names the component. Asserted to happen before
        // the thread check by refusing both at once and expecting the profile's complaint.
        this.host.owned(false);
        final VehicleProfile tooFast = new VehicleProfile(
                50F, 0F, 0F, 0.5F, 0.5F, 0F, 1F, 1F, ControlScheme.TURNING);

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> binder().create(VehicleFixtures.rig(), tooFast, StubEntity.plain()))
                .getMessage().contains("maxSpeedBlocksPerTick"));
    }

    @Test
    void creatingFromAForeignThreadIsRefusedBeforeAnythingIsSpawned() {
        this.host.owned(false);
        final VehicleBinderImpl binder = binder();

        assertAll(
                () -> assertTrue(assertThrows(IllegalStateException.class,
                        () -> binder.create(VehicleFixtures.rig(), VehicleFixtures.profile(),
                                StubEntity.plain())).getMessage().contains("thread")),
                () -> assertEquals(List.of(), this.host.journal()),
                () -> assertEquals(0, binder.size())
        );
    }

    @Test
    void aRigThatCannotBeBoundFailsBeforeAnythingIsScheduled() {
        final VehicleBinderImpl binder = new VehicleBinderImpl(root -> this.host,
                (rig, root) -> {
                    throw new IllegalStateException("bones 'wheel_left', 'wheel_right' not found");
                },
                VehicleSettingsSource.fixed(VehicleFixtures.settings()), this.clock,
                SimulationLogger.DISCARDING);

        assertAll(
                () -> assertTrue(assertThrows(IllegalStateException.class,
                        () -> binder.create(VehicleFixtures.rig(), VehicleFixtures.profile(),
                                StubEntity.plain())).getMessage().contains("wheel_right"),
                        "the message has to list every unresolved name, not the first"),
                () -> assertFalse(this.host.isTaskAlive()),
                () -> assertEquals(0, binder.size())
        );
    }

    // ------------------------------------------------------------------ the registry

    @Test
    void creatingRegistersARunningVehicle() {
        final VehicleBinderImpl binder = binder();

        final VehicleImpl vehicle =
                binder.create(VehicleFixtures.rig(), VehicleFixtures.profile(), StubEntity.plain());

        assertAll(
                () -> assertTrue(vehicle.isAlive()),
                () -> assertEquals(1, binder.size()),
                () -> assertTrue(binder.vehicles().contains(vehicle))
        );
    }

    @Test
    void aVehicleRemovesItselfWhenItStopsFromAnyDirection() {
        final VehicleBinderImpl binder = binder();
        final VehicleImpl vehicle =
                binder.create(VehicleFixtures.rig(), VehicleFixtures.profile(), StubEntity.plain());

        vehicle.despawn();

        assertEquals(0, binder.size(),
                "membership is exactly 'created and not yet stopped', however the stop arrived");
    }

    @Test
    void aRootThatVanishedDuringTheDispatchComesBackAlreadyStopped() {
        // A race a caller cannot avoid and cannot act on, which is why it is a return value rather than
        // an exception — and why the handle has to have taken itself out of the registry.
        this.host.refuseSchedule(true);
        final VehicleBinderImpl binder = binder();

        final VehicleImpl vehicle =
                binder.create(VehicleFixtures.rig(), VehicleFixtures.profile(), StubEntity.plain());

        assertAll(
                () -> assertFalse(vehicle.isAlive()),
                () -> assertTrue(vehicle.isStopped()),
                () -> assertEquals(0, binder.size())
        );
    }

    @Test
    void despawnAllStopsEveryVehicle() {
        final VehicleBinderImpl binder = binder();
        binder.create(VehicleFixtures.rig(), VehicleFixtures.profile(), StubEntity.plain());

        assertAll(
                () -> assertEquals(1, binder.despawnAll()),
                () -> assertEquals(0, binder.size()),
                () -> assertFalse(this.host.isTaskAlive(),
                        "a control loop nobody cancelled outlives the plugin's own classloader")
        );
    }

    @Test
    void reloadRedispatchesOnlyTheLoopsWhoseConfiguredPeriodMoved() {
        final long[] period = {3L};
        final VehicleBinderImpl binder = new VehicleBinderImpl(root -> this.host,
                (rig, root) -> this.visual, () -> VehicleFixtures.settingsWithPeriod(period[0]),
                this.clock, SimulationLogger.DISCARDING);
        binder.create(VehicleFixtures.rig(), VehicleFixtures.profile(), StubEntity.plain());

        assertEquals(0, binder.reload(), "nothing moved");

        period[0] = 9L;
        assertAll(
                () -> assertEquals(1, binder.reload()),
                () -> assertEquals(9L, this.host.period())
        );
    }
}
