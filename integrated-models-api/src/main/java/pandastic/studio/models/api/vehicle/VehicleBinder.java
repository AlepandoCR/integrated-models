package pandastic.studio.models.api.vehicle;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.ai.AiController;
import pandastic.studio.models.api.ai.AiPlan;
import pandastic.studio.models.api.ai.CreatureAi;
import pandastic.studio.models.api.rig.Rig;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * How a consumer says "make this entity a vehicle that drives like this and looks like that".
 *
 * <p>The one entry point into the vehicle layer, and the only type in it that names a platform object
 * besides the rider.
 *
 * <h2>The caller supplies the root entity, on purpose</h2>
 * Three reasons, and the first is the important one.
 *
 * <ol>
 *   <li><strong>The choice of entity type decides who is authoritative.</strong> The server hands
 *       control of a vehicle to its rider's client whenever the root entity's own class says the first
 *       passenger is its controlling passenger — which is what a boat, a saddled pig, a horse, a camel
 *       and a strider all say. Mount a rider on one of those and the client simulates the vehicle and
 *       the server merely checks its homework; every position this layer writes is then fighting a
 *       prediction that has already been drawn. That is not a thing a plugin can opt out of per entity,
 *       so the only correct answer is to <em>pick a different root</em>, and the only place that
 *       decision can be made is where the entity is spawned. {@link #create} checks it and refuses, so
 *       a wrong choice fails at creation with a sentence rather than at drive time with rubber-banding.</li>
 *   <li>The root's type decides its hitbox, its gravity, its collision and whether it steps up a slab —
 *       all of which this layer deliberately leaves to the server's own entity physics rather than
 *       reimplementing badly.</li>
 *   <li>The root's lifetime is the caller's. A vehicle despawned at shutdown should leave an entity
 *       behind that the next start can pick up, which is only possible if this layer never created it.</li>
 * </ol>
 *
 * <h2>Creating validates; it does not drive</h2>
 * {@link #create} resolves the rig against the model, refuses a client-controlled root, spawns the visual
 * and starts the control loop, all on the root entity's own thread. It is therefore the one call in this
 * interface with a thread rule, and the rule is the same one the whole project has: touch an entity only
 * from the thread that owns it, because on a regionised server doing otherwise is undefined rather than
 * merely racy, and nothing will tell you.
 */
public interface VehicleBinder {

    /**
     * Makes {@code root} a vehicle driving {@code rig} under {@code profile}.
     *
     * <p>Must be called from the thread that owns {@code root}. In practice that is the body of a task on
     * its own scheduler, or an event handler for it.
     *
     * @param rig     the rig to render on it, as loaded from this plugin's rig directory
     * @param profile how it drives, already validated against the server's bounds
     * @param root    the entity carrying the hitbox, the collision and the replicated position. Must not
     *                be a type whose own class makes its first passenger the controlling passenger; see
     *                the class documentation
     * @return the vehicle, already alive and being controlled — except in one case, which is the only
     *         thing a caller has to check for: if {@code root} vanished between this call and the control
     *         loop being dispatched, the handle comes back already despawned and
     *         {@link Vehicle#isAlive()} answers {@code false}. That is a race a caller cannot avoid and
     *         cannot act on, which is why it is a return value rather than an exception
     * @throws IllegalStateException if called off {@code root}'s owning thread; if {@code root} is a type
     *                               the server would let a rider's client simulate; if the renderer has
     *                               no model by the name the rig points at; or if the model is missing
     *                               any bone the rig names, in which case the message lists every
     *                               unresolved name rather than the first
     * @throws IllegalArgumentException if {@code profile} is outside the bounds the operator configured,
     *                                  naming the component and the bound
     */
    default @NotNull Vehicle create(
            @NotNull Rig rig,
            @NotNull VehicleProfile profile,
            @NotNull Entity root
    ) {
        return create(rig, profile, root, VehicleSetup.DEFAULT);
    }

    /**
     * The same thing, with the decisions that belong to one <em>kind</em> of vehicle.
     *
     * <p>{@link VehicleSetup} carries what this vehicle says to its riders, what each seat's inputs do,
     * and the one {@link VehicleController} that owns its behaviour. All three are the consumer's: a
     * plugin shipping a horse and a tank wants different words for "this is full", a different meaning
     * for the gunner's trigger, and a turret angle that only the tank has. None of them is validated
     * against the operator's bounds, because there is no server-side range for a sentence.
     *
     * <p>Everything the three-argument overload says about threads, refusals and the one race applies
     * unchanged.
     *
     * @param rig     the rig to render on it
     * @param profile how it drives
     * @param root    the entity carrying the hitbox, the collision and the replicated position
     * @param setup   the messages, the per-seat controls and the controller.
     *                {@link VehicleSetup#DEFAULT} is this plugin's own defaults, no per-seat controls
     *                and {@link VehicleController#NONE}
     * @return the vehicle
     * @throws IllegalStateException    as the three-argument overload
     * @throws IllegalArgumentException as the three-argument overload
     */
    default @NotNull Vehicle create(
            @NotNull Rig rig,
            @NotNull VehicleProfile profile,
            @NotNull Entity root,
            @NotNull VehicleSetup setup
    ) {
        return create(rig, profile, root, setup, VehiclePilot.NONE);
    }

    /**
     * The same thing, steered by something other than a rider when the driver's seat is empty.
     *
     * <h2>What a pilot is, and what it is not</h2>
     * A {@link VehiclePilot} supplies the {@link RiderIntent} the control step would otherwise read from
     * the driver's keys. It is <strong>not</strong> a second way to move the vehicle: it cannot write a
     * position, set a heading or teleport, so everything the layer guarantees a ridden vehicle holds
     * unchanged — the profile's speeds and rates, the step-size independence, the teleport threshold, the
     * terrain reaction and the rest fixed point are all downstream of the intent rather than beside it.
     * That is the whole reason the seam has this shape; see {@link VehiclePilot}.
     *
     * <p>A rider always wins. The pilot is asked only on the steps where
     * {@link SeatPlan#DRIVER_INDEX} is empty, so boarding an autonomous vehicle takes it over and leaving
     * it gives it back, with one decider at every moment.
     *
     * <p>{@link VehiclePilot#NONE} is the identity: a vehicle built with it behaves exactly as one built
     * through the four-argument overload, which is what that overload is now written in terms of.
     *
     * <p>Everything the three-argument overload says about threads, refusals and the one race applies
     * unchanged.
     *
     * @param rig     the rig to render on it
     * @param profile how it drives
     * @param root    the entity carrying the hitbox, the collision and the replicated position
     * @param setup   the messages, the per-seat controls and the controller
     * @param pilot   what steers it with nobody in the driver's seat. Fixed for the vehicle's life, for
     *                the reason the controller is: a vehicle whose decider could be swapped mid-drive
     *                would change what it was doing with nothing to tell anybody
     * @return the vehicle
     * @throws IllegalStateException    as the three-argument overload
     * @throws IllegalArgumentException as the three-argument overload
     */
    @NotNull Vehicle create(
            @NotNull Rig rig,
            @NotNull VehicleProfile profile,
            @NotNull Entity root,
            @NotNull VehicleSetup setup,
            @NotNull VehiclePilot pilot
    );

    /**
     * Makes {@code root} a vehicle driven by this plugin's own creature AI.
     *
     * <h2>Why the parameter is a {@link Mob} and the return is a future</h2>
     * A brain is installed on a {@code Mob} and on nothing else, so the requirement is in the signature
     * rather than in a cast a consumer has to discover: an {@code ArmorStand} is a {@code LivingEntity}
     * and not a {@code Mob}, an {@code Interaction} is not living at all, and both are compile errors here
     * instead of {@link ClassCastException}s later. The future is {@link CreatureAi#install}'s — installing
     * is only legal on the thread that owns the creature and that layer refuses to trust a caller to have
     * arranged it, so the touching part is dispatched and this completes there.
     *
     * <p>Do not {@code join()} it from the creature's own region: the task that would complete it is
     * queued behind the one waiting for it. Use {@code thenAccept}.
     *
     * <h2>The order, which is creation first</h2>
     * The vehicle is created before the plan is installed, and that is deliberate rather than convenient.
     * Creating is what refuses a client-steered root, a profile outside the operator's bounds, a call from
     * the wrong thread and a rig that no longer resolves; installing first would leave a rebuilt brain and
     * scheduled sensors on a creature that turned out not to be allowed to be a vehicle, with no handle to
     * reach them by. So those refusals throw <em>before</em> anything is installed, and the vehicle sits
     * still for the few ticks between its creation and the install completing — see
     * {@link AiPilot#pending()}.
     *
     * <p>If the install fails, the vehicle is <strong>despawned</strong> and the failure is handed on. A
     * consumer that asked for an AI-driven vehicle and cannot have one should not be left holding a rig
     * parked in the world that nothing will ever drive.
     *
     * <h2>What is refused here, and why each refusal is loud</h2>
     * <ol>
     *   <li><strong>A plan that does not clear the vanilla goals.</strong> A brain-driven species still has
     *       goal selectors, and a goal that navigates writes the root entity's own movement from the
     *       creature's tick — the second writer to the one position this design keeps single.</li>
     *   <li><strong>A species whose tick does not tick its brain.</strong> On anything else the install
     *       succeeds, is correct, and is asked nothing ever; the message names the species that work. A
     *       <em>zombie</em> is a perfectly good vehicle root and is one of the ones that cannot carry a
     *       plan.</li>
     *   <li><strong>A client-steered root</strong>, by the ordinary {@link #create} path. Note that being a
     *       {@code Mob} does not save a root from this: a camel and a happy ghast are both brain-driven
     *       <em>and</em> client-steered, so they pass the check above and fail this one.</li>
     * </ol>
     *
     * @param creatureAi the AI layer, as {@link pandastic.studio.models.api.IntegratedModels#getCreatureAi()}
     *                   hands it over. An {@link java.util.Optional} there rather than a field, because a
     *                   server with no adapter for its version has no AI layer at all — which is a
     *                   supported state and is why this takes the service rather than reaching for one
     * @param rig        the rig to render on it
     * @param profile    how it drives
     * @param root       the creature carrying the hitbox, the collision and the replicated position, and
     *                   the creature the plan is installed on. One entity, not two: the mob <em>is</em> the
     *                   root, which is what puts the behaviours that decide and the control step that acts
     *                   on the same region thread
     * @param setup      the messages, the per-seat controls and the controller
     * @param plan       what the creature should do
     * @return a future completing with the vehicle once the plan is driving it, or completing exceptionally
     *         with whatever the install failed on — in which case the vehicle has already been despawned
     * @throws IllegalArgumentException if {@code plan} does not clear the vanilla goals, if {@code plan}
     *                                  does not fit the live {@link CreatureAi#getBounds()}, or if
     *                                  {@code root}'s species cannot be given a brain
     * @throws IllegalStateException    everything {@link #create} throws, and before anything is installed
     */
    default @NotNull CompletableFuture<Vehicle> createPiloted(
            @NotNull CreatureAi creatureAi,
            @NotNull Rig rig,
            @NotNull VehicleProfile profile,
            @NotNull Mob root,
            @NotNull VehicleSetup setup,
            @NotNull AiPlan plan
    ) {
        Objects.requireNonNull(creatureAi, "creatureAi");
        Objects.requireNonNull(rig, "rig");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(setup, "setup");
        Objects.requireNonNull(plan, "plan");

        if (!plan.clearsVanillaGoals()) {
            throw new IllegalArgumentException("Plan '" + plan.getName() + "' cannot drive a vehicle on "
                    + root.getType() + " " + root.getUniqueId() + " because it leaves the vanilla goals in"
                    + " place: a goal that navigates writes the root entity's own movement from the"
                    + " creature's tick, while the vehicle's control step writes it from the same tick, and"
                    + " two writers to one position fight for ever with nothing to say which won. Build"
                    + " the plan with clearVanillaGoals(true), which is the default.");
        }
        if (!creatureAi.supports(root)) {
            throw new IllegalArgumentException("Plan '" + plan.getName() + "' cannot be installed on "
                    + root.getType() + " " + root.getUniqueId() + ", so it cannot drive a vehicle on it:"
                    + " only a creature whose own tick ticks its brain is ever asked anything by a plan,"
                    + " and on anything else the install would succeed and have no effect at all. The"
                    + " species that work are: " + String.join(", ", creatureAi.supportedSpecies())
                    + ". Of those, a Camel and a HappyGhast are additionally refused as vehicle roots,"
                    + " because the server hands their movement to their rider's client.");
        }
        // Against the live bounds, here as well as inside the install, because the install throws this one
        // on its caller's thread rather than returning it — and by then a vehicle would exist. Nothing is
        // duplicated that could disagree: both calls ask the same plan about the same live bounds.
        plan.requireWithin(creatureAi.getBounds());

        // The vehicle first, so every refusal that belongs to being a vehicle throws before a brain has
        // been rebuilt or a sensor scheduled. The pilot is attached afterwards; see AiPilot.pending().
        final AiPilot pilot = AiPilot.pending();
        final Vehicle vehicle = create(rig, profile, root, setup, pilot);
        if (!vehicle.isAlive()) {
            // The root vanished between the checks and the dispatch — the one race a caller cannot avoid.
            // No plan is installed, because there is nothing left to install it on.
            return CompletableFuture.completedFuture(vehicle);
        }

        final CompletableFuture<AiController> installing;
        try {
            installing = creatureAi.install(plan, root);
        } catch (final RuntimeException refused) {
            // The install's own synchronous refusals — a plan outside the bounds, a species that cannot
            // carry one. The pre-checks above cover both, so reaching here means the live bounds moved
            // between them; either way the vehicle must not survive a plan that was never installed.
            vehicle.despawn();
            throw refused;
        }

        return installing
                .thenApply(controller -> {
                    pilot.attach(controller);
                    return vehicle;
                })
                .whenComplete((driven, failure) -> {
                    if (failure != null) {
                        vehicle.despawn();
                    }
                });
    }
}
