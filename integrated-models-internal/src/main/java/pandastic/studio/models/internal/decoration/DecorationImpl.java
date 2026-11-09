package pandastic.studio.models.internal.decoration;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.decoration.AmbientMotion;
import pandastic.studio.models.api.decoration.Decoration;
import pandastic.studio.models.api.decoration.DecorationPlacement;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.sim.DriverHolder;
import pandastic.studio.models.internal.sim.ModelSimulation;
import pandastic.studio.models.internal.sim.SimulationLoop;
import pandastic.studio.models.internal.sim.SimulationSettingsSource;
import pandastic.studio.models.internal.view.CullableModel;
import pandastic.studio.models.internal.view.ModelViewport;
import pandastic.studio.models.internal.view.ModelViewportSink;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A rig standing at a position, with no entity under it: the decoration handle and everything it owns.
 *
 * <p>It is the entity-backed {@code RiggedModelImpl} with the entity taken out, and what is left is
 * markedly smaller — which is the point of having built it separately rather than as a special case.
 * Four things that class has to deal with do not exist here:
 *
 * <ul>
 *   <li><strong>No sampler.</strong> A decoration's position changes only when a caller changes it, so
 *       there is nothing to read once per tick and no per-decoration task to schedule, retain, cancel or
 *       re-dispatch on a reload. Its driver comes from one server-wide {@link DecorationDriverLoop}.</li>
 *   <li><strong>No retirement.</strong> A position cannot be removed, so there is no platform callback
 *       announcing that the thing underneath vanished, no critical-code path that may not touch the
 *       world, and exactly one direction a teardown can come from.</li>
 *   <li><strong>No region ownership to follow.</strong> Moving, showing and hiding are packets; only
 *       creating and releasing the tracker touch anything the server owns, and only because the renderer
 *       fires a platform event on both.</li>
 *   <li><strong>No locomotion.</strong> A decoration does not walk, so its simulation carries no gait
 *       stage and its at-rest test is the physics alone.</li>
 * </ul>
 *
 * <h2>The property this class exists to hold: a resting decoration is silent</h2>
 * Three things have to line up, and all three are here rather than spread across the layer. The driver
 * loop publishes {@link pandastic.studio.models.api.physics.PhysicsDriver#STILL} for a decoration nobody
 * moves and no wind reaches. A motionless driver lets the solver settle, at which point
 * {@code ModelSimulation} emits the settling pose <em>once</em> and then stops emitting. And nothing else
 * in this class ever writes to the sink. So a placed, spawned, sheltered decoration costs one emission
 * and then zero packets per second, for as long as nobody touches it — which is what makes three hundred
 * of them viable.
 *
 * <h2>Spawn order</h2>
 * <ol>
 *   <li><strong>Open the rendering.</strong> First because it is the step most likely to fail — a rig
 *       whose bone names went stale — and the cheapest to unwind, since nothing is registered yet.</li>
 *   <li><strong>Reset the driver</strong>, so a stale sample from a previous life cannot drive the first
 *       step of this one.</li>
 *   <li><strong>Build the simulation</strong>, which is where a rig whose tuning the current calibration
 *       cannot integrate is refused — wanted here rather than inside the loop's catch-all.</li>
 *   <li><strong>Register with the simulation loop.</strong> From this instant the decoration is stepped,
 *       which is safe: the sink is live and a step with no driver yet simulates a motionless world and
 *       emits the rest pose, which is exactly what something that has just appeared should look
 *       like.</li>
 *   <li><strong>Register with the driver loop.</strong> Last, because it is the publisher: it must not
 *       be able to publish into state that does not exist yet.</li>
 * </ol>
 *
 * <p>Any step failing unwinds the ones before it in reverse. A half-spawned decoration is worse than a
 * failed one: it leaves a tracker standing in the world that nothing holds a reference to.
 *
 * <h2>Teardown order</h2>
 * The same rule as the entity path — <em>stop everything that can drive the model, then release what it
 * was driving</em> — and one monitor and one latch, so the body runs once however many callers reach it
 * from however many threads.
 *
 * <ol>
 *   <li>Unregister from the simulation loop, which is the only thing that reaches the sink.</li>
 *   <li>Unregister from the driver loop, which is the only thing that publishes.</li>
 *   <li>Detach the sink, which covers an emission that was already in flight when step 1 ran — the
 *       loop's {@code unregister} does not wait for an iteration to finish.</li>
 *   <li>Release the tracker, on a thread allowed to fire a platform event.</li>
 * </ol>
 */
public final class DecorationImpl implements Decoration, DrivenDecoration, CullableModel {

    /**
     * Published by whoever drives this decoration, read by the simulation thread. The seam, and the only
     * object the two threads share.
     */
    private final @NotNull DriverHolder driver = new DriverHolder();

    /** Who currently holds this decoration's bones. The same type the distance culler drives. */
    private final @NotNull ModelViewport viewport = new ModelViewport();

    private final @NotNull ModelViewportSink viewportSink = new Viewport();

    private final @NotNull Rig rig;
    private final @NotNull UUID uniqueIdentifier = UUID.randomUUID();
    private final @NotNull DecorationAnchor anchor;
    private final @NotNull SimulationLoop loop;
    private final @NotNull DecorationDriverLoop drivers;
    private final @NotNull SimulationSettingsSource simulationSettings;

    /** Told once, when this decoration has finished tearing down. See {@link DecorationBinderImpl}. */
    private final @NotNull Consumer<DecorationImpl> onStopped;

    /**
     * Where this decoration stands.
     *
     * <p>Volatile because the driver loop reads it on an arbitrary thread while a caller moves it from
     * another, and immutable so that one reference store carries a whole move: a reader never sees a new
     * position against an old yaw.
     */
    private volatile @NotNull DecorationPlacement placement;

    /** Read once per pass by the driver loop; written by any caller. */
    private volatile @NotNull AmbientMotion ambient;

    /**
     * Read by the view sink and by {@link #show} from arbitrary threads, so volatile rather than
     * monitor-guarded. Set before anything is registered and cleared as part of the teardown, which
     * makes it the gate on everything that touches the renderer: a call that lands after the decoration
     * stopped finds null and does nothing.
     */
    private volatile @Nullable DecorationRendering rendering;

    private volatile @Nullable ModelSimulation simulation;

    private volatile boolean alive;

    /** The single-use latch. Guarded by {@code this}. */
    private boolean stopped;

    /** Guarded by {@code this}: only the lifecycle methods touch these. */
    private @Nullable SimulationLoop.Registration registration;
    private @Nullable DecorationDriverLoop.Registration driverRegistration;

    /**
     * @param anchor             the world this decoration stands in, and the only thing that may create
     *                           its tracker
     * @param loop               the single off-tick task every simulation is stepped from
     * @param drivers            the single off-tick task every decoration's driver is published from
     * @param simulationSettings where the solver, the cadence and the teleport thresholds come from
     * @param placement          where it stands until somebody moves it
     * @param ambient            how exposed it is to the ambient field
     * @param onStopped          told once when this decoration has torn down, whether it ever spawned
     */
    public DecorationImpl(
            @NotNull Rig rig,
            @NotNull DecorationAnchor anchor,
            @NotNull SimulationLoop loop,
            @NotNull DecorationDriverLoop drivers,
            @NotNull SimulationSettingsSource simulationSettings,
            @NotNull DecorationPlacement placement,
            @NotNull AmbientMotion ambient,
            @NotNull Consumer<DecorationImpl> onStopped
    ) {
        this.rig = Objects.requireNonNull(rig, "rig");
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        this.loop = Objects.requireNonNull(loop, "loop");
        this.drivers = Objects.requireNonNull(drivers, "drivers");
        this.simulationSettings = Objects.requireNonNull(simulationSettings, "simulationSettings");
        this.placement = Objects.requireNonNull(placement, "placement");
        this.ambient = Objects.requireNonNull(ambient, "ambient");
        this.onStopped = Objects.requireNonNull(onStopped, "onStopped");
    }

    // ------------------------------------------------------------------ identity

    @Override
    public @NotNull Rig getRig() {
        return this.rig;
    }

    @Override
    public @NotNull UUID getUniqueIdentifier() {
        return this.uniqueIdentifier;
    }

    @Override
    public @NotNull UUID getWorldId() {
        return this.anchor.worldId();
    }

    @Override
    public boolean isAlive() {
        return this.alive;
    }

    /** Whether this handle has torn down and can no longer spawn. */
    public synchronized boolean isStopped() {
        return this.stopped;
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * {@inheritDoc}
     *
     * <p>See the class documentation for the order and for why each step is where it is.
     *
     * @throws IllegalStateException if called off the thread that owns the placement's region, if this
     *                               handle has already been despawned, or if the rig cannot be bound to
     *                               the live model
     */
    @Override
    public synchronized boolean spawn() {
        if (this.stopped) {
            throw new IllegalStateException(
                    "Rig '" + this.rig.getName() + "' at " + this.placement + " in "
                            + this.anchor.describe() + " has already been despawned. A handle is single"
                            + " use — bind again rather than reviving one, because its cached bone"
                            + " transformers belong to a tracker that no longer exists.");
        }
        if (this.alive) {
            return false;
        }

        final DecorationPlacement at = this.placement;
        if (!this.anchor.isOwnedByCurrentThread(at)) {
            // Guarded here because the platform will not guard it. Creating the tracker fires a
            // platform event, and a listener is entitled to assume it runs where events for that
            // position run.
            throw new IllegalStateException(
                    "Rig '" + this.rig.getName() + "' cannot be spawned at " + at + " in "
                            + this.anchor.describe() + " from this thread: creating the renderer's"
                            + " tracker fires a platform event, which belongs on the thread that owns"
                            + " that position's region. Schedule the spawn there.");
        }

        final DecorationRendering opened = this.anchor.open(this.rig, at);

        SimulationLoop.Registration registered = null;
        DecorationDriverLoop.Registration driven = null;
        boolean committed = false;
        try {
            // A driver left over from a previous binding describes motion that is not happening.
            this.driver.reset();

            // No locomotion: a decoration does not walk, so its at-rest test is the physics alone.
            final ModelSimulation built = new ModelSimulation(
                    this.rig, this.simulationSettings, opened.sink(), this.driver);

            this.rendering = opened;
            this.simulation = built;
            registered = this.loop.register(built);
            driven = this.drivers.register(this);

            this.registration = registered;
            this.driverRegistration = driven;
            this.alive = true;
            committed = true;
            return true;
        } finally {
            // One unwind for every way out, driven by a flag rather than a catch so that no failure
            // path can be added later without passing through here.
            if (!committed) {
                unwind(registered, driven, opened);
            }
        }
    }

    /**
     * Undoes a failed {@link #spawn()} in the reverse of the order that built it.
     *
     * <p>A tracker left open by a spawn that threw is a model standing in the world that nothing holds a
     * reference to, so nothing will ever close it. That is the one outcome worth writing an unwind for.
     */
    private void unwind(
            @Nullable SimulationLoop.Registration registered,
            @Nullable DecorationDriverLoop.Registration driven,
            @NotNull DecorationRendering opened
    ) {
        if (driven != null) {
            driven.unregister();
        }
        if (registered != null) {
            registered.unregister();
        }
        this.rendering = null;
        this.simulation = null;
        opened.detach();
        // On the owning thread by construction: spawn() refused to run anywhere else.
        opened.close();
    }

    @Override
    public void despawn() {
        stop();
    }

    /**
     * The one teardown, reached from every direction and run once.
     *
     * <p>Unlike the entity path there is no variant of this: a position has no retired callback, so
     * there is no caller that is forbidden from releasing the tracker and no case in which the release
     * has to be left to the renderer.
     */
    private synchronized void stop() {
        if (this.stopped) {
            return;
        }
        this.stopped = true;
        this.alive = false;

        // 1. The stepper, first, because it is the only thing that reaches the sink.
        final SimulationLoop.Registration registered = this.registration;
        this.registration = null;
        if (registered != null) {
            registered.unregister();
        }

        // 2. The publisher. A pass already in flight only writes this decoration's own driver holder,
        //    which nothing reads any more.
        final DecorationDriverLoop.Registration driven = this.driverRegistration;
        this.driverRegistration = null;
        if (driven != null) {
            driven.unregister();
        }

        this.simulation = null;
        this.driver.reset();

        // 3. and 4. Stop writing, then release.
        final DecorationRendering opened = this.rendering;
        this.rendering = null;
        if (opened != null) {
            opened.detach();
            release(opened);
        }

        // The tracker's own close despawned every display, so this is bookkeeping rather than packets.
        this.viewport.clear();

        this.onStopped.accept(this);
    }

    /**
     * Releases {@code opened} on a thread allowed to fire a platform event.
     *
     * <p>Closing a dummy tracker is packets — it has no hitbox entities to remove, because nothing here
     * ever asks for one — but it does fire the renderer's close event, and a listener is entitled to
     * assume that runs where events for that position run. Inline when we are already on that thread,
     * which is where a well-behaved caller is; deferred through the anchor otherwise.
     *
     * <p>A refused deferral means the world is gone, in which case there is nothing left to despawn for
     * and nobody left to despawn it for.
     */
    private void release(@NotNull DecorationRendering opened) {
        final DecorationPlacement at = this.placement;
        if (this.anchor.isOwnedByCurrentThread(at)) {
            opened.close();
            return;
        }
        this.anchor.submit(at, opened::close);
    }

    // ------------------------------------------------------------------ placement

    @Override
    public @NotNull DecorationPlacement getPlacement() {
        return this.placement;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The same value {@link #getPlacement()} returns, under the name the driver loop reads it by.
     * Two names for one field rather than one, because the published API and this plugin's own seam are
     * allowed to disagree about naming and are not allowed to disagree about the value.
     */
    @Override
    public @NotNull DecorationPlacement placement() {
        return this.placement;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Two stores and a packet. The physics is deliberately <strong>not</strong> told here: the next
     * pass of the driver loop differences this placement against the one it last published from, which
     * is what makes the move into a velocity the bones can feel. A caller that published its own impulse
     * would have it overwritten by that pass before the simulation had necessarily read it — a dropped
     * impulse that happens only when the timing lines up, which is the worst kind.
     *
     * <p>A move on a spent handle is ignored rather than refused. It is not a lifecycle operation, and a
     * plugin moving a decoration the platform already tore down has nothing to fix.
     */
    @Override
    public void moveTo(@NotNull DecorationPlacement to) {
        Objects.requireNonNull(to, "placement");
        final DecorationRendering opened;
        synchronized (this) {
            if (this.stopped) {
                return;
            }
            this.placement = to;
            opened = this.rendering;
        }
        // Outside the monitor: the renderer takes its own lock and sends packets, and holding ours
        // across that would put a teardown behind a network buffer.
        if (opened != null) {
            opened.moveTo(to);
        }
    }

    // ------------------------------------------------------------------ visibility

    /**
     * {@inheritDoc}
     *
     * <p>The viewport is marked <em>before</em> the packets go out and un-marked if they could not,
     * which keeps the registry's answer to "who has this" true rather than optimistic. A viewer recorded
     * as holding a model they do not get no updates and no repair, which is the one state that never
     * fixes itself.
     */
    @Override
    public boolean show(@NotNull UUID viewer) {
        Objects.requireNonNull(viewer, "viewer");
        final DecorationRendering opened = liveRendering();
        if (opened == null) {
            // Nothing to send: a decoration that is not spawned has no displays. Not an exception,
            // because "place, show, spawn" is a reasonable order for a caller to try.
            return false;
        }
        if (!this.viewport.markShown(viewer)) {
            return false;
        }
        if (opened.show(viewer)) {
            return true;
        }
        this.viewport.markHidden(viewer);
        return false;
    }

    @Override
    public boolean hide(@NotNull UUID viewer) {
        Objects.requireNonNull(viewer, "viewer");
        if (!this.viewport.markHidden(viewer)) {
            return false;
        }
        final DecorationRendering opened = this.rendering;
        if (opened != null) {
            opened.hide(viewer);
        }
        // True even when the packet could not be sent: a player who is not online does not hold this
        // decoration, which is exactly what the registry now says.
        return true;
    }

    @Override
    public boolean isShownTo(@NotNull UUID viewer) {
        return this.viewport.isShownTo(viewer);
    }

    /**
     * The rendering, but only once this decoration is committed and still alive.
     *
     * <p>Both conditions, and the {@code alive} half is the one worth having. The rendering is visible
     * from the moment it is opened, which is several steps before a spawn commits — so a show that
     * landed in that window would spawn displays on a tracker a failing spawn is about to close, and
     * leave a viewer recorded as holding a decoration that never existed. Gating on the flag that the
     * commit sets makes that window unreachable instead of narrow.
     */
    private @Nullable DecorationRendering liveRendering() {
        return this.alive ? this.rendering : null;
    }

    /** How many viewers this decoration is currently costing bandwidth for. */
    public int shownCount() {
        return this.viewport.shownCount();
    }

    // ------------------------------------------------------------------ ambient field

    @Override
    public @NotNull AmbientMotion getAmbientMotion() {
        return this.ambient;
    }

    @Override
    public void setAmbientMotion(@NotNull AmbientMotion motion) {
        this.ambient = Objects.requireNonNull(motion, "motion");
    }

    @Override
    public @NotNull AmbientMotion ambientMotion() {
        return this.ambient;
    }

    @Override
    public @NotNull DriverHolder driver() {
        return this.driver;
    }

    /** This decoration's physics, or {@code null} if it is not spawned. */
    public @Nullable ModelSimulation simulation() {
        return this.simulation;
    }

    // ------------------------------------------------------------------ culling

    @Override
    public @NotNull UUID modelId() {
        return this.uniqueIdentifier;
    }

    @Override
    public @NotNull UUID worldId() {
        return this.anchor.worldId();
    }

    @Override
    public double cullX() {
        return this.placement.x();
    }

    /**
     * {@inheritDoc}
     *
     * <p>The placement's own y, which is the model's origin rather than its visual centre: nothing here
     * knows how tall the model is, and the bind pose that does know lives behind the renderer seam. A
     * tall decoration is therefore kept on screen by {@link #cullRadius()}'s configured default rather
     * than by a height nobody measured.
     */
    @Override
    public double cullY() {
        return this.placement.y();
    }

    @Override
    public double cullZ() {
        return this.placement.z();
    }

    @Override
    public @NotNull ModelViewport viewport() {
        return this.viewport;
    }

    @Override
    public @NotNull ModelViewportSink viewportSink() {
        return this.viewportSink;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Only a live decoration is a culling decision. One that has not spawned has nothing to show, and
     * one that has stopped has already had everything taken away — so both answer "hide from everyone",
     * which is what this returning {@code false} means.
     */
    @Override
    public boolean isCullable() {
        return this.alive;
    }

    @Override
    public @NotNull String toString() {
        return "Decoration[" + this.rig.getName() + " at " + this.placement + " in "
                + this.anchor.describe() + ", alive=" + this.alive
                + ", viewers=" + this.viewport.shownCount() + "]";
    }

    /**
     * The culler's view of this decoration: transport only.
     *
     * <p>A separate object rather than this class implementing {@link ModelViewportSink} directly,
     * because the sink's {@code show(UUID)} and {@link Decoration#show(UUID)} differ only in return
     * type and could not coexist on one class. The separation is honest anyway: the sink sends and the
     * public methods decide, and the culler has already updated the viewport by the time it calls here.
     */
    private final class Viewport implements ModelViewportSink {

        @Override
        public void show(@NotNull UUID viewer) {
            final DecorationRendering opened = liveRendering();
            if (opened != null) {
                opened.show(viewer);
            }
        }

        @Override
        public void hide(@NotNull UUID viewer) {
            // Deliberately not gated on being alive, unlike the show: a culler that decided to take a
            // decoration away while it was being torn down should still be allowed to send the
            // despawn, and the tracker's own close is latched so the second one is free.
            final DecorationRendering opened = DecorationImpl.this.rendering;
            if (opened != null) {
                opened.hide(viewer);
            }
        }
    }
}
