package pandastic.studio.models.internal.ai;

import org.bukkit.entity.Mob;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.ai.AiContext;
import pandastic.studio.models.api.ai.AiController;
import pandastic.studio.models.api.ai.AiIntent;
import pandastic.studio.models.api.ai.AiMemoryView;
import pandastic.studio.models.api.ai.AiPlan;
import pandastic.studio.models.api.ai.AiSensor;
import pandastic.studio.models.api.ai.AiSensorBinding;
import pandastic.studio.models.internal.nms.AiFaultSink;
import pandastic.studio.models.internal.nms.BrainInstallation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * One plan installed on one creature: the brain, the sensors' tasks, and the one teardown.
 *
 * <h2>Start order, and why each step is where it is</h2>
 * <ol>
 *   <li><strong>The brain is already installed</strong> when this object is constructed. That is
 *       deliberate: installing is the step most likely to fail and the cheapest to unwind, because
 *       nothing is scheduled yet, so it happens before there is a controller to leak.</li>
 *   <li><strong>Schedule the sensors.</strong> Last, because a sensor is the only thing here that can
 *       tear the installation down from inside its own body, and it must not be able to do that to
 *       state that does not exist yet.</li>
 * </ol>
 *
 * <p>A sensor that cannot be scheduled means the creature retired between the install and this call.
 * Neither its body nor its retired callback will ever run, so nothing would ever tear the installation
 * down — which is why that case unwinds immediately rather than carrying on with one sensor missing.
 *
 * <h2>Teardown order: stop everything that can drive the creature, then release what it was driving</h2>
 * <ol>
 *   <li><strong>Cancel the sensors.</strong> They are the only thing here that writes memories from
 *       outside the brain's own tick. A body already in flight is harmless — it writes this creature's
 *       memories and nothing reads them afterwards — so cancelling does not wait for one.</li>
 *   <li><strong>Clear the intent.</strong> Now nothing can act on a decision made by a plan that is no
 *       longer installed, including a reader on the simulation thread that had already picked up the
 *       reference.</li>
 *   <li><strong>Restore the brain</strong>, on a thread allowed to touch the creature. Last, so no live
 *       sensor can ever observe a restored brain and write a memory into it.</li>
 * </ol>
 *
 * <h2>Stopping exactly once, from whichever side notices first</h2>
 * Three things can decide an installation is finished and they can decide it simultaneously: the
 * platform's retired callback, a sensor finding the creature gone, and a caller — usually a plugin
 * shutting down or installing a replacement plan. All three go through one monitor and one latch, so the
 * teardown body runs once and the rest return immediately.
 *
 * <p>The retired path is the one exception to step 3, and it is the platform's rule rather than ours: a
 * retired callback is critical code and there is no thread left to defer to, because the scheduler that
 * would have run the deferred work is the one that has just retired. A creature that has been removed
 * has nothing left to restore a brain onto, so that path performs steps 1 and 2 and stops.
 *
 * <h2>Why the sensor tasks are retained rather than left to the platform</h2>
 * Because the platform will not cancel them. A repeating task dispatched through an entity's scheduler
 * survives plugin disable — unlike one on the main scheduler, which is swept — and keeps running after
 * the plugin's classloader has been closed, failing on the first lazy class load after that: during
 * shutdown, pointing nowhere near the creature that leaked it.
 */
public final class AiControllerImpl implements AiController {

    private final @NotNull UUID uniqueIdentifier = UUID.randomUUID();

    /**
     * The creature's own identity, captured at construction — on the owning thread — rather than read
     * when needed.
     *
     * <p>The teardown runs from any thread, including a plugin shutting down and the platform's retired
     * callback, and it has to tell the service which creature it belonged to. Reaching through the
     * creature to ask would be a read of a platform object from a thread that may not own it, on the one
     * path where the creature is most likely to have just been removed. The identity never changes, so
     * copying it once removes the question.
     */
    private final @NotNull UUID creatureId;

    private final @NotNull AiPlan plan;
    private final @NotNull AiHost host;
    private final @NotNull BrainInstallation installation;
    private final @NotNull AiMemoryView memories;
    private final @NotNull IntentHolder intents;
    private final @NotNull AiContext context;
    private final @NotNull ThrottledFaultSink faults;

