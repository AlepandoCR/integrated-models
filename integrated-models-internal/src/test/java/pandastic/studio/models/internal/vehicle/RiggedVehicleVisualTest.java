package pandastic.studio.models.internal.vehicle;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.binding.RiggedModel;
import pandastic.studio.models.api.rig.Rig;

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
        final RiggedVehicleVisual visual = new RiggedVehicleVisual(model);

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
        final RiggedVehicleVisual visual = new RiggedVehicleVisual(model);
        visual.spawn();
        visual.despawn();

        assertFalse(visual.spawn(),
                "a spent handle must not come back alive with transformers pointing at a dead tracker");
    }

    /** A rigged model that remembers its own lifecycle, which is all this adapter touches. */
    private static final class RecordingModel implements RiggedModel {

        private final @NotNull Rig rig = VehicleFixtures.rig();
        private final @NotNull UUID id = UUID.randomUUID();

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
        public boolean isAlive() {
            return this.alive;
        }
    }
}
