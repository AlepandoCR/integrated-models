package pandastic.studio.models.api.vehicle;

import org.bukkit.entity.Mob;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.ai.AiController;
import pandastic.studio.models.api.ai.AiIntent;
import pandastic.studio.models.api.ai.AiPlan;
import pandastic.studio.models.api.ai.AiPoint;
import pandastic.studio.models.api.ai.CreatureAi;

import java.util.Objects;
import java.util.Optional;

/**
 * A {@link VehiclePilot} that steers from this plugin's own creature AI.
 *
 * <h2>The one thing this type exists to make impossible</h2>
 * {@link CreatureAi#install} needs a {@link Mob}. {@link VehicleBinder#create} takes an
 * {@link org.bukkit.entity.Entity}. Before this existed, a consumer who wanted an AI-driven vehicle had
 * to notice that for itself, and the way it found out was a {@link ClassCastException} at a cast it wrote
 * — or, worse, an {@code instanceof} that silently skipped the install and left a vehicle that simply
 * never moved.
 *
 * <p>So the requirement is in the <strong>type</strong>:
 * {@link VehicleBinder#createPiloted(CreatureAi, pandastic.studio.models.api.rig.Rig, VehicleProfile,
 * Mob, VehicleSetup, AiPlan)} takes a {@code Mob} and nothing else will compile. An
 * {@code ArmorStand} — which is a {@code LivingEntity} and is <em>not</em> a {@code Mob} — and an
 * {@code Interaction}, which is not even living, are both rejected by the compiler rather than at runtime.
 *
 * <h2>The three constraints, and the honest shape of the intersection</h2>
 * A vehicle root that can carry a plan has to satisfy three separate refusals, and two of them are
 * frequently described as being in tension. They are not, but the usable set is smaller than either side
 * suggests:
 *
 * <ol>
 *   <li><strong>It must be a {@code Mob}</strong>, because that is what a brain is installed on. This is
 *       the compile-time half.</li>
 *   <li><strong>Its species' own tick must tick its brain.</strong> Only about twenty do; on anything
 *       else the install succeeds, is correct, and is never asked a single question.
 *       {@link CreatureAi#supports(Mob)} is that check and {@link CreatureAi#supportedSpecies()} names
 *       them. Note what this rules out: a <em>zombie</em> is a {@code Mob} and a perfectly good
 *       server-authoritative vehicle root, and it is goal-driven, so it can never carry a plan.</li>
 *   <li><strong>It must not be a root the server would let a rider's client steer.</strong> Horses and
 *       everything reaching {@code AbstractHorse}, boats, saddled pigs, striders and happy ghasts all
 *       hand control to their first passenger, and {@link VehicleBinder#create} refuses them. Two of
 *       them are also brain-driven — a <strong>camel</strong> is an {@code AbstractHorse} and a
 *       <strong>happy ghast</strong> is rideable by several players at once — so they pass check 2 and
 *       fail check 3.</li>
 * </ol>
 *
 * <p>The intersection is therefore every brain-driven species <em>except</em> camels and happy ghasts,
 * and it is not empty: a silenced, invulnerable goat, villager, armadillo, allay, warden, sniffer,
 * creaking, piglin, hoglin, axolotl or frog is a {@code Mob}, is brain-driven, and is refused by nothing.
 * A goat is the one this plugin's sibling project already uses as a modelled creature.
 *
 * <p>Which is to say the answer to "is a {@code Mob} root safe from the client-authority problem" is
 * <strong>no, not automatically</strong> — four of the six client-steered families are mobs. The safe
 * statement is the one above: the authority refusal is asked by type at creation and again
 * authoritatively at mount time, and it does not care whether the root is a mob.
 *
 * <h2>The plan must clear the vanilla goals, and that is not advice</h2>
 * {@link AiPlan#clearsVanillaGoals()} defaults to {@code true} and must stay {@code true} for a vehicle
 * root. A brain-driven species still has goal selectors — a goat panics, a villager strolls — and a goal
 * that navigates writes the entity's own movement from the creature's tick, which is the second writer to
 * the one position this whole design keeps single. {@code createPiloted} refuses a plan that does not
 * clear them, naming the fix.
 *
 * <h2>Why this reads an intent rather than driving the mob</h2>
 * Because the sibling project's intent driver — read {@link AiController#intent()} on the mob's own
 * scheduler and call {@code mob.getPathfinder().moveTo(...)} — is correct for a plain creature and is
 * exactly wrong for a vehicle root. It would make the navigation and the control step two writers to one
 * position within one tick, and it would discard the profile: the vehicle would travel at the mob's
 * movement-speed attribute, turn as fast as the navigation liked, and ignore the friction, the boost, the
 * teleport threshold and the terrain ceilings. So for a vehicle that driver is not merely unnecessary — it
 * is the thing that must not exist. The vehicle's own control step is the intent driver, and it executes
 * the intent through {@link PilotSteering} and {@link VehicleSolver} instead of through the pathfinder.
 *
 * <p>The handover needs no synchronisation beyond the volatile reference the AI layer already publishes
 * through, and in this one arrangement it needs even less than that: because the root entity <em>is</em>
 * the mob, the behaviours that decide and the control step that acts run on the same region thread.
 *
 * <h2>What is deliberately dropped from the intent</h2>
 * {@link AiIntent#lookPoint()} is not applied. A root entity's yaw is the vehicle's heading, written by
 * the control step from the solver's answer; calling {@code lookAt} would be a second writer to it, and
 * the visible result is a model whose body points somewhere its motion does not. A consumer that wants a
 * head or a turret to track the look point has it: read {@link #controller()}'s intent from a
 * {@link VehicleController} and move the bone, which is a rotation and not a position.
 *
 * <p>{@link AiIntent#moveSpeed()} is not applied either, and that is the one real loss. The vehicle's
 * speed is its profile's; a multiplier on a creature's movement-speed attribute has no meaning for a
 * vehicle that does not use the attribute. A behaviour that wants a slower approach has to ask for a
 * nearer destination, or the consumer has to build two profiles.
 *
 * <h2>Single use, and who uninstalls</h2>
 * A pilot drives one vehicle and is closed with it. Whether closing <em>uninstalls the plan</em> depends
 * on which factory built it, and that distinction is explicit rather than guessed:
 * {@link #owning(AiController)} and {@link #pending()} release the installation on
 * {@link #onClosed(Vehicle)}, {@link #borrowing(AiController)} leaves it alone because the caller holds
 * it for something else.
 *
 * <h2>Nothing here is tunable</h2>
 * Not one number. The destination comes from a behaviour, every threshold is derived by
 * {@link PilotSteering} from the vehicle's own profile and the measured step, and the AI layer's bounds
 * are read live from {@code ai.yml} by {@link CreatureAi#getBounds()}.
 */
