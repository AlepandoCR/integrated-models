package pandastic.studio.models.internal.vehicle;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.binding.RiggedModel;
import pandastic.studio.models.api.rig.Rig;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Three delegating methods, and the one property that makes them worth a class.
 *
 * <p>A rigged model is single use: a handle that has despawned refuses to spawn again, so the vehicle
 * layer must never try. That is only guaranteed if the thing holding the handle owns its lifecycle,
 * which is what this adapter is for — and it is the kind of guarantee that is cheap to assert and
 * expensive to discover the absence of.
 */
class RiggedVehicleVisualTest {

    @Test
    void theLifecycleIsPassedStraightThrough() {
        final RecordingModel model = new RecordingModel();
        final RiggedVehicleVisual visual = new RiggedVehicleVisual(model, SeatLocatorSource.NONE);

        assertAll(
                () -> assertFalse(visual.isAlive()),
                () -> assertTrue(visual.spawn()),
                () -> assertTrue(visual.isAlive()),
                () -> assertSame(model, visual.model())
        );

        visual.despawn();
        assertAll(
                () -> assertFalse(visual.isAlive()),
                () -> assertEquals(1, model.despawnCount)
        );
    }

    @Test
    void aDespawnedModelRefusesToSpawnAgainAndTheVisualCarriesThatRefusal() {
        // The whole reason the adapter exists rather than a pair of method references.
        final RecordingModel model = new RecordingModel();
        final RiggedVehicleVisual visual = new RiggedVehicleVisual(model, SeatLocatorSource.NONE);
        visual.spawn();
        visual.despawn();

        assertFalse(visual.spawn(),
                "a spent handle must not come back alive with transformers pointing at a dead tracker");
    }

    @Test
    void theSeatLocatorsAreAskedForByEntityAndModelId() {
        // Keyed on what is actually rendered rather than on a handle this plugin holds, which is what
        // makes a vehicle whose visual somebody else spawned seatable.
        final Entity root = StubEntity.plain();
        final RecordingModel model = new RecordingModel().on(root);
        final FakeSeatLocators locators = VehicleFixtures.locators();
        final String[] asked = {null, null};
        final RiggedVehicleVisual visual = new RiggedVehicleVisual(model, (entity, modelId) -> {
            asked[0] = entity == root ? "root" : "somebody else";
            asked[1] = modelId;
            return locators;
        });

        assertAll(
                () -> assertSame(locators, visual.locators()),
                () -> assertEquals("root", asked[0]),
                () -> assertEquals(model.getRig().getModelId(), asked[1])
        );
    }

    @Test
    void aModelWithNoEntityHasNoLocatorsRatherThanAFailure() {
        // A legal shape of a rigged model, impossible for a vehicle, and answering NONE keeps the seat
        // chart the one place it is reported.
        final RiggedVehicleVisual visual = new RiggedVehicleVisual(
                new RecordingModel(), (entity, modelId) -> VehicleFixtures.locators());

        assertSame(SeatLocators.NONE, visual.locators());
    }

    /** A rigged model that remembers its own lifecycle, which is all this adapter touches. */
    private static final class RecordingModel implements RiggedModel {

        private final @NotNull Rig rig = VehicleFixtures.rig();
        private final @NotNull UUID id = UUID.randomUUID();

        private @Nullable Entity entity;

        private @NotNull RecordingModel on(@NotNull Entity root) {
            this.entity = root;
            return this;
        }

        @Override
        public @NotNull Optional<Entity> getEntity() {
            return Optional.ofNullable(this.entity);
        }

        private boolean alive;
        private boolean spent;
        private int despawnCount;

        @Override
        public @NotNull Rig getRig() {
            return this.rig;
        }

        @Override
        public @NotNull UUID getUniqueIdentifier() {
            return this.id;
        }

        @Override
        public boolean spawn() {
            if (this.spent || this.alive) {
                return false;
            }
            this.alive = true;
            return true;
        }

        @Override
        public void despawn() {
            if (this.spent) {
                return;
            }
            this.spent = true;
            this.alive = false;
            this.despawnCount++;
        }

        @Override
        public boolean isSpawned() {
            return this.alive;
        }
    }
}
