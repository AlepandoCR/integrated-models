package pandastic.studio.models.internal.binding;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.bukkit.entity.Entity;
import pandastic.studio.models.api.binding.BindingOptions;
import pandastic.studio.models.api.binding.RiggedModel;
import pandastic.studio.models.api.locomotion.GaitState;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.sim.DriverHolder;
import pandastic.studio.models.internal.sim.DriverSampler;
import pandastic.studio.models.internal.sim.LocomotionStage;
import pandastic.studio.models.internal.sim.ModelSimulation;
import pandastic.studio.models.internal.sim.SimulationLoop;
import pandastic.studio.models.internal.sim.SimulationSettingsSource;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A rig, the thing it is attached to, and everything needed to make the two move together.
 *
 * <p>This is the type every other layer in this plugin was built for and the one that connects them: it
 * owns the {@link ModelAnchor}, the {@link RenderBinding} that turns poses into packets, the
 * {@link ModelSimulation} that produces them, the {@link DriverHolder} the two communicate through, and
 * the per-tick task that fills it. Before this class existed every one of those worked and none of them
 * were reachable.
 *
 * <h2>The region-safety argument, in full</h2>
 * Two threads touch a bound model and they touch disjoint things.
 *
 * <p>The <strong>owning region thread</strong> runs the sampler. It is the only thread that reads the
 * anchor, and it does so once per period, into a buffer it owns. What leaves that read is an immutable
 * {@link pandastic.studio.models.api.physics.PhysicsDriver} stored into a volatile field, plus two
 * booleans written into the gait's state. That single reference store is what carries the
 * happens-before edge: a reader that sees the new driver sees all four of its components, and sees them
 * from the same sample.
 *
 * <p>The <strong>simulation thread</strong> steps the physics. It reads that volatile field once per
 * step and touches no anchor, no entity, no world and no block — which is not a convention but a
 * property of the types involved: a {@link ModelSimulation} is reachable only from a
 * {@link pandastic.studio.models.api.rig.Rig}, a {@link pandastic.studio.models.api.pose.Pose} and a
 * {@link pandastic.studio.models.api.render.PoseSink}, none of which can name a platform type.
 *
 * <p>The two rates are deliberately unrelated. The sampler runs on ticks because that is how often its
 * input changes; the simulation runs at its own configured rate because that is what its integrator's
 * stability requires. Coupling them would mean either sampling an unchanged position sixty times a
 * second or integrating a spring at twenty.
 *
 * <h2>Spawn order</h2>
 * <ol>
 *   <li><strong>Open the rendering.</strong> Every rig bone is resolved to a cached display transformer
 *       and the whole list of failures is reported at once. First because it is the step most likely to
 *       fail and the cheapest to unwind — nothing is registered and nothing is scheduled yet.</li>
 *   <li><strong>Reset the driver and the sampler.</strong> A position delta measured across a bind
 *       describes nothing physical, and the sampler's first reading after a reset is deliberately
 *       {@code STILL} rather than a difference against a remembered position from another binding.</li>
 *   <li><strong>Build the simulation</strong> around the sink from step 1. This is where a rig whose
 *       tuning the current calibration cannot integrate is refused, which is wanted here rather than
 *       sixteen milliseconds later inside the loop's catch-all.</li>
 *   <li><strong>Register with the loop.</strong> From this instant the model is being stepped, which is
 *       safe: the sink is live, the tracker is open, and a step with no sample yet simulates a
 *       motionless owner and emits the rest pose — which is exactly what a model that has just appeared
 *       should look like.</li>
 *   <li><strong>Schedule the sampler.</strong> Last, because it is the only thing that can tear the
 *       model down from another thread, and it must not be able to do that to state that does not exist
 *       yet.</li>
 * </ol>
 *
 * <p>Any step failing unwinds the ones before it in reverse. A half-spawned model is worse than a failed
 * one: it leaves a tracker in the world that nothing will ever close.
 *
 * <h2>Teardown order</h2>
 * The rule is <em>stop everything that can drive the model, then release what it was driving</em>.
 *
 * <ol>
 *   <li><strong>Unregister from the loop.</strong> Stops the stepper, which is the only thing that
 *       reaches the sink.</li>
 *   <li><strong>Cancel the sampler.</strong> Stops the publisher.</li>
 *   <li><strong>Detach the sink.</strong> Now nothing can write a transform, including an emission that
 *       was already in flight when step 1 ran — the loop's {@code unregister} does not wait for an
 *       iteration to finish, so this is what covers that window rather than hoping it is narrow.</li>
 *   <li><strong>Release the tracker</strong>, on a thread allowed to mutate the world. Last, so no live
 *       driver can ever observe a closed tracker.</li>
 * </ol>
 *
 * <h2>Stopping exactly once, from whichever side notices first</h2>
 * Three things can decide a model is finished and they can decide it simultaneously: the platform's
 * retired callback, the sampler finding its anchor invalid, and a caller — usually the plugin shutting
 * down. All three go through one monitor and one latch, so the teardown body runs once and the rest
 * return immediately.
 *
 * <p>The retired path is the one exception to the ordering above, and it is the platform's rule rather
 * than ours: a retired callback runs in critical code that may not remove entities, and releasing a
 * tracker removes whatever entities the renderer created for hitboxes. It is also unschedulable, because
 * the anchor's scheduler is precisely what has just retired. So that path performs steps 1 to 3 and
 * leaves the release to the renderer, which closes its own registry from its own entity-removal hook.
 *
 * <h2>Single use</h2>
 * A model spawns once and stops once; a stopped handle refuses to spawn again. The alternative is a
 * state machine that has to decide what a second spawn does with a sink whose per-bone transformers were
 * cached against a tracker that no longer exists, and every answer to that is a binding that looks live
 * and writes nowhere.
 *
 * <h2>What a binding may differ about</h2>
 * One thing, so far: the teleport filter its sampler measures movement against. It is fixed for the
 * handle's life because it is wired into the sampler at construction, and it is wired in as a
 * <em>source</em> rather than as a value so that a binding which stated nothing keeps following the
 * live configuration; see {@link DriverBoundsOverride}. Everything else — the solver, the cadence, the
 * sample period — stays server-wide, and the simulation is deliberately handed the unmodified
 * configuration: {@code driverBounds} is the only thing in that snapshot the sampler reads and the only
 * thing a binding can change, so nothing else sees a derived view of its settings.
 */
