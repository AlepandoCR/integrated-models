package pandastic.studio.models.internal.vehicle;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.binding.RiggedModel;
import pandastic.studio.models.internal.view.CullableModel;

import java.util.Objects;
import java.util.Optional;

/**
 * A {@link VehicleVisual} backed by a rigged model bound to the vehicle's root entity.
 *
 * <p>Four delegating methods, and the reason it is a class rather than a method reference is that the
 * handle is single use: a rigged model that has despawned refuses to spawn again, so the vehicle layer
 * must never try — and the only way to be sure of that is for the thing holding the handle to be the
 * thing that owns its lifecycle.
 *
 * <p>Note what it does <em>not</em> do: drive anything. The model follows the root entity through the
 * binding layer's own per-tick sampler, which is the single writer into the pose pipeline; see
 * {@link VehicleVisual} for why that is deliberate.
 *
 * <h2>The seats come from here because the model does</h2>
 * {@link #locators()} is the one method that is not a delegation. A seat is a locator of the model this
 * visual spawned, so the place that knows which model is standing on which entity is the place that can
 * ask the renderer about it — which it does through {@link SeatLocatorSource}, keyed on the entity and
 * the model id rather than on a handle, so that the answer is about what is actually rendered.
 */
public final class RiggedVehicleVisual implements VehicleVisual {

    private final @NotNull RiggedModel model;
    private final @NotNull SeatLocatorSource locators;

    /**
     * @param model the rigged model bound to this vehicle's root entity, not yet spawned
     * @param locators where this vehicle's seat locators are read from, usually
     *                 {@link SeatLocatorSource#renderer()}
     */
    public RiggedVehicleVisual(
            @NotNull RiggedModel model,
            @NotNull SeatLocatorSource locators
    ) {
        this.model = Objects.requireNonNull(model, "model");
        this.locators = Objects.requireNonNull(locators, "locators");
    }

    @Override
    public boolean spawn() {
        return this.model.spawn();
    }

    @Override
    public void despawn() {
        this.model.despawn();
    }

    @Override
    public boolean isAlive() {
        return this.model.isAlive();
    }

    /**
     * {@inheritDoc}
     *
     * <h2>The roll is carried to here and dropped, and that is a backend limit rather than a decision</h2>
     * {@link pandastic.studio.models.api.model.Model#setRotation(float, float)} is the whole-model
     * rotation this project's rendering seam exposes, and it takes a pitch and a yaw. It takes two
     * because the shipped renderer's own whole-model rotation is two: a pitch and a yaw, packed as the
     * two rotation bytes an entity's orientation travels in. There is no third component to put a roll
     * in.
     *
     * <p>The two places a roll could otherwise go were both considered and both are worse. Writing it as
     * a rotation on the rig's root bone would express it correctly — a root bone's rotation <em>is</em> a
     * whole-model roll, and it breaks no rig invariant because it moves no bone off its pivot — but it
     * would make the vehicle's control step a second writer into the pose the binding layer's sampler
     * owns, which is the one thing {@link VehicleVisual}'s own note refuses outright. Adding a third
     * angle to the rendering seam would push a component into the published api that the only shipped
     * backend must then silently discard, which is a worse lie than discarding it here where the reason
     * is written down.
     *
     * <p>So a banking vehicle pitches and turns and renders upright on this backend. The angle is still
     * computed, still blended, still bounded and still tested, and a backend whose whole-model rotation
     * has three components needs no change above this line.
     */
    @Override
    public void setBodyRotation(float pitchDegrees, float yawDegrees, float rollDegrees) {
        this.model.setRotation(pitchDegrees, yawDegrees);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Answered from the model's own viewport, which is the culler's published state and the only
     * authority on who holds a model. The handle is the published {@link RiggedModel} interface and the
     * viewport is an internal concern, so the capability is asked for rather than assumed: a model that
     * is not a {@link CullableModel} is not tracked by the culler, which means nothing is taking viewers
     * away from it and the honest answer is that it is seen.
     *
     * <p>A model that is cullable but <em>not</em> culled — one no viewer has reached yet, or one the
     * culler has suspended — reports no viewers, and that is the answer this is for: it is the same state
     * the culler uses to take the model's simulation off the loop, so a vehicle reading no blocks for it
     * is consistent with the model not being posed either.
     */
    @Override
    public boolean hasViewers() {
        return !(this.model instanceof CullableModel cullable)
                || cullable.viewport().shownCount() > 0;
    }

    @Override
    public @NotNull SeatLocators locators() {
        // Empty when the model is bound to something that is not an entity, which cannot happen for a
        // vehicle but is a legal shape of a rigged model. Answering NONE rather than throwing keeps the
        // seat chart the place that reports it.
        final Optional<Entity> entity = this.model.getEntity();
        if (entity.isEmpty()) {
            return SeatLocators.NONE;
        }
        return this.locators.locatorsOf(entity.get(), this.model.getRig().getModelId());
    }

    /** The model behind this visual, so a caller can reach the rig it drives. */
    public @NotNull RiggedModel model() {
        return this.model;
    }

    @Override
    public @NotNull String toString() {
        return "RiggedVehicleVisual[" + this.model + "]";
    }
}