    /** Told once, when this installation has finished tearing down. See {@link CreatureAiImpl}. */
    private final @NotNull Consumer<AiControllerImpl> onStopped;

    /** Guarded by {@code this}: only the lifecycle methods touch these. */
    private final @NotNull List<AiTask> sensorTasks = new ArrayList<>();

    /** The single-use latch. Guarded by {@code this}; see "Stopping exactly once". */
    private boolean stopped;

    private volatile boolean installed;

    /**
     * @param plan         what was installed
     * @param host         the creature and the thread rules around it
     * @param installation the live brain, already installed
     * @param memories     the guarded view over the installation's store
     * @param intents      where behaviours publish and anything else reads
     * @param context      the object sensor bodies are handed; the same one the adapter hands behaviours
     * @param faults       where a sensor body's exception goes, and who decides whether to keep running it
     * @param onStopped    told once when this installation has torn down
     */
    public AiControllerImpl(
            @NotNull AiPlan plan,
            @NotNull AiHost host,
            @NotNull BrainInstallation installation,
            @NotNull AiMemoryView memories,
            @NotNull IntentHolder intents,
            @NotNull AiContext context,
            @NotNull ThrottledFaultSink faults,
            @NotNull Consumer<AiControllerImpl> onStopped
    ) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.host = Objects.requireNonNull(host, "host");
        this.installation = Objects.requireNonNull(installation, "installation");
        this.memories = Objects.requireNonNull(memories, "memories");
        this.intents = Objects.requireNonNull(intents, "intents");
        this.context = Objects.requireNonNull(context, "context");
        this.faults = Objects.requireNonNull(faults, "faults");
        this.onStopped = Objects.requireNonNull(onStopped, "onStopped");
        this.creatureId = host.creatureId();
    }

    /**
     * The creature's identity, as captured when this controller was built.
     *
     * <p>Safe from any thread, which is what the teardown path needs; see {@link #creatureId}.
     *
     * @return the creature's unique identifier
     */
    public @NotNull UUID creatureId() {
        return this.creatureId;
    }

    /**
     * Schedules the plan's sensors and marks the installation live.
     *
     * <p>Separate from the constructor so that a failure to schedule unwinds through the ordinary
     * teardown rather than leaving a half-built object to a caller holding a reference to it.
     *
     * <p>Must run on the thread that owns the creature, which it does by construction: the only caller
     * is the install, which the service already forced onto that thread.
     *
     * @return {@code true} if the installation is now live, {@code false} if the creature retired while
     *         the sensors were being scheduled — a race a caller cannot avoid, which is why it is a
     *         return value and not an exception
     */
    public synchronized boolean startSensors() {
        if (this.stopped) {
            return false;
        }
        for (final AiSensorBinding binding : this.plan.getSensors()) {
            final AiSensor sensor = binding.sensor();
            final AiTask task = this.host.repeat(
                    () -> runSensor(sensor),
                    this::onCreatureRetired,
                    binding.periodTicks());
            if (task == null) {
                // The creature retired between the brain install and now. Neither callback of any
                // further sensor would run either, so nothing would ever tear this down.
                stop(false);
                return false;
            }
            this.sensorTasks.add(task);
        }
        this.installed = true;
        return true;
    }

    @Override
    public @NotNull AiPlan getPlan() {
        return this.plan;
    }

    @Override
    public @NotNull Mob getMob() {
        return this.host.mob();
    }

    @Override
    public @NotNull UUID getUniqueIdentifier() {
        return this.uniqueIdentifier;
    }

    @Override
    public @NotNull AiMemoryView memories() {
        return this.memories;
    }

    @Override
    public @NotNull AiIntent intent() {
        return this.intents.current();
    }

    @Override
    public boolean isInstalled() {
        return this.installed;
    }

    @Override
    public void uninstall() {
        stop(false);
    }

    /** Whether this handle has torn down. */
    public synchronized boolean isStopped() {
        return this.stopped;
    }

    /** How many sensor tasks are live. For tests and for a diagnostic command. */
    public synchronized int liveSensorCount() {
        return this.sensorTasks.size();
    }

    @Override
    public @NotNull String toString() {
        return "AiController[" + this.plan.getName() + " on " + this.host.describe()
                + ", installed=" + this.installed + "]";
    }

    /**
     * One sensor run, with the body's exceptions contained.
     *
     * <p>A sensor body runs on a region thread. An exception escaping it is swallowed by the platform
     * and the task is <strong>not</strong> cancelled, so the same failure repeats every period
     * indefinitely — which is why the catch is here rather than being left to the platform, and why a
     * body that keeps failing has its task cancelled.
     *
     * <p>{@link Throwable} rather than {@link Exception}: a body that throws an {@link Error} — a
     * {@link NoSuchMethodError} from a plugin compiled against a different version, most plausibly — is
     * exactly as repeating and exactly as fatal to the creature's usefulness, and letting that one
     * through would make the containment conditional on which supertype the failure happened to have.
     */
    private void runSensor(@NotNull AiSensor sensor) {
        if (!this.installed) {
            // The task's own gate: a body dispatched before the teardown but running after it.
            return;
        }
        try {
            sensor.sense(this.context);
            this.faults.succeeded(AiFaultSink.SENSOR, sensor.name());
        } catch (final Throwable failure) {
            if (!this.faults.failed(AiFaultSink.SENSOR, sensor.name(), failure)) {
                cancelSensor(sensor);
            }
        }
    }

    /**
     * Cancels the one sensor that has failed too often, leaving the rest running.
     *
     * <p>Identified by position rather than by holding a map from sensor to task: the plan's sensor list
     * and the task list are built in the same order in {@link #startSensors()}, so the indices
     * correspond, and a plan has a handful of sensors at most. A map would be the same information with
     * a second thing to keep in step.
     */
    private synchronized void cancelSensor(@NotNull AiSensor sensor) {
        final List<AiSensorBinding> bindings = this.plan.getSensors();
        for (int index = 0; index < bindings.size() && index < this.sensorTasks.size(); index++) {
            if (bindings.get(index).sensor() != sensor) {
                continue;
            }
            // Safe from inside the body: cancelling a repeating task from its own execution is
            // supported and does not wait for that execution to finish.
            this.sensorTasks.get(index).cancel();
            return;
        }
    }

    /**
     * The platform telling us the creature is gone — the authoritative notification, and the only one
     * for a creature that was removed rather than merely killed.
     */
    private void onCreatureRetired() {
        stop(true);
    }

    /**
     * The one teardown, reached from every direction and run once.
     *
     * @param creatureRetired whether this is the platform's retired callback, which has no thread left
     *                        to restore a brain on; see "Stopping exactly once" in the class
     *                        documentation
     */
    private synchronized void stop(boolean creatureRetired) {
        if (this.stopped) {
            return;
        }
        this.stopped = true;
        this.installed = false;

        // 1. The sensors. Cancelled before anything else, because they are the only thing here that
        //    writes memories from outside the brain's own tick.
        for (final AiTask task : this.sensorTasks) {
            task.cancel();
        }
        this.sensorTasks.clear();

        // 2. The decision. Cleared so nothing acts on a plan that is no longer installed.
        this.intents.reset();

        // 3. The brain, on a thread allowed to touch the creature.
        restoreBrain(creatureRetired);

        this.onStopped.accept(this);
    }

    /**
     * Restores the creature's own brain on a thread allowed to do it, or declines.
     *
     * <p>Three cases, and the awkward one is real. On the owning thread — where a sensor's own teardown
     * and a well-behaved caller both run — the restore happens inline. Off it, the restore is handed to
     * the creature's scheduler, which is the only dispatcher that follows the creature if it has moved
     * region since. From a retired callback it cannot be done at all: the creature has been removed, so
     * there is nothing to restore a brain onto and no thread to do it from — the scheduler that would
     * have run the deferred work is the one that just retired.
     */
    private void restoreBrain(boolean creatureRetired) {
        if (creatureRetired) {
            return;
        }
        if (this.host.isOwnedByCurrentThread()) {
            this.installation.uninstall();
            return;
        }
        // A false answer means the creature went between the decision to tear down and the dispatch,
        // which has the same outcome as the retired path: there is nothing left to restore a brain
        // onto, and the installation's own store stops being reachable with this object.
        this.host.submit(this.installation::uninstall);
    }
}