public final class AiPilot implements VehiclePilot {

    /**
     * Whether closing this pilot uninstalls the plan.
     *
     * <p>Final, because it is a statement about who the installation belongs to and that cannot change
     * part-way through a vehicle's life without one side leaking it.
     */
    private final boolean owning;

    /**
     * The installation, or {@code null} until one arrives.
     *
     * <p>Volatile because a {@link #pending()} pilot is attached from whichever thread the install's
     * future completes on — the creature's own, which in this arrangement is also the thread that steps
     * the vehicle, but the field is published rather than assumed so that a consumer attaching from
     * elsewhere is correct too. One store of one reference, which is the same shape every hand-over in
     * this project uses.
     */
    private volatile @Nullable AiController controller;

    private AiPilot(@Nullable AiController controller, boolean owning) {
        this.controller = controller;
        this.owning = owning;
    }

    /**
     * A pilot over an installation the <strong>caller</strong> keeps.
     *
     * <p>Closing the vehicle does not uninstall the plan, so the creature carries on being driven by it
     * afterwards. For a consumer that installed the plan for its own reasons and is lending its decisions
     * to a vehicle.
     *
     * @param controller the installation to steer from
     * @return the pilot
     */
    @Contract(value = "_ -> new", pure = true)
    public static @NotNull AiPilot borrowing(@NotNull AiController controller) {
        return new AiPilot(Objects.requireNonNull(controller, "controller"), false);
    }

    /**
     * A pilot that <strong>owns</strong> an installation and uninstalls it when the vehicle is torn down.
     *
     * <p>The right factory for a consumer whose plan exists only to drive this vehicle, because it makes
     * the plan's lifetime the vehicle's. An entity-scheduled sensor task is never cancelled for a plugin
     * on disable, so a plan nobody uninstalls outlives the classloader.
     *
     * @param controller the installation to steer from and release
     * @return the pilot
     */
    @Contract(value = "_ -> new", pure = true)
    public static @NotNull AiPilot owning(@NotNull AiController controller) {
        return new AiPilot(Objects.requireNonNull(controller, "controller"), true);
    }

