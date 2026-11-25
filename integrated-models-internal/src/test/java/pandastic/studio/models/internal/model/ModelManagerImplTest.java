package pandastic.studio.models.internal.model;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Pig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.binding.RiggedModel;
import pandastic.studio.models.api.model.Model;
import pandastic.studio.models.api.rig.Rig;

import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the one registry holds, what it hands back, and what it refuses to hand back.
 *
 * <p>Every assertion here is about a promise {@code ModelManager} makes that the previous registry could
 * not: it was never written to, so none of this was observable.
 */
class ModelManagerImplTest {

    private final ModelManagerImpl manager = new ModelManagerImpl();

    @Test
    void aRegisteredModelIsFoundByIdentityAndByValue() {
        final FakeModel model = new FakeModel(null);

        this.manager.addModel(model);

        assertAll(
                () -> assertEquals(1, this.manager.size()),
                () -> assertSame(model, this.manager.getModel(model.getUniqueIdentifier()).orElseThrow()),
                () -> assertEquals(List.of(model), List.copyOf(this.manager.getModels())));
    }

    @Test
    void anUnknownIdentityIsEmptyRatherThanNull() {
        assertTrue(this.manager.getModel(UUID.randomUUID()).isEmpty());
    }

    @Test
    void removingForgetsTheModel() {
        final FakeModel model = new FakeModel(null);
        this.manager.addModel(model);

        this.manager.removeModel(model);

        assertAll(
                () -> assertEquals(0, this.manager.size()),
                () -> assertTrue(this.manager.getModels().isEmpty()));
    }

    @Test
    void removingAModelThatWasNeverRegisteredIsIgnored() {
        this.manager.removeModel(new FakeModel(null));

        assertEquals(0, this.manager.size());
    }

    @Test
    void aModelCannotEvictItsSuccessorUnderTheSameIdentity() {
        // addModel replaces by key, so two models can share an id. The one that was replaced must not be
        // able to remove the one that replaced it — otherwise a teardown callback arriving late empties
        // an entry belonging to something that is still in the world.
        final FakeModel first = new FakeModel(null);
        final FakeModel second = new FakeModel(null, first.getUniqueIdentifier());
        this.manager.addModel(first);
        this.manager.addModel(second);

        this.manager.removeModel(first);

        assertSame(second, this.manager.getModel(second.getUniqueIdentifier()).orElseThrow());
    }

    @Test
    void modelsAreAskedForByKind() {
        final FakeModel plain = new FakeModel(null);
        final FakeRiggedModel rigged = new FakeRiggedModel();
        this.manager.addModel(plain);
        this.manager.addModel(rigged);

        assertAll(
                () -> assertEquals(List.of(rigged),
                        List.copyOf(this.manager.getModelsByType(RiggedModel.class))),
                () -> assertEquals(2, this.manager.getModelsByType(Model.class).size()));
    }

    @Test
    void modelsAreAskedForByTheirEntitysType() {
        final Entity pig = entity(Pig.class);
        final FakeModel onAPig = new FakeModel(pig);
        final FakeModel onNothing = new FakeModel(null);
        this.manager.addModel(onAPig);
        this.manager.addModel(onNothing);

        assertAll(
                () -> assertEquals(List.of(onAPig),
                        List.copyOf(this.manager.getModelsByEntityType(Pig.class))),
                () -> assertEquals(List.of(onAPig),
                        List.copyOf(this.manager.getModelsByEntityType(Entity.class))),
                // A model attached to nothing matches no entity filter, which is the whole reason the
                // filter is on the entity rather than on a declared type parameter.
                () -> assertFalse(this.manager.getModelsByEntityType(Entity.class).contains(onNothing)));
    }

    @Test
    void everyModelOnOneEntityIsFound() {
        // Two models on one entity is a supported shape — a cloak and a tail — which is why a model's
        // identity is its own rather than its entity's.
        final Entity shared = entity(Pig.class);
        final FakeModel cloak = new FakeModel(shared);
        final FakeModel tail = new FakeModel(shared);
        this.manager.addModel(cloak);
        this.manager.addModel(tail);
        this.manager.addModel(new FakeModel(entity(Pig.class)));

        final Collection<Model> found = this.manager.getModelsByEntity(shared.getUniqueId());

        assertAll(
                () -> assertEquals(2, found.size()),
                () -> assertTrue(found.contains(cloak)),
                () -> assertTrue(found.contains(tail)));
    }

    @Test
    void everyCollectionIsASnapshotRatherThanTheLiveMap() {
        final FakeModel model = new FakeModel(null);
        this.manager.addModel(model);

        final Collection<Model> models = this.manager.getModels();
        this.manager.removeModel(model);

        assertAll(
                // Taken before the removal, so it still names what was there — a live view would have
                // changed underneath a caller iterating it while a region thread tore a model down.
                () -> assertEquals(1, models.size()),
                () -> assertEquals(0, this.manager.size()),
                () -> assertThrows(UnsupportedOperationException.class, () -> models.clear()));
    }

    /** An entity reference whose only interesting properties are its type and its id. */
    private static @NotNull Entity entity(@NotNull Class<? extends Entity> type) {
        final UUID id = UUID.randomUUID();
        return (Entity) Proxy.newProxyInstance(
                ModelManagerImplTest.class.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    // Proxy routes these to the handler, so reference semantics have to be restated.
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "FakeEntity[" + type.getSimpleName() + "]";
                    default -> null;
                });
    }

    /** The four methods a {@link Model} cannot default, and nothing else. */
    private static class FakeModel implements Model {

        private final @NotNull UUID id;
        private final @Nullable Entity entity;
        private final @NotNull Rig rig = Rig.modelOnly("test-model");

        FakeModel(@Nullable Entity entity) {
            this(entity, UUID.randomUUID());
        }

        FakeModel(@Nullable Entity entity, @NotNull UUID id) {
            this.entity = entity;
            this.id = id;
        }

        @Override
        public @NotNull UUID getUniqueIdentifier() {
            return this.id;
        }

        @Override
        public @NotNull Rig getRig() {
            return this.rig;
        }

        @Override
        public @NotNull Optional<Entity> getEntity() {
            return Optional.ofNullable(this.entity);
        }

        @Override
        public boolean isSpawned() {
            return true;
        }

        @Override
        public void despawn() {
        }
    }

    /** A second kind, so that asking by kind has something to discriminate. */
    private static final class FakeRiggedModel extends FakeModel implements RiggedModel {

        FakeRiggedModel() {
            super(null);
        }

        @Override
        public boolean spawn() {
            return false;
        }
    }
}
