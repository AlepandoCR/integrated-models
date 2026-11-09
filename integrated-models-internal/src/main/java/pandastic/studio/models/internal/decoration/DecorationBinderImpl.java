package pandastic.studio.models.internal.decoration;

import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.decoration.AmbientMotion;
import pandastic.studio.models.api.decoration.DecorationBinder;
import pandastic.studio.models.api.decoration.DecorationPlacement;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.binding.RigBindValidator;
import pandastic.studio.models.internal.sim.SimulationLoop;
import pandastic.studio.models.internal.sim.SimulationSettingsSource;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds decorations, and keeps a list of the ones that are still bound.
 *
 * <p>Two responsibilities that sound separable and are not, for the same reason the entity binder keeps
 * both: something has to be able to tear every live decoration down at shutdown, because a tracker is
 * not swept for a plugin on disable — a decoration nobody closed is a model left standing in the world
 * with a frame handler holding this plugin's classes. The only place that can hold that list without
 * inventing a second registry is the thing that handed the handles out.
 *
 * <p>Membership is exactly "bound and not yet stopped": a decoration removes itself as the last step of
 * its own teardown, whether that came from here or from a caller. Which is also why a handle is single
 * use — one that could spawn again after stopping would have left the set with no way back in.
 *
 * <h2>Nothing here reloads</h2>
 * The entity binder has a {@code reload()} because every bound model owns a per-entity repeating task
 * whose rate the platform will not let it change in place. A decoration owns no task: its driver comes
 * from the one server-wide {@link DecorationDriverLoop}, which re-dispatches itself, and every
 * coefficient of the field is read fresh at the top of each of that loop's passes. So a configuration
 * reload reaches every decoration on the server without this class being told, and the absence of a
 * method here is the point rather than an omission.
 *
 * <h2>No platform object in here</h2>
 * The location in {@link #bind(Rig, Location)} is read once — five numbers and a world — and the world
 * is handed straight to a {@link DecorationAnchorFactory}. Everything with a scheduler or a renderer
 * behind it lives on the far side of that seam, which is what makes this class, the one that owns the
 * shutdown path, something a test can construct.
 */
public final class DecorationBinderImpl implements DecorationBinder {

    private final @NotNull DecorationAnchorFactory anchors;
    private final @NotNull SimulationLoop loop;
    private final @NotNull DecorationDriverLoop drivers;
    private final @NotNull SimulationSettingsSource simulationSettings;
    private final @NotNull RigBindValidator validator;

    /**
     * Every decoration handed out and not yet torn down.
     *
     * <p>A concurrent set because decorations are placed from whichever thread owns the position and
     * remove themselves from whichever thread noticed they were finished.
     */
    private final @NotNull Set<DecorationImpl> bound = ConcurrentHashMap.newKeySet();

    /**
     * @param anchors            how a world becomes something this layer may stand a model in
     * @param loop               the single off-tick task every decoration's simulation is stepped from
     * @param drivers            the single off-tick task every decoration's driver is published from
     * @param simulationSettings where the solver, the cadence and the teleport thresholds come from
     * @param validator          the offline check {@link #bind} runs; see {@link RigBindValidator}
     */
    public DecorationBinderImpl(
            @NotNull DecorationAnchorFactory anchors,
            @NotNull SimulationLoop loop,
            @NotNull DecorationDriverLoop drivers,
            @NotNull SimulationSettingsSource simulationSettings,
            @NotNull RigBindValidator validator
    ) {
        this.anchors = Objects.requireNonNull(anchors, "anchors");
        this.loop = Objects.requireNonNull(loop, "loop");
        this.drivers = Objects.requireNonNull(drivers, "drivers");
        this.simulationSettings = Objects.requireNonNull(simulationSettings, "simulationSettings");
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    @Override
    public @NotNull DecorationImpl bind(@NotNull Rig rig, @NotNull Location location) {
        return bind(rig, location, AmbientMotion.NONE);
    }

    @Override
    public @NotNull DecorationImpl bind(
            @NotNull Rig rig,
            @NotNull Location location,
            @NotNull AmbientMotion ambient
    ) {
        Objects.requireNonNull(location, "location");
        final World world = location.getWorld();
        if (world == null) {
            throw new IllegalArgumentException(
                    "A decoration needs a world: the location it was placed at has none");
        }
        // Read once, here. The handle keeps a value rather than the location object, because a Location
        // is mutable and a caller is entitled to reuse one — a handle holding the reference would find
        // its own position changing underneath it.
        return bind(rig, this.anchors.anchorFor(world), placementOf(location), ambient);
    }

    /**
     * Binds {@code rig} to an arbitrary anchor and placement.
     *
     * <p>The form the location overloads are written in terms of, and the only one a test can reach.
     * Not part of the published API, because a {@link DecorationAnchor} is this plugin's own contract
     * about which thread may do what.
     *
     * @param rig       the rig to drive
     * @param anchor    the world to stand it in
     * @param placement where it stands
     * @param ambient   how exposed it is to the ambient field
     * @return the unspawned handle
     * @throws IllegalStateException if the rig cannot bind to its model, naming every bone that failed
     */
    public @NotNull DecorationImpl bind(
            @NotNull Rig rig,
            @NotNull DecorationAnchor anchor,
            @NotNull DecorationPlacement placement,
            @NotNull AmbientMotion ambient
    ) {
        Objects.requireNonNull(rig, "rig");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(placement, "placement");
        Objects.requireNonNull(ambient, "ambient");

        // Before anything exists. A rig whose bone names no longer match its model is a content error,
        // and the person who can fix it is reading the log — so it has to fail here, with every broken
        // name in one message, rather than as a decoration that appears and then does not move.
        this.validator.requireBindable(rig);

        final DecorationImpl decoration = new DecorationImpl(
                rig, anchor, this.loop, this.drivers, this.simulationSettings, placement, ambient,
                this.bound::remove);
        this.bound.add(decoration);
        return decoration;
    }

    /** Every decoration currently bound, as a snapshot. */
    public @NotNull Collection<DecorationImpl> boundDecorations() {
        return List.copyOf(this.bound);
    }

    /** How many decorations are bound. */
    public int size() {
        return this.bound.size();
    }

    /**
     * Despawns every bound decoration, and must be called on plugin disable.
     *
     * <p>Iterates a snapshot rather than the live set: each decoration removes itself as the last step
     * of its own teardown, and mutating the set under its own iterator is the kind of thing that works
     * until the day it does not.
     *
     * <p>One decoration failing to tear down must not cost the rest theirs, so each is isolated. There
     * is nowhere useful to report it to either: this runs during disable, where the plugin's own logger
     * may already be shutting down, and what matters is that every remaining tracker gets closed.
     *
     * @return how many decorations were torn down
     */
    public int despawnAll() {
        int despawned = 0;
        for (final DecorationImpl decoration : List.copyOf(this.bound)) {
            try {
                decoration.despawn();
            } catch (final RuntimeException | Error ignored) {
                // Deliberately swallowed; see above.
            }
            despawned++;
        }
        this.bound.clear();
        return despawned;
    }

    /** The placement a location describes, with its world dropped — the handle owns that. */
    private static @NotNull DecorationPlacement placementOf(@NotNull Location location) {
        return new DecorationPlacement(location.getX(), location.getY(), location.getZ(),
                location.getYaw(), location.getPitch());
    }
}
