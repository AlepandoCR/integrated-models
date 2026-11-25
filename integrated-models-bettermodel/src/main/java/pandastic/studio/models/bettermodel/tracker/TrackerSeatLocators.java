package pandastic.studio.models.bettermodel.tracker;

import kr.toxicity.model.api.bone.BoneMovement;
import kr.toxicity.model.api.bone.RenderedBone;
import kr.toxicity.model.api.data.blueprint.BlueprintElement;
import kr.toxicity.model.api.tracker.EntityTracker;
import kr.toxicity.model.api.tracker.EntityTrackerRegistry;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector3f;
import pandastic.studio.models.internal.vehicle.SeatLocators;
import pandastic.studio.models.internal.vehicle.SeatSample;

import java.util.Objects;

/**
 * BetterModel's answer to "where are this model's locators".
 *
 * <h2>How a locator is told from a bone, and why there is only one admissible test</h2>
 * A Blockbench locator reaches BetterModel as a {@code BlueprintElement.Locator}, which implements
 * {@code BlueprintElement.Bone} — so it <em>does</em> appear in the rendered bone tree, alongside the
 * groups that carry cubes. The two are therefore indistinguishable by name, by position, and by anything
 * else a caller could invent. What distinguishes them is which blueprint element the rendered bone was
 * derived from, and since 3.6.0 the renderer exposes exactly that:
 * {@code RenderedBone#isDerivedFrom(BlueprintElement.Locator.class)}. That is the test, and the only one.
 *
 * <p>The tempting substitutes are all wrong in ways that are quiet. "It has no display" is true of a
 * locator and also of a dummy bone and of a group whose cubes a resource pack dropped. "Its name looks
 * like a locator's" is a convention nobody enforces. "It is the bone called {@code mount}" is the exact
 * mistake this plugin's seat policy exists to catch — four models in this repository carry such a bone
 * and none of them has a seat.
 *
 * <h2>Why the position is an offset rather than a world coordinate</h2>
 * {@code RenderedBone#worldPosition()} answers in world axes, with the model's rotation applied, measured
 * from the model's own origin — it is what the renderer itself adds to {@code tracker.location()} when it
 * places a shadow or a hitbox. This class hands that offset straight across the seam instead of adding
 * the origin, because the tracker caches its location and refreshes it on its own schedule: adding it
 * here would make every seat up to a tick stale, and the vehicle layer already has the root entity's
 * position freshly read in the step that is about to use it.
 *
 * <h2>Unvalidated against a running server</h2>
 * No model in this repository contains a locator element yet, so neither the resolution nor the position
 * has been seen on a real one. Both follow the renderer's own code paths, and the failure mode if the
 * second is wrong is a rider sitting at a fixed offset from where they should be — visible immediately
 * and fixable without touching this class.
 */
public final class TrackerSeatLocators implements SeatLocators {

    private final @NotNull EntityTracker tracker;

    /**
     * Reused by every read of every locator of this model.
     *
     * <p>The renderer takes the cache as an argument precisely so that a per-tick caller does not
     * allocate one, and a vehicle reads one of these per occupied seat per step. Confined to the thread
     * that owns the entity, which is the only thread allowed to read the model at all.
     */
    private final @NotNull BoneMovement positionCache = new BoneMovement();

    private TrackerSeatLocators(@NotNull EntityTracker tracker) {
        this.tracker = tracker;
    }

    /**
     * The locators of {@code modelId} on {@code entity}, or {@link SeatLocators#NONE}.
     *
     * <p>Asked of the renderer's own registry rather than of a handle this plugin holds, so the answer is
     * about the model that is actually standing there. Keyed by model name as well as by entity, so a
     * vehicle never resolves a seat against some other plugin's model on the same entity.
     *
     * @param entity  the entity the model stands on; owned by the calling thread
     * @param modelId the model the rig names
     */
    public static @NotNull SeatLocators of(@NotNull Entity entity, @NotNull String modelId) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(modelId, "modelId");
        final EntityTrackerRegistry registry = EntityTrackerRegistry.registry(entity.getUniqueId());
        if (registry == null) {
            return SeatLocators.NONE;
        }
        final EntityTracker tracker = registry.tracker(modelId);
        if (tracker == null || tracker.isClosed()) {
            return SeatLocators.NONE;
        }
        return new TrackerSeatLocators(tracker);
    }

    @Override
    public @NotNull Resolution resolve(@NotNull String locatorName) {
        Objects.requireNonNull(locatorName, "locatorName");
        // The renderer's own name lookup, which compares the bare name case-insensitively — the same
        // comparison a rig's bones go through, and the reason a rig stores its seat's locator name
        // normalised rather than raw.
        final RenderedBone bone = this.tracker.bone(locatorName);
        if (bone == null) {
            return Resolution.UNRESOLVED;
        }
        if (!bone.isDerivedFrom(BlueprintElement.Locator.class)) {
            // The rigger pointed at a bone. Reported, never seated on; see the class note for why every
            // other way of asking this question is quietly wrong.
            return Resolution.BONE;
        }
        return Resolution.locator(sample -> readInto(bone, sample));
    }

    /**
     * Fills {@code sample} with one locator's offset from the model's origin.
     *
     * <p>Answers {@code false} once the tracker is closed, which is how the seating layer learns that the
     * model behind a seat is gone and puts its rider down rather than following a position that no longer
     * means anything.
     */
    private boolean readInto(@NotNull RenderedBone bone, @NotNull SeatSample sample) {
        if (this.tracker.isClosed()) {
            return false;
        }
        final Vector3f position = bone.worldPosition(this.positionCache);
        sample.set(position.x, position.y, position.z);
        return true;
    }

    @Override
    public @NotNull String toString() {
        return "TrackerSeatLocators[" + this.tracker.name() + "]";
    }
}
