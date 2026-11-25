package pandastic.studio.models.api.model;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.model.component.ModelComponent;
import pandastic.studio.models.api.model.component.decoration.ModelDecorator;
import pandastic.studio.models.api.util.schedule.TickerController;

import java.util.Collection;
import java.util.Optional;

/**
 * A {@link Model} bound to a Bukkit entity and assembled out of components.
 *
 * <p>This is the component API, and it is the same interface it always was — it was called {@code Model}
 * until the four kinds of model were given a common root, and it kept every member it had. What changed is
 * that it is now <em>a</em> {@link Model} rather than <em>the</em> model: a consumer holding a mixed
 * collection of this plugin's models can shut this one down, show it to a player and ask it what rig it
 * drives through the same methods it uses on a decoration or a vehicle.
 *
 * <h2>What it adds over {@link Model}</h2>
 * <ul>
 *   <li><strong>An entity type.</strong> It is generic in it, and {@link #spawn(Location)} creates one —
 *       which is why this is the one kind whose {@link #despawn()} also removes the entity.</li>
 *   <li><strong>Components.</strong> A capability on one of these is a component in the list rather than a
 *       method here; see {@link ModelComponent}.</li>
 *   <li><strong>Its own ticking.</strong> It is a {@link TickerController} over
 *       {@link ScheduledTask}, and the task is one repeating task on the base entity's own
 *       {@code EntityScheduler} — which is what makes it Folia-safe, since that task runs only while the
 *       entity exists and always on the region thread that owns it. Nothing about a model may be driven
 *       from any other dispatcher.</li>
 * </ul>
 *
 * <h2>The appearance surface is the decorator's</h2>
 * {@link Model}'s appearance setters are implemented here by routing each one to the matching
 * {@link ModelDecorator} operation, so {@code model.setGlint(true)} and
 * {@code model.getDecorator().setEnchantmentGlint(true)} are the same call. The four that the decorator
 * does not express — clearing a tint, whole-model visibility and the two per-bone operations — keep
 * {@link Model}'s no-op default, because the component path has no way to say them and silently doing
 * nothing is what every other kind that cannot express an operation does.
 */
public interface EntityModel<BaseEntityType extends Entity>
        extends Model, TickerController<ScheduledTask> {

    /**
     * Spawns a fresh entity of {@link #getBaseEntityType()} at {@code location} and binds this model to
     * it.
     *
     * <p>Must run on the thread that owns {@code location}'s region.
     *
     * @param location where to spawn
     */
    void spawn(@NotNull Location location);

    /**
     * Binds this model to an entity that already exists, despawning any current binding first.
     *
     * <p>Must run on the thread that owns {@code entity}.
     *
     * @param entity what to attach to
     */
    void apply(@NotNull BaseEntityType entity);

    /**
     * {@inheritDoc}
     *
     * <p>On this kind the entity goes too: an {@link EntityModel} spawns its own base entity, so it owns
     * its lifetime. That is the one place this kind's teardown differs from every other kind's, where the
     * entity belongs to the caller.
     */
    @Override
    void despawn();

    /**
     * This model's visual state, as a component.
     *
     * <p>Every model has exactly one, built with the model itself. {@link Model}'s own appearance setters
     * are routed here, so neither is the "real" one.
     *
     * @return the decorator
     */
    @NotNull ModelDecorator<BaseEntityType> getDecorator();

    /**
     * The base entity, empty while nothing is bound.
     *
     * @return the entity this model is attached to, at its own type
     */
    @NotNull Optional<BaseEntityType> getBaseEntity();

    /** The entity class this model spawns and accepts. */
    @NotNull Class<BaseEntityType> getBaseEntityType();

    /**
     * {@inheritDoc}
     *
     * <p>{@link #getBaseEntity()} widened, so a caller that does not care about the entity type can read
     * it off {@link Model} and one that does can keep its own.
     */
    @Override
    default @NotNull Optional<Entity> getEntity() {
        return getBaseEntity().map(entity -> entity);
    }

    /** Every component attached to this model, in the order they were added. */
    @NotNull Collection<ModelComponent<BaseEntityType>> getComponents();

    /**
     * Every attached component that is an instance of {@code componentType}.
     *
     * @param componentType the component interface or class to look for
     * @param <ComponentType> the component type
     * @return the matching components, as a snapshot
     */
    <ComponentType extends ModelComponent<BaseEntityType>> @NotNull Collection<ComponentType>
            getComponentsByType(@NotNull Class<ComponentType> componentType);

    /**
     * Attaches a component.
     *
     * @param component the component, already built around this model
     */
    void addComponent(@NotNull ModelComponent<BaseEntityType> component);

    @Override
    default void setScale(float scale) {
        getDecorator().setScale(scale);
    }

    @Override
    default void setRotation(float pitchDegrees, float yawDegrees) {
        getDecorator().setRotation(pitchDegrees, yawDegrees);
    }

    @Override
    default void setTint(int rgb) {
        getDecorator().setTint(rgb);
    }

    @Override
    default void setGlow(boolean glow) {
        getDecorator().setGlowing(glow);
    }

    @Override
    default void setGlowColor(int rgb) {
        getDecorator().setGlowColor(rgb);
    }

    @Override
    default void setGlint(boolean glint) {
        getDecorator().setEnchantmentGlint(glint);
    }

    @Override
    default void setBrightness(int blockLight, int skyLight) {
        getDecorator().setBrightness(blockLight, skyLight);
    }

    @Override
    default void setViewRange(float blocks) {
        getDecorator().setViewRange(blocks);
    }
}