    /**
     * An owning pilot whose installation has not arrived yet.
     *
     * <h2>Why a pilot can exist before its plan does</h2>
     * Because the order has to be creation first, install second, and nothing else is safe. Creating the
     * vehicle is what refuses a client-steered root, a profile outside the operator's bounds, a call from
     * the wrong thread and a rig whose bones no longer resolve. Installing first and creating second would
     * leave a plan running on a creature that turned out not to be allowed to be a vehicle at all — a
     * brain rebuilt and sensors scheduled for nothing, which the consumer has no handle to and would never
     * hear about.
     *
     * <p>So {@link VehicleBinder#createPiloted} builds one of these, creates the vehicle with it, and
     * attaches the installation when the asynchronous install completes. Between those two moments the
     * pilot asks for {@link RiderIntent#NONE} and the vehicle sits still, which is a real state of a few
     * ticks and the honest one: nothing has decided anything yet.
     *
     * @return the pilot, waiting for {@link #attach(AiController)}
     */
    @Contract(value = "-> new", pure = true)
    public static @NotNull AiPilot pending() {
        return new AiPilot(null, true);
    }

    /**
     * Supplies the installation a {@link #pending()} pilot was waiting for.
     *
     * <p>Once, ever. A second attach is refused rather than accepted, because the first installation would
     * then be unreachable and unreleasable — the pilot is what was going to uninstall it.
     *
     * @param installed the installation to steer from
     * @throws IllegalStateException if this pilot already has one
     */
    public void attach(@NotNull AiController installed) {
        Objects.requireNonNull(installed, "installed");
        final AiController existing = this.controller;
        if (existing != null) {
            throw new IllegalStateException("This pilot is already steering from plan '"
                    + existing.getPlan().getName() + "' on " + existing.getMob().getUniqueId()
                    + ", so attaching plan '" + installed.getPlan().getName() + "' would leave the first"
                    + " installation with nothing holding it and nothing able to uninstall it. Build a"
                    + " second pilot, or uninstall the first plan yourself and use AiPilot.borrowing.");
        }
        this.controller = installed;
    }

    /**
     * The installation this pilot is steering from, if one has arrived.
     *
     * <p>Safe from any thread, and the designed way for a {@link VehicleController} on the same vehicle to
     * reach the parts of the decision this pilot deliberately drops — the look point, the move speed.
     *
     * @return the installation, or empty while a {@link #pending()} pilot is waiting for one
     */
    public @NotNull Optional<AiController> controller() {
        return Optional.ofNullable(this.controller);
    }

    /** Whether this pilot releases its installation when the vehicle is torn down. */
    public boolean isOwning() {
        return this.owning;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Reads the published decision once — one volatile read of one immutable value, which is the whole
     * of the cross-thread contract the AI layer offers — and turns its move point into keys. Asks for
     * nothing at all when there is no installation yet, when the plan has been uninstalled, and when the
     * current decision has no move target, which is a behaviour handing movement back rather than an
     * absence of one.
     */
    @Override
    public @NotNull RiderIntent steer(@NotNull PilotContext context) {
        Objects.requireNonNull(context, "context");
        final AiController installed = this.controller;
        if (installed == null || !installed.isInstalled()) {
            return RiderIntent.NONE;
        }
        // One read, used whole. Reading intent() twice inside one step could straddle a publication and
        // let the throttle and the heading answer two different decisions — the same hazard the control
        // step's own single read of the rider's input avoids.
        final AiIntent decision = installed.intent();
        final AiPoint destination = decision.movePoint();
        if (destination == null) {
            return RiderIntent.NONE;
        }
        // The height is dropped rather than approximated: there is no key the solver would read it from.
        return PilotSteering.toward(context, destination.x(), destination.z());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Uninstalls the plan when this pilot owns it, and otherwise does nothing.
     * {@link AiController#uninstall()} is documented safe from any thread and any number of times, which
     * is exactly what this hook needs — it carries no thread promise, because one of the three things
     * that can tear a vehicle down is the platform's retired callback.
     */
    @Override
    public void onClosed(@NotNull Vehicle vehicle) {
        Objects.requireNonNull(vehicle, "vehicle");
        final AiController installed = this.controller;
        if (this.owning && installed != null) {
            installed.uninstall();
        }
    }

    @Override
    public @NotNull String toString() {
        final AiController installed = this.controller;
        return "AiPilot[" + (installed == null ? "pending" : installed.getPlan().getName())
                + ", owning=" + this.owning + "]";
    }
}