public final class RiggedModelImpl implements RiggedModel {

    /**
     * Published by the owning region thread, read by the simulation thread. The seam, and the only
     * object both threads touch.
     */
    private final @NotNull DriverHolder driver = new DriverHolder();

    private final @NotNull Rig rig;
    private final @NotNull UUID uniqueIdentifier = UUID.randomUUID();
    private final @NotNull ModelAnchor anchor;
    private final @NotNull SimulationLoop loop;
    private final @NotNull SimulationSettingsSource simulationSettings;
    private final @NotNull BindingSettingsSource bindingSettings;
    private final @NotNull LocomotionStageSource locomotion;

    /** What this binding stated about itself; {@link BindingOptions#defaults()} if it stated nothing. */
    private final @NotNull BindingOptions options;

    /** Told once, when this model has finished tearing down. See {@link ModelBinderImpl}. */
    private final @NotNull Consumer<RiggedModelImpl> onStopped;

    /**
     * Reused every sample, and confined to the owning region thread.
     *
     * <p>Along with {@link #driverSampler}, this is why the sampling path allocates nothing but the one
     * driver it publishes.
     */
    private final @NotNull AnchorSample sample = new AnchorSample();

    /**
     * Differences successive samples, and remembers the previous one.
     *
     * <p>Region-confined, like {@link #sample}, which is why it is reset in {@link #spawn()} — on the
     * owning thread — and never in the teardown, which can run anywhere.
     *
     * <p>Built over {@link DriverBoundsOverride#sourceFor} rather than over the server-wide source
     * directly. That is the one place this binding's own teleport filter, if it stated one, enters the
     * pipeline — and when it stated none the two are the same object.
     */
    private final @NotNull DriverSampler driverSampler;
    private long attackSequence;
    private boolean attackSeeded;

