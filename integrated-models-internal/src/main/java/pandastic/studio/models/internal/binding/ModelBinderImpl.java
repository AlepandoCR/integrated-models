package pandastic.studio.models.internal.binding;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.binding.ModelBinder;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.sim.SimulationLoop;
import pandastic.studio.models.internal.sim.SimulationSettingsSource;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds rigged models, and keeps a list of the ones that are still bound.
 *
 * <p>Two responsibilities that sound separable and are not. Something has to be able to tear every live
 * model down at shutdown, because an entity-scheduled repeating task is <strong>never</strong> cancelled
 * for a plugin on disable — unlike a main-scheduler task, which the platform sweeps. A sampler nobody
 * cancelled keeps running after the plugin's classloader is closed and fails on the first lazy class
 * load after that, during shutdown, pointing nowhere near the model that leaked it. The only place that
 * can hold that list without inventing a second registry is the thing that handed the models out.
 *
 * <p>Membership is exactly "bound and not yet stopped": a model removes itself from the set as the last
 * step of its teardown, whether that teardown came from here, from a caller, or from the platform
 * noticing the entity is gone. Which is also why a handle is single use — a model that could spawn again
 * after stopping would have left the set and have no way back into it.
 *
 * <h2>What it validates, and when</h2>
 * {@link #bind} runs the offline check and nothing else. It touches no entity, creates no tracker and
 * schedules nothing, so it is safe from any thread and cheap enough to call speculatively. Everything
 * with a thread rule attached to it happens in
 * {@link pandastic.studio.models.api.binding.RiggedModel#spawn()}.
 *
 * <h2>No platform object in here</h2>
 * The entity in {@link #bind(Rig, Entity)} is handed straight to an {@link AnchorFactory} and never
 * touched. Everything with a scheduler or a world behind it lives on the far side of that seam, which is
 * what makes this class — the one that owns the shutdown path — something a test can construct.
 */
public final class ModelBinderImpl implements ModelBinder {

    private final @NotNull AnchorFactory anchors;
    private final @NotNull SimulationLoop loop;
    private final @NotNull SimulationSettingsSource simulationSettings;
    private final @NotNull BindingSettingsSource bindingSettings;
    private final @NotNull RigBindValidator validator;
    private final @NotNull LocomotionStageSource locomotion;

    /**
     * Every model handed out and not yet torn down.
     *
     * <p>A concurrent set because models are bound from whichever thread owns their anchor and remove
     * themselves from whichever thread noticed they were finished — frequently a region thread for one
     * and the main thread for the other, at the same moment.
     */
    private final @NotNull Set<RiggedModelImpl> bound = ConcurrentHashMap.newKeySet();

    /**
     * @param anchors            how an entity becomes something this layer may read
     * @param loop               the single off-tick task every bound model's simulation is stepped from
     * @param simulationSettings where the solver, the cadence and the teleport thresholds come from
     * @param bindingSettings    where the sampler's period comes from
     * @param validator          the offline check {@link #bind} runs; see {@link RigBindValidator}
     * @param locomotion         the walk cycle for a rig, or {@link LocomotionStageSource#NONE}
     */
    public ModelBinderImpl(
            @NotNull AnchorFactory anchors,
            @NotNull SimulationLoop loop,
            @NotNull SimulationSettingsSource simulationSettings,
            @NotNull BindingSettingsSource bindingSettings,
            @NotNull RigBindValidator validator,
            @NotNull LocomotionStageSource locomotion
    ) {
        this.anchors = Objects.requireNonNull(anchors, "anchors");
        this.loop = Objects.requireNonNull(loop, "loop");
        this.simulationSettings = Objects.requireNonNull(simulationSettings, "simulationSettings");
        this.bindingSettings = Objects.requireNonNull(bindingSettings, "bindingSettings");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.locomotion = Objects.requireNonNull(locomotion, "locomotion");
    }

    @Override
    public @NotNull RiggedModelImpl bind(@NotNull Rig rig, @NotNull Entity entity) {
        Objects.requireNonNull(entity, "entity");
        return bind(rig, this.anchors.anchorFor(entity));
    }

    /**
     * Binds {@code rig} to an arbitrary anchor.
     *
     * <p>The form the entity overload is written in terms of, and the form a model anchored to something
     * other than an entity will use. Not part of the published API, because a {@link ModelAnchor} is
     * this plugin's own contract about which thread may read what.
     *
     * @throws IllegalStateException if the rig cannot bind to its model, naming every bone that failed
     */
    public @NotNull RiggedModelImpl bind(@NotNull Rig rig, @NotNull ModelAnchor anchor) {
        Objects.requireNonNull(rig, "rig");
        Objects.requireNonNull(anchor, "anchor");

        // Before anything exists. A rig whose bone names no longer match its model is a content error,
        // and the person who can fix it is reading the log — so it has to fail here, with every broken
        // name in one message, rather than as a model that spawns and then does not move.
        this.validator.requireBindable(rig);

        final RiggedModelImpl model = new RiggedModelImpl(
                rig, anchor, this.loop, this.simulationSettings, this.bindingSettings, this.locomotion,
                this.bound::remove);
        this.bound.add(model);
        return model;
    }

    /** Every model currently bound, as a snapshot. */
    public @NotNull Collection<RiggedModelImpl> boundModels() {
        return List.copyOf(this.bound);
    }

    /** How many models are bound. */
    public int size() {
        return this.bound.size();
    }

    /**
     * Despawns every bound model, and must be called on plugin disable.
     *
     * <p>Iterates a snapshot rather than the live set: each model removes itself as the last step of its
     * own teardown, and mutating the set under its own iterator is the kind of thing that works until
     * the day it does not.
     *
     * <p>One model failing to tear down must not cost the rest theirs, so each is isolated. There is
     * nowhere useful to report it to either: this runs during disable, where the plugin's own logger may
     * already be shutting down, and the thing that matters is that every remaining sampler gets
     * cancelled.
     *
     * @return how many models were torn down
     */
    public int despawnAll() {
        int despawned = 0;
        for (final RiggedModelImpl model : List.copyOf(this.bound)) {
            try {
                model.despawn();
            } catch (final RuntimeException | Error ignored) {
                // Deliberately swallowed; see above.
            }
            despawned++;
        }
        this.bound.clear();
        return despawned;
    }

    /**
     * Re-dispatches every bound model's sampler whose configured period has moved.
     *
     * <p>The binding layer's half of a configuration reload, and explicit for the same reason
     * {@link SimulationLoop#reload()} is: a repeating task's rate belongs to the platform, so changing
     * it means cancelling and dispatching again, which a task cannot do to itself.
     *
     * @return how many samplers were re-dispatched
     */
    public int reload() {
        int redispatched = 0;
        for (final RiggedModelImpl model : List.copyOf(this.bound)) {
            if (model.reload()) {
                redispatched++;
            }
        }
        return redispatched;
    }
}
