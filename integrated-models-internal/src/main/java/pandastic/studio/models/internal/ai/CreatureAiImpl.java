package pandastic.studio.models.internal.ai;

import org.bukkit.entity.Mob;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.ai.AiBounds;
import pandastic.studio.models.api.ai.AiContext;
import pandastic.studio.models.api.ai.AiController;
import pandastic.studio.models.api.ai.AiMemoryView;
import pandastic.studio.models.api.ai.AiPlan;
import pandastic.studio.models.api.ai.CreatureAi;
import pandastic.studio.models.internal.nms.BrainBridge;
import pandastic.studio.models.internal.nms.BrainInstallRequest;
import pandastic.studio.models.internal.nms.BrainInstallation;
import pandastic.studio.models.internal.nms.MemoryStore;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Installs plans, and is the only thing that decides which thread an install runs on.
 *
 * <h2>Forcing the thread is this class's main job</h2>
 * Registering behaviour on a creature is the one operation in this area with <strong>no thread checking
 * at all</strong>. The goal selector holds a plain set, the brain's maps are plain maps, and the
 * platform's own goal API checks nothing — so a registration from a foreign thread does not throw. It
 * interleaves with the creature's tick and corrupts whichever structure it landed in, and there is no
 * mode that would have caught it.
 *
 * <p>That makes documenting the rule insufficient. {@link #install} therefore never touches the
 * creature on its caller's thread, whatever thread that is: it validates what can be validated purely,
 * then dispatches the whole touching part through the creature's own scheduler and hands back a future
 * that completes there. A caller on a region thread, a pool thread, a load routine or a command all get
 * the same guarantee, and none of them has to know the rule exists.
 *
 * <p>The creature's <em>own</em> scheduler and not its region's: an entity scheduler follows its entity
 * across region and world changes, while a region-scheduled task is pinned to the region it was posted
 * from and would run on the wrong thread the moment the creature walked over a boundary between the
 * post and the run.
 *
 * <h2>Why validation happens before the dispatch and throws</h2>
 * A plan that does not fit the bounds is a programming error, and a programming error that arrives one
 * callback later is one whose stack trace no longer contains the code that caused it. So the plan is
 * checked against the live bounds on the calling thread and throws there. Everything that can only fail
 * on the creature's thread — a creature that has since died, a species that cannot be given a brain, a
 * server whose internals this build cannot reach — completes the future exceptionally instead, because
 * those are conditions rather than mistakes.
 *
 * <h2>One plan per creature</h2>
 * A second install on a creature that already has one uninstalls the first, on the creature's own thread
 * and immediately before installing the second, so there is never a moment with two plans driving one
 * brain. Tracking is by the creature's identity rather than by the controller's, because the creature is
 * what cannot have two.
 *
 * <h2>Absence of an adapter is a supported state</h2>
 * The AI layer needs the NMS seam; the rest of the plugin does not. A server running a version no
 * shipped adapter was built for therefore has a working rendering pipeline and no AI, and this class
 * reports that as a failed future naming the reason rather than refusing to exist. Degrade, say so once,
 * keep rendering.
 */
public final class CreatureAiImpl implements CreatureAi {

    private final @NotNull BrainBridge brains;
    private final @NotNull AiSettingsSource settings;
    private final @NotNull ThrottledFaultSink faults;
    private final @NotNull Function<Mob, AiHost> hosts;

    /**
     * Live installations, keyed by the creature they drive.
     *
     * <p>Concurrent because installs arrive on whichever region owns the creature and teardowns arrive
     * from anywhere, including a plugin shutting down. Entries are removed by the controller's own
     * stopped callback, so a creature that retired does not keep one alive.
     */
    private final @NotNull Map<UUID, AiControllerImpl> installations = new ConcurrentHashMap<>();

    /**
     * @param brains   the seam that rebuilds a brain
     * @param settings where the bounds and the fault policy come from, read live
     * @param faults   where a body's exception goes; shared across every installation, because the
     *                 throttle is per body name rather than per creature and a creature-local one would
     *                 let a thousand creatures with the same broken behaviour produce a thousand lines
     * @param hosts    how to build the per-creature thread seam. Injected rather than constructed so
     *                 that every ordering decision in this class is testable without a server
     */
    public CreatureAiImpl(
            @NotNull BrainBridge brains,
            @NotNull AiSettingsSource settings,
            @NotNull ThrottledFaultSink faults,
            @NotNull Function<Mob, AiHost> hosts
    ) {
        this.brains = Objects.requireNonNull(brains, "brains");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.faults = Objects.requireNonNull(faults, "faults");
        this.hosts = Objects.requireNonNull(hosts, "hosts");
    }

    @Override
    public boolean supports(@NotNull Mob mob) {
        Objects.requireNonNull(mob, "mob");
        return this.brains.supports(mob);
    }

    @Override
    public @NotNull List<String> supportedSpecies() {
        return this.brains.supportedSpecies();
    }

    @Override
    public @NotNull AiBounds getBounds() {
        return this.settings.current().bounds();
    }

    @Override
    public @NotNull CompletableFuture<AiController> install(@NotNull AiPlan plan, @NotNull Mob mob) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(mob, "mob");

        final AiSettings current = this.settings.current();
        // Re-validated against the live bounds rather than trusted, because a plan is built once and may
        // be installed much later — after a reload that tightened something. The check is a handful of
        // comparisons and the alternative is a plan that passes a bound the operator has since removed.
        plan.requireWithin(current.bounds());

        final AiHost host = this.hosts.apply(mob);

        if (!this.brains.supports(mob)) {
            // Thrown rather than returned, because it is a property of the creature's type that the
            // caller could have asked about with supports(Mob) and that is not going to change.
            throw new IllegalArgumentException("Plan '" + plan.getName() + "' cannot be installed on "
                    + host.describe() + ": only a creature whose own tick ticks its brain will ever be"
                    + " asked anything by a plan, and on anything else the install would succeed and"
                    + " have no effect at all. The species that work are: "
                    + String.join(", ", this.brains.supportedSpecies()));
        }

        final CompletableFuture<AiController> installed = new CompletableFuture<>();

        // Everything past this point touches the creature, so none of it runs here.
        if (!host.submit(() -> installOnOwningThread(plan, host, current, installed))) {
            installed.completeExceptionally(new IllegalStateException("Plan '" + plan.getName()
                    + "' cannot be installed on " + host.describe()
                    + ": the creature has been removed, so its scheduler will never run anything again"));
        }
        return installed;
    }

    /**
     * The install itself, on the creature's own thread.
     *
     * <p>Completes {@code installed} exactly once on every path. A future left incomplete by a thrown
     * exception would hang whatever was waiting on it with nothing to point at, so the whole body is
     * wrapped — including the parts that "cannot" throw, because the one that eventually does will be
     * below the NMS seam where this build cannot see it.
     */
    private void installOnOwningThread(
            @NotNull AiPlan plan,
            @NotNull AiHost host,
            @NotNull AiSettings settings,
            @NotNull CompletableFuture<AiController> installed
    ) {
        try {
            if (!host.isAlive()) {
                // A race a caller cannot avoid: the creature can die between the dispatch and the run.
                throw new IllegalStateException("Plan '" + plan.getName() + "' cannot be installed on "
                        + host.describe() + ": the creature was gone by the time the install ran");
            }

            // Uninstalled before the replacement is built, not after, so there is never a moment with
            // two plans on one brain. On this thread, so the order is the order it appears in.
            final AiControllerImpl previous = this.installations.remove(host.creatureId());
            if (previous != null) {
                previous.uninstall();
            }

            final IntentHolder intents = new IntentHolder();
            final AiBounds bounds = settings.bounds();

            // The context's memory half only exists once the brain has been rebuilt, and its rules only
            // exist up here. The factory closes over the rules and is called with the store; see
            // AiContextFactory for why the seam calls back rather than building one.
            final BrainInstallation installation = this.brains.install(new BrainInstallRequest(
                    host.mob(),
                    plan,
                    store -> context(plan, host, store, intents, bounds),
                    this.faults));

            final AiMemoryView memories =
                    new GuardedMemoryView(plan, installation.memories(), host, bounds);
            final AiContext context = new AiContextImpl(host, memories, intents, bounds);

            final AiControllerImpl controller = new AiControllerImpl(plan, host, installation, memories,
                    intents, context, this.faults, this::forget);
            this.installations.put(host.creatureId(), controller);

            if (!controller.startSensors()) {
                // startSensors() has already torn the installation down, which has already removed the
                // map entry through forget(). Nothing to unwind here beyond saying why.
                throw new IllegalStateException("Plan '" + plan.getName() + "' was installed on "
                        + host.describe() + " but the creature retired before its sensors could be"
                        + " scheduled, so the installation was rolled back");
            }
            installed.complete(controller);
        } catch (final RuntimeException | LinkageError failure) {
            // LinkageError as well as RuntimeException: an adapter compiled against a different server
            // fails as a NoSuchMethodError on its first real call, and that call is in here. Letting it
            // escape would leave the future hanging and the exception swallowed by the scheduler.
            installed.completeExceptionally(failure);
        }
    }

    /**
     * Builds the context handed to behaviour bodies.
     *
     * <p>A separate method rather than a lambda body so that the context a behaviour gets and the
     * context a sensor gets are visibly built the same way. They are two objects rather than one because
     * the behaviour side's store comes from inside the install and the sensor side's comes from the
     * finished installation — the same store either way, reached one step apart.
     */
    private static @NotNull AiContext context(
            @NotNull AiPlan plan,
            @NotNull AiHost host,
            @NotNull MemoryStore store,
            @NotNull IntentHolder intents,
            @NotNull AiBounds bounds
    ) {
        return new AiContextImpl(host, new GuardedMemoryView(plan, store, host, bounds), intents,
                bounds);
    }

    /**
     * The controller for {@code mob}, or {@code null}.
     *
     * <p>Not part of the api: a plugin holds the controller its own install returned. This is for the
     * plugin's own teardown, which has to reach every live installation without having kept a list.
     *
     * @param creatureId the creature's identity, as an AiHost reports it
     * @return its controller, or {@code null} if it has none
     */
    public @Nullable AiControllerImpl installationFor(@NotNull UUID creatureId) {
        return this.installations.get(creatureId);
    }

    /** How many installations are live. For tests and for a diagnostic command. */
    public int installationCount() {
        return this.installations.size();
    }

    /**
     * Uninstalls every live plan.
     *
     * <p>Called from the plugin's shutdown, from a thread that owns nothing. Each controller's teardown
     * cancels its own sensor tasks synchronously and defers only the brain restoration, so this returns
     * with nothing of ours still scheduled — which is the property that matters, because a repeating
     * task dispatched through an entity scheduler is never cancelled for us.
     *
     * @return how many were uninstalled
     */
    public int uninstallAll() {
        int uninstalled = 0;
        // A copy, because each uninstall removes its own entry through forget() and the map's iterator
        // is weakly consistent rather than snapshot — iterating it while it shrinks is legal and makes
        // the count depend on timing.
        for (final AiControllerImpl controller : List.copyOf(this.installations.values())) {
            controller.uninstall();
            uninstalled++;
        }
        this.installations.clear();
        return uninstalled;
    }

    /**
     * Drops a controller that has finished tearing down.
     *
     * <p>Keyed by the creature, and removed by identity: a creature that was reinstalled holds a
     * different controller under the same key, and the old one's late teardown must not evict it.
     */
    private void forget(@NotNull AiControllerImpl controller) {
        this.installations.remove(controller.creatureId(), controller);
    }
}