    /**
     * Read by the sampler on the region thread with no lock, so volatile rather than monitor-guarded.
     *
     * <p>Set before the sampler is scheduled and cleared as part of the teardown, which makes it the
     * sampler's own gate: a body that lands after the model stopped finds null and does nothing.
     */
    private volatile @Nullable ModelSimulation simulation;

    private volatile boolean alive;

    /** The single-use latch. Guarded by {@code this}; see "Stopping exactly once". */
    private boolean stopped;

    /** Guarded by {@code this}: only the lifecycle methods touch these. */
    private @Nullable RenderBinding rendering;
    private @Nullable SimulationLoop.Registration registration;
    private @Nullable AnchorTask samplerTask;

    /**
     * The period the live sampler was dispatched at, which is what {@link #reload()} compares against.
     * A repeating task's rate belongs to the platform, so the configured period and the scheduled one
     * can differ — between a reload and the call that acts on it.
     */
    private long scheduledPeriodTicks;

    /**
     * A model that differs about nothing, driven entirely by the server's configuration.
     *
     * @param anchor             where this model is, and the only thing allowed to read it
     * @param loop               the single off-tick task every simulation is stepped from
     * @param simulationSettings where the solver, cadence and teleport thresholds come from
     * @param bindingSettings    where the sampler's period comes from
     * @param locomotion         the walk cycle for this rig, or one that answers null
     * @param onStopped          told once when this model has torn down, whether it ever spawned or not
     */
    public RiggedModelImpl(
            @NotNull Rig rig,
            @NotNull ModelAnchor anchor,
            @NotNull SimulationLoop loop,
            @NotNull SimulationSettingsSource simulationSettings,
            @NotNull BindingSettingsSource bindingSettings,
            @NotNull LocomotionStageSource locomotion,
            @NotNull Consumer<RiggedModelImpl> onStopped
    ) {
        this(rig, anchor, loop, simulationSettings, bindingSettings, locomotion,
                BindingOptions.defaults(), onStopped);
    }

    /**
     * @param anchor             where this model is, and the only thing allowed to read it
     * @param loop               the single off-tick task every simulation is stepped from
     * @param simulationSettings where the solver, cadence and teleport thresholds come from
     * @param bindingSettings    where the sampler's period comes from
     * @param locomotion         the walk cycle for this rig, or one that answers null
     * @param options            what this binding differs about, or {@link BindingOptions#defaults()}
     * @param onStopped          told once when this model has torn down, whether it ever spawned or not
     */
    public RiggedModelImpl(
            @NotNull Rig rig,
            @NotNull ModelAnchor anchor,
            @NotNull SimulationLoop loop,
            @NotNull SimulationSettingsSource simulationSettings,
            @NotNull BindingSettingsSource bindingSettings,
            @NotNull LocomotionStageSource locomotion,
            @NotNull BindingOptions options,
            @NotNull Consumer<RiggedModelImpl> onStopped
    ) {
        this.rig = Objects.requireNonNull(rig, "rig");
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        this.loop = Objects.requireNonNull(loop, "loop");
        this.simulationSettings = Objects.requireNonNull(simulationSettings, "simulationSettings");
        this.bindingSettings = Objects.requireNonNull(bindingSettings, "bindingSettings");
        this.locomotion = Objects.requireNonNull(locomotion, "locomotion");
        this.options = Objects.requireNonNull(options, "options");
        this.onStopped = Objects.requireNonNull(onStopped, "onStopped");
        this.driverSampler =
                new DriverSampler(DriverBoundsOverride.sourceFor(this.simulationSettings, options));
    }

    @Override
    public @NotNull Rig getRig() {
        return this.rig;
    }

    @Override
    public @NotNull UUID getUniqueIdentifier() {
        return this.uniqueIdentifier;
    }

    @Override
    public @NotNull Optional<Entity> getEntity() {
        return this.anchor instanceof EntityAnchor entityAnchor
                ? Optional.of(entityAnchor.entity())
                : Optional.empty();
    }

    @Override
    public boolean isAlive() {
        return this.alive;
    }

    /**
     * {@inheritDoc}
     *
     * <p>See the class documentation for the order and for why each step is where it is.
     *
     * @throws IllegalStateException if called off the anchor's owning thread, if this handle has already
     *                               stopped, or if the rig cannot be bound to the live model
     */
    @Override
    public synchronized boolean spawn() {
        if (this.stopped) {
            throw new IllegalStateException(
                    "Rig '" + this.rig.getName() + "' on " + this.anchor.describe()
                            + " has already been despawned. A handle is single use — bind again rather"
                            + " than reviving one, because its cached bone transformers belong to a"
                            + " tracker that no longer exists.");
        }
        if (this.alive) {
            return false;
        }
        if (!this.anchor.isOwnedByCurrentThread()) {
            // Guarded here because the platform will not guard it. Creating a tracker reads the
            // anchor's position, and reading a position from a thread that does not own the anchor is
            // undefined rather than merely racy — it fails silently, with a torn coordinate.
            throw new IllegalStateException(
                    "Rig '" + this.rig.getName() + "' cannot be spawned on " + this.anchor.describe()
                            + " from this thread: spawning reads the anchor's position, which is only"
                            + " defined on the thread that owns it. Schedule the spawn on that thread.");
        }
        if (!this.anchor.isAlive()) {
            // A race the caller cannot avoid: the anchor can go between binding and spawning. Not an
            // exception, because there is nothing the caller did wrong and nothing it could have done.
            return false;
        }

        final long periodTicks = this.bindingSettings.current().samplePeriodTicks();
        final RenderBinding opened = this.anchor.render(this.rig);

        SimulationLoop.Registration registered = null;
        boolean committed = false;
        try {
            // A delta measured across a bind describes nothing physical, so the first sample of the new
            // binding has to be a first sample rather than a difference against the old one.
            this.driver.reset();
            this.driverSampler.reset();
            this.attackSeeded = false;

            final ModelSimulation built = new ModelSimulation(
                    this.rig, this.simulationSettings, opened.sink(), this.driver,
                    this.locomotion.stageFor(this.rig));

            // Visible to the sampler from here on, which is why it is set before anything is scheduled.
            this.simulation = built;
            registered = this.loop.register(built);

            final AnchorTask scheduled =
                    this.anchor.schedule(this::sampleOnce, this::onAnchorRetired, periodTicks);
            if (scheduled == null) {
                // The anchor retired between the check above and this call. Neither callback will ever
                // run, so nothing would ever tear this down; the unwind below is the only exit.
                return false;
            }

            this.rendering = opened;
            this.registration = registered;
            this.samplerTask = scheduled;
            this.scheduledPeriodTicks = periodTicks;
            this.alive = true;
            committed = true;
            return true;
        } finally {
            // One unwind for both ways out: a step that threw, and an anchor that retired mid-spawn.
            // The flag rather than a catch, so no failure path can be added later without passing
            // through here.
            if (!committed) {
                unwind(registered, opened);
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
            @NotNull RenderBinding opened
    ) {
        if (registered != null) {
            registered.unregister();
        }
        this.simulation = null;
        opened.detach();
        // On the owning thread by construction: spawn() refused to run anywhere else.
        opened.close();
    }

    @Override
    public void despawn() {
        stop(false);
    }

    /**
     * The platform telling us the anchor is gone — the authoritative notification, and the only one for
     * an entity that was removed rather than merely killed.
     */
    private void onAnchorRetired() {
        stop(true);
    }

    /**
     * The one teardown, reached from every direction and run once.
     *
     * @param anchorRetired whether this is the platform's retired callback, which may not release the
     *                      tracker; see "Stopping exactly once" in the class documentation
     */
    private synchronized void stop(boolean anchorRetired) {
        if (this.stopped) {
            return;
        }
        this.stopped = true;
        this.alive = false;

        // 1. The stepper. Done first because it is the only thing that reaches the sink.
        final SimulationLoop.Registration registered = this.registration;
        this.registration = null;
        if (registered != null) {
            registered.unregister();
        }

        // 2. The publisher. Its body only writes this model's own driver and gait state, so a body
        //    already in flight is harmless; cancelling here simply stops any further ones.
        final AnchorTask scheduled = this.samplerTask;
        this.samplerTask = null;
        if (scheduled != null) {
            scheduled.cancel();
        }

        // The sampler's own gate, and the reason an in-flight body does nothing. Not the sampler's
        // remembered position, though: that is region-confined state and this method is not, so it is
        // reset by the next spawn — on the owning thread — instead of from here.
        this.simulation = null;
        this.driver.reset();

        // 3. and 4. Stop writing, then release.
        final RenderBinding opened = this.rendering;
        this.rendering = null;
        if (opened != null) {
            opened.detach();
            release(opened, anchorRetired);
        }

        this.onStopped.accept(this);
    }

    /**
     * Releases {@code opened} on a thread allowed to mutate the world, or leaves it to the renderer.
     *
     * <p>Three cases, and the awkward one is real. On the owning thread — which is where the sampler's
     * own teardown and a well-behaved caller both run — the close happens inline. Off it, the close is
     * handed to the anchor's scheduler. From a retired callback it cannot be done at all: the platform
     * documents that callback as critical code which may not remove entities, closing a tracker removes
     * whatever hitbox entities the renderer created for it, and there is no thread left to defer to
     * because the scheduler that would have run the deferred work is the one that just retired.
     *
     * <p>In both of the cases where we decline, the renderer has already closed or is about to close its
     * own registry from its entity-removal hook, and its close is latched — so declining costs nothing
     * and doing it anyway would mean removing entities from the wrong thread.
     */
    private void release(@NotNull RenderBinding opened, boolean anchorRetired) {
        if (anchorRetired) {
            return;
        }
        if (this.anchor.isOwnedByCurrentThread()) {
            opened.close();
            return;
        }
        this.anchor.submit(opened::close);
    }

    /**
     * One sample: read the anchor, publish the movement, hand over the stance.
     *
     * <p>Runs on the owning region thread, once per configured period, and allocates nothing but the one
     * immutable driver it publishes — the buffer and the differencer are both fields, reused.
     *
     * <p>Order inside it is not arbitrary. The anchor is read <strong>once</strong>, into one buffer,
     * and both the movement and the stance come out of that one reading. Reading twice would let the
     * tick move the entity between the two and have the gait's stance disagree with its speed, which is
     * the same hazard the simulation's one-driver-sample-per-step rule covers, one layer earlier.
     */
    private void sampleOnce() {
        final ModelSimulation running = this.simulation;
        if (running == null) {
            return;
        }
        if (!this.anchor.readInto(this.sample)) {
            // The other side of the exactly-once guarantee: an entity that is dead but whose scheduler
            // has not retired yet is noticed here rather than never. Safe to tear down from inside the
            // body — this is an ordinary repeating task, not the platform's critical retired callback.
            stop(false);
            return;
        }

        this.driverSampler.sampleInto(this.driver, this.sample.x(), this.sample.y(), this.sample.z(),
                this.sample.yawDegrees());
        this.driver.publishHeadOrientation(
                (float) Math.toRadians(DriverSampler.wrapDegrees(
                        this.sample.yawDegrees() - this.sample.headYawDegrees())),
                (float) Math.toRadians(this.sample.pitchDegrees()));

        final LocomotionStage gait = running.locomotion();
        if (gait != null) {
            if (!this.attackSeeded) {
                this.attackSequence = this.sample.attackSequence();
                this.attackSeeded = true;
            } else if (this.sample.attackSequence() != this.attackSequence) {
                this.attackSequence = this.sample.attackSequence();
                gait.state().triggerAttack();
            }
            // Booleans across the same boundary the driver already crosses, and for the same reason:
            // these are things only a region thread can know and the simulation must not go looking
            // for. The gait eases toward them rather than snapping, which is why they are state and not
            // arguments.
            final GaitState state = gait.state();
            state.setSneaking(this.sample.sneaking());
            state.setAirborne(this.sample.airborne());
        }
    }

    /**
     * Re-dispatches the sampler if configuration moved its period.
     *
     * <p>Explicit rather than detected per sample, for the same reason
     * {@link SimulationLoop#reload()} is: a repeating task's rate belongs to the platform, so the only
     * way to change it is to cancel and dispatch again, which a task cannot do to itself from inside its
     * own body. Reading the period per sample would also put an allocation on the sampling path, since a
     * live settings source builds its value per call.
     *
     * <p>Cancels before dispatching, never the other way round: two live samplers would both difference
     * against the same remembered position and publish half-intervals.
     *
     * @return whether the sampler was re-dispatched
     */
    public synchronized boolean reload() {
        if (!this.alive) {
            return false;
        }
        final long periodTicks = this.bindingSettings.current().samplePeriodTicks();
        if (periodTicks == this.scheduledPeriodTicks) {
            return false;
        }

        final AnchorTask previous = this.samplerTask;
        this.samplerTask = null;
        if (previous != null) {
            previous.cancel();
        }

        final AnchorTask scheduled =
                this.anchor.schedule(this::sampleOnce, this::onAnchorRetired, periodTicks);
        if (scheduled == null) {
            // The anchor retired while we were re-dispatching, and we have just cancelled the only task
            // that would have told us. Nothing else will, so stop here.
            stop(false);
            return false;
        }
        this.samplerTask = scheduled;
        this.scheduledPeriodTicks = periodTicks;
        return true;
    }

    /** Where this model is. */
    public @NotNull ModelAnchor anchor() {
        return this.anchor;
    }

    /**
     * What this binding stated about itself, which is fixed for its life.
     *
     * <p>What it stated, not what it resolved to: an absent override is still absent here, because the
     * value it defaults to belongs to the live configuration and changes under it.
     */
    public @NotNull BindingOptions options() {
        return this.options;
    }

    /** This model's physics, or {@code null} if it is not spawned. */
    public @Nullable ModelSimulation simulation() {
        return this.simulation;
    }

    /**
     * Where the region thread publishes the anchor's movement.
     *
     * <p>The same holder {@link #simulation()} reads, by construction: the simulation is built around
     * this one rather than allocating its own, which is what makes the sampler and the solver two ends
     * of one seam instead of two unrelated objects that happen to agree.
     */
    public @NotNull DriverHolder driver() {
        return this.driver;
    }

    /** Whether this handle has torn down and can no longer spawn. */
    public synchronized boolean isStopped() {
        return this.stopped;
    }

    /** The period the live sampler was dispatched at, or the last one if it is not running. */
    public synchronized long scheduledPeriodTicks() {
        return this.scheduledPeriodTicks;
    }

    @Override
    public @NotNull String toString() {
        return "RiggedModel[" + this.rig.getName() + " on " + this.anchor.describe()
                + ", alive=" + this.alive + "]";
    }
}
