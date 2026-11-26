package pandastic.studio.models.internal.vehicle;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.ai.AiActivity;
import pandastic.studio.models.api.ai.AiActivityPlan;
import pandastic.studio.models.api.ai.AiBehaviour;
import pandastic.studio.models.api.ai.AiBounds;
import pandastic.studio.models.api.ai.AiContext;
import pandastic.studio.models.api.ai.AiController;
import pandastic.studio.models.api.ai.AiIntent;
import pandastic.studio.models.api.ai.AiMemoryView;
import pandastic.studio.models.api.ai.AiPlan;
import pandastic.studio.models.api.ai.AiPoint;
import pandastic.studio.models.api.ai.CreatureAi;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.api.vehicle.AiPilot;
import pandastic.studio.models.api.vehicle.PilotContext;
import pandastic.studio.models.api.vehicle.RiderIntent;
import pandastic.studio.models.api.vehicle.Vehicle;
import pandastic.studio.models.api.vehicle.VehicleBinder;
import pandastic.studio.models.api.vehicle.VehiclePilot;
import pandastic.studio.models.api.vehicle.VehicleProfile;
import pandastic.studio.models.api.vehicle.VehicleSetup;
import pandastic.studio.models.api.vehicle.VehicleState;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Giving a vehicle this plugin's creature AI: the attach path, the refusals, and the lifetime.
 *
 * <h2>Why this suite lives in the internal module</h2>
 * For the reason the AI suites do: every type under test is published api, but exercising it needs a
 * {@code Mob} reference and a vehicle binder, and this is the test source set with the platform on its
 * classpath and the vehicle fakes already in it. The subject is the api; the classpath is the internal
 * module's.
 *
 * <h2>What cannot be tested, and why that is the point</h2>
 * The non-{@code Mob} refusal. {@link VehicleBinder#createPiloted} takes a {@code Mob}, so handing it an
 * {@code ArmorStand} — a {@code LivingEntity} that is not a {@code Mob} — or an {@code Interaction} is a
 * <strong>compile error</strong> and there is no runtime behaviour to assert. That is the whole of the
 * requirement being in the type rather than in a cast: the test is that the code does not compile, and
 * {@link #theOrdinaryCreatePathStillTakesAnyEntity()} is the other half of it — the plain
 * {@link VehicleBinder#create} still accepts an {@code Entity}, so nothing was narrowed for the consumers
 * that do not want AI.
 */
class AiPilotTest {

    /** Small and deliberately unlike the shipped defaults; see the AI suite's own fixture. */
    private static final @NotNull AiBounds BOUNDS =
            new AiBounds(3, 2, 7, 2, 2L, 40L, 11.0D, 500L, 8);

    private static final float EPSILON = 1.0E-5F;

    // ------------------------------------------------------------------ fixtures

    /** A plan that validates, fills one slot and does nothing. */
    private static @NotNull AiPlan plan(boolean clearsVanillaGoals) {
        return AiPlan.builder("haul", BOUNDS)
                .activity(AiActivityPlan.builder(AiActivity.IDLE)
                        .behaviour(1, new AiBehaviour() {
                            @Override
                            public @NotNull String name() {
                                return "idle";
                            }

                            @Override
                            public boolean canStart(@NotNull AiContext context) {
                                return true;
                            }

                            @Override
                            public void tick(@NotNull AiContext context, long ticksRunning) {
                                // Nothing: this plan exists to be installed, not to decide.
                            }
                        })
                        .build())
                .defaultActivity(AiActivity.IDLE)
                .clearVanillaGoals(clearsVanillaGoals)
                .build();
    }

    /**
     * A creature reference, which is all the AI-on-vehicle path ever needs of one.
     *
     * <p>Through the package's own entity fixture rather than a second proxy: the point of both is that
     * nothing above the seams reads anything off the root, and one fixture saying so is better than two.
     * The cast is sound because the proxy is built against {@code Mob} itself.
     */
    private static @NotNull Mob mob() {
        return (Mob) StubEntity.of(Mob.class);
    }

    private static @NotNull PilotContext context(double x, double z, float headingDegrees) {
        final VehicleProfile profile = VehicleFixtures.profile();
        return new PilotContext(StubVehicle.create(), x, 64.0D, z,
                new VehicleState(0F, 0F, headingDegrees), profile, 1F);
    }

    // ------------------------------------------------------------------ the intent bridge

    @Test
    void aPlanWithAMoveTargetBecomesHeldKeys() {
        // The whole point of the type: a destination in, seven booleans out, and the vehicle's own solver
        // does the rest. The target is a hundred blocks along +Z and the vehicle faces +Z.
        final FakeAiController installed = new FakeAiController(plan(true));
        installed.publish(AiIntent.moveTo(new AiPoint(0.0D, 64.0D, 100.0D), 1.0D));
        final AiPilot pilot = AiPilot.borrowing(installed);

        final RiderIntent intent = pilot.steer(context(0.0D, 0.0D, 0F));

        assertAll(
                () -> assertTrue(intent.forward()),
                () -> assertFalse(intent.left() || intent.right(), "dead ahead")
        );
    }

    @Test
    void aBehaviourHandingMovementBackStopsTheVehicle() {
        // AiIntent.NONE is a behaviour saying "stop deciding where to go", not an absence of a decision.
        // Releasing every key is what lets the vehicle coast to the rest fixed point and stop writing
        // positions at all, which is the difference between a parked vehicle and a quiet one.
        final FakeAiController installed = new FakeAiController(plan(true));
        installed.publish(AiIntent.NONE);

        assertSame(RiderIntent.NONE, AiPilot.borrowing(installed).steer(context(0.0D, 0.0D, 0F)));
    }

    @Test
    void aLookTargetAloneAsksForNothing() {
        // The look point is deliberately dropped: a root entity's yaw is the vehicle's heading, written by
        // the control step from the solver's answer, so applying a look would be a second writer to it.
        // A consumer that wants a head to track it reads the intent from its own controller instead.
        final FakeAiController installed = new FakeAiController(plan(true));
        installed.publish(AiIntent.lookAt(new AiPoint(500.0D, 64.0D, 500.0D)));

        assertSame(RiderIntent.NONE, AiPilot.borrowing(installed).steer(context(0.0D, 0.0D, 0F)));
    }

    @Test
    void anUninstalledPlanStopsSteering() {
        // Not a stale decision carried for ever: a vehicle whose plan was uninstalled coasts to a stop
        // rather than driving on toward a destination nothing is deciding any more.
        final FakeAiController installed = new FakeAiController(plan(true));
        installed.publish(AiIntent.moveTo(new AiPoint(0.0D, 64.0D, 100.0D), 1.0D));
        final AiPilot pilot = AiPilot.borrowing(installed);

        assertTrue(pilot.steer(context(0.0D, 0.0D, 0F)).forward());
        installed.uninstall();

        assertSame(RiderIntent.NONE, pilot.steer(context(0.0D, 0.0D, 0F)));
    }

    @Test
    void theHeightOfTheMoveTargetIsIgnoredRatherThanApproximated() {
        // There is no key the solver would read a vertical from, so a target's height cannot be acted on.
        // Two targets differing only in y therefore produce the same keys, which is the honest answer and
        // is stated rather than left to be discovered on a flyer.
        final FakeAiController low = new FakeAiController(plan(true));
        low.publish(AiIntent.moveTo(new AiPoint(0.0D, 10.0D, 100.0D), 1.0D));
        final FakeAiController high = new FakeAiController(plan(true));
        high.publish(AiIntent.moveTo(new AiPoint(0.0D, 200.0D, 100.0D), 1.0D));

        assertEquals(
                AiPilot.borrowing(low).steer(context(0.0D, 0.0D, 0F)),
                AiPilot.borrowing(high).steer(context(0.0D, 0.0D, 0F)));
    }

    // ------------------------------------------------------------------ attaching

    @Test
    void aPendingPilotAsksForNothingUntilItsPlanArrives() {
        // The few ticks between a vehicle being created and its asynchronous install completing. The
        // vehicle sits still, which is the honest state: nothing has decided anything yet.
        final AiPilot pilot = AiPilot.pending();

        assertAll(
                () -> assertTrue(pilot.controller().isEmpty()),
                () -> assertSame(RiderIntent.NONE, pilot.steer(context(0.0D, 0.0D, 0F))),
                () -> assertTrue(pilot.isOwning(),
                        "a pending pilot owns what it is given, because the thing that gave it to it is"
                                + " createPiloted and nobody else is holding it")
        );

        final FakeAiController installed = new FakeAiController(plan(true));
        installed.publish(AiIntent.moveTo(new AiPoint(0.0D, 64.0D, 100.0D), 1.0D));
        pilot.attach(installed);

        assertAll(
                () -> assertSame(installed, pilot.controller().orElseThrow()),
                () -> assertTrue(pilot.steer(context(0.0D, 0.0D, 0F)).forward())
        );
    }

    @Test
    void attachingTwiceIsRefusedBecauseTheFirstPlanWouldBeUnreleasable() {
        final AiPilot pilot = AiPilot.pending();
        pilot.attach(new FakeAiController(plan(true)));

        final IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> pilot.attach(new FakeAiController(plan(true))));

        assertAll(
                () -> assertTrue(refused.getMessage().contains("haul"), "names the plan"),
                () -> assertTrue(refused.getMessage().contains("AiPilot.borrowing"),
                        "and names the fix, which is the whole point of refusing rather than replacing")
        );
    }

    // ------------------------------------------------------------------ the lifetime

    @Test
    void anOwningPilotUninstallsItsPlanWhenTheVehicleIsTornDown() {
        // An entity-scheduled sensor task is never cancelled for a plugin on disable, so a plan nobody
        // uninstalls outlives the classloader. Making the plan's lifetime the vehicle's is what the
        // owning factory is for.
        final FakeAiController installed = new FakeAiController(plan(true));
        final AiPilot pilot = AiPilot.owning(installed);

        pilot.onClosed(StubVehicle.create());

        assertFalse(installed.isInstalled(), "the plan is gone with the vehicle");
    }

    @Test
    void aBorrowingPilotLeavesItsPlanRunning() {
        // The caller installed it for its own reasons and is still holding it.
        final FakeAiController installed = new FakeAiController(plan(true));

        AiPilot.borrowing(installed).onClosed(StubVehicle.create());

        assertTrue(installed.isInstalled());
    }

    @Test
    void aPendingPilotThatNeverGotAPlanClosesWithoutComplaint() {
        // The install failed, or the vehicle died before it completed. There is nothing to release and
        // the hook must not throw: it runs on whichever thread reached the teardown first, including the
        // platform's retired callback, where an exception would abandon the rest of the release.
        AiPilot.pending().onClosed(StubVehicle.create());
    }

    // ------------------------------------------------------------------ createPiloted

    @Test
    void aPlanThatLeavesTheVanillaGoalsInPlaceIsRefusedBeforeAnythingExists() {
        // The two-writers refusal, and the reason it is a refusal rather than a warning: a goal that
        // navigates writes the root entity's movement from the creature's tick while the control step
        // writes it from the same tick, and nothing reports the fight.
        final RecordingBinder binder = new RecordingBinder();
        final FakeCreatureAi ai = new FakeCreatureAi(true);

        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> binder.createPiloted(ai, VehicleFixtures.rig(), VehicleFixtures.profile(),
                        mob(), VehicleSetup.silent(), plan(false)));

        assertAll(
                () -> assertTrue(refused.getMessage().contains("clearVanillaGoals(true)"),
                        "names the fix"),
                () -> assertEquals(0, binder.created, "and nothing was created"),
                () -> assertEquals(0, ai.installs, "and nothing was installed")
        );
    }

    @Test
    void aSpeciesWhoseTickDoesNotTickItsBrainIsRefusedAndTheMessageNamesTheAlternatives() {
        // The silent failure this check exists to make loud: the install would succeed, be correct, and
        // be asked nothing ever. The message has to carry both halves of the real constraint, because a
        // caller who reads only the species list will reach for a camel.
        final RecordingBinder binder = new RecordingBinder();
        final FakeCreatureAi ai = new FakeCreatureAi(false);

        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> binder.createPiloted(ai, VehicleFixtures.rig(), VehicleFixtures.profile(),
                        mob(), VehicleSetup.silent(), plan(true)));

        assertAll(
                () -> assertTrue(refused.getMessage().contains("Goat"),
                        "names a species that works, from the service's own list"),
                () -> assertTrue(refused.getMessage().contains("Camel")
                                && refused.getMessage().contains("HappyGhast"),
                        "and names the two that pass this check and fail the authority one, which is the"
                                + " half a caller cannot work out from the list alone"),
                () -> assertEquals(0, binder.created)
        );
    }

    @Test
    void theVehicleIsCreatedBeforeThePlanIsInstalled() {
        // The order, and it is not a preference. Creating is what refuses a client-steered root, a
        // profile outside the operator's bounds, a call from the wrong thread and a rig that no longer
        // resolves. Installing first would leave a rebuilt brain and scheduled sensors on a creature
        // that turned out not to be allowed to be a vehicle, with no handle to reach them by.
        final RecordingBinder binder = new RecordingBinder();
        final FakeCreatureAi ai = new FakeCreatureAi(true);

        final CompletableFuture<Vehicle> pending = binder.createPiloted(ai, VehicleFixtures.rig(),
                VehicleFixtures.profile(), mob(), VehicleSetup.silent(), plan(true));

        assertAll(
                () -> assertEquals(List.of("create", "install"), ai.order(binder)),
                () -> assertFalse(pending.isDone(), "the install has not completed yet"),
                () -> assertNotNull(binder.pilot, "and the vehicle was built with a pilot")
        );

        final FakeAiController installed = ai.complete();

        assertAll(
                () -> assertTrue(pending.isDone()),
                () -> assertSame(binder.vehicle, pending.join()),
                () -> assertSame(installed, ((AiPilot) binder.pilot).controller().orElseThrow(),
                        "and the installation reached the pilot the vehicle is already carrying")
        );
    }

    @Test
    void anInstallThatFailsDespawnsTheVehicleRatherThanLeavingItParked() {
        // A consumer that asked for an AI-driven vehicle and cannot have one must not be left holding a
        // rig in the world that nothing will ever drive — the silent half-failure this project refuses.
        final RecordingBinder binder = new RecordingBinder();
        final FakeCreatureAi ai = new FakeCreatureAi(true);

        final CompletableFuture<Vehicle> pending = binder.createPiloted(ai, VehicleFixtures.rig(),
                VehicleFixtures.profile(), mob(), VehicleSetup.silent(), plan(true));
        ai.fail(new IllegalStateException("the creature was gone by the time the install ran"));

        assertAll(
                () -> assertTrue(assertThrows(CompletionException.class, pending::join)
                        .getCause() instanceof IllegalStateException,
                        "the failure reaches the caller rather than being swallowed"),
                () -> assertTrue(binder.despawned.get(), "and the vehicle does not survive it")
        );
    }

    @Test
    void aRootThatVanishedBeforeTheLoopStartedIsReturnedWithoutInstallingAPlan() {
        // The one race a caller cannot avoid, and the handle comes back already stopped. There is nothing
        // left to install a plan on, so nothing is installed — rather than a brain rebuilt on a corpse.
        final RecordingBinder binder = new RecordingBinder();
        binder.alive = false;
        final FakeCreatureAi ai = new FakeCreatureAi(true);

        final CompletableFuture<Vehicle> pending = binder.createPiloted(ai, VehicleFixtures.rig(),
                VehicleFixtures.profile(), mob(), VehicleSetup.silent(), plan(true));

        assertAll(
                () -> assertTrue(pending.isDone()),
                () -> assertSame(binder.vehicle, pending.join()),
                () -> assertEquals(0, ai.installs)
        );
    }

    @Test
    void anInstallThatRefusesOnTheCallingThreadStillTearsTheVehicleDown() {
        // CreatureAi.install throws a bad plan on its caller's thread rather than returning it, and by
        // then a vehicle exists. The pre-checks cover the known cases; this covers the live bounds having
        // moved between them, which is a reload away.
        final RecordingBinder binder = new RecordingBinder();
        final FakeCreatureAi ai = new FakeCreatureAi(true);
        ai.refuseSynchronously = new IllegalArgumentException("the bounds moved");

        assertThrows(IllegalArgumentException.class,
                () -> binder.createPiloted(ai, VehicleFixtures.rig(), VehicleFixtures.profile(),
                        mob(), VehicleSetup.silent(), plan(true)));

        assertTrue(binder.despawned.get(), "a vehicle with a plan that was never installed is torn down");
    }

    @Test
    void theOrdinaryCreatePathStillTakesAnyEntity() {
        // The other half of the type-level requirement: narrowing createPiloted to a Mob narrowed nothing
        // for the consumers that do not want AI, and the four-argument overload is now written in terms
        // of the five-argument one with the identity pilot.
        final RecordingBinder binder = new RecordingBinder();
        final Entity root = StubEntity.plain();

        final Vehicle created = binder.create(VehicleFixtures.rig(), VehicleFixtures.profile(), root,
                VehicleSetup.silent());

        assertAll(
                () -> assertSame(binder.vehicle, created),
                () -> assertSame(VehiclePilot.NONE, binder.pilot,
                        "and the identity pilot is what an unpiloted vehicle carries")
        );
    }

    @Test
    void aPilotContextCarriesTheMeasuredStepSoAThresholdCanBeDerivedFromIt() {
        // Pinned because it is what lets AiPilot and PilotSteering hold no configured number at all.
        final PilotContext stalled = new PilotContext(StubVehicle.create(), 0.0D, 64.0D, 0.0D,
                VehicleState.STOPPED, VehicleFixtures.profile(), 3.5F);

        assertAll(
                () -> assertEquals(3.5F, stalled.ticks(), EPSILON),
                () -> assertTrue(stalled.hasElapsed())
        );
    }

    // ------------------------------------------------------------------ fakes

    /**
     * An {@link AiController} a test publishes decisions through.
     *
     * <p>A real class rather than a proxy, because the intent is read and the installed flag is written —
     * both of which are the subject here rather than incidental.
     */
    private static final class FakeAiController implements AiController {

        private final @NotNull AiPlan plan;
        private final @NotNull UUID identity = UUID.randomUUID();
        private final @NotNull Mob mob = mob();
        private volatile @NotNull AiIntent intent = AiIntent.NONE;
        private volatile boolean installed = true;

        FakeAiController(@NotNull AiPlan plan) {
            this.plan = Objects.requireNonNull(plan, "plan");
        }

        void publish(@NotNull AiIntent decision) {
            this.intent = decision;
        }

        @Override
        public @NotNull AiPlan getPlan() {
            return this.plan;
        }

        @Override
        public @NotNull Mob getMob() {
            return this.mob;
        }

        @Override
        public @NotNull UUID getUniqueIdentifier() {
            return this.identity;
        }

        @Override
        public @NotNull AiMemoryView memories() {
            throw new AssertionError("A pilot must not read memories: they are confined to the thread"
                    + " that owns the creature and the designed cross-thread value is the intent.");
        }

        @Override
        public @NotNull AiIntent intent() {
            return this.intent;
        }

        @Override
        public boolean isInstalled() {
            return this.installed;
        }

        @Override
        public void uninstall() {
            this.installed = false;
        }
    }

    /** A {@link CreatureAi} whose install a test completes by hand. */
    private static final class FakeCreatureAi implements CreatureAi {

        private final boolean supports;
        private final @NotNull AtomicReference<CompletableFuture<AiController>> pending =
                new AtomicReference<>();
        private final @NotNull List<String> order;

        private int installs;
        private @Nullable RuntimeException refuseSynchronously;

        FakeCreatureAi(boolean supports) {
            this(supports, new ArrayList<>());
        }

        private FakeCreatureAi(boolean supports, @NotNull List<String> order) {
            this.supports = supports;
            this.order = order;
        }

        @Override
        public boolean supports(@NotNull Mob mob) {
            return this.supports;
        }

        @Override
        public @NotNull List<String> supportedSpecies() {
            return List.of("Camel", "Goat", "HappyGhast", "Villager");
        }

        @Override
        public @NotNull AiBounds getBounds() {
            return BOUNDS;
        }

        @Override
        public @NotNull CompletableFuture<AiController> install(
                @NotNull AiPlan plan,
                @NotNull Mob mob
        ) {
            if (this.refuseSynchronously != null) {
                throw this.refuseSynchronously;
            }
            this.installs++;
            this.order.add("install");
            final CompletableFuture<AiController> future = new CompletableFuture<>();
            this.pending.set(future);
            return future;
        }

        /** Completes the pending install, as the creature's own scheduler would. */
        @NotNull FakeAiController complete() {
            final FakeAiController controller = new FakeAiController(plan(true));
            Objects.requireNonNull(this.pending.get(), "nothing is installing").complete(controller);
            return controller;
        }

        void fail(@NotNull Throwable failure) {
            Objects.requireNonNull(this.pending.get(), "nothing is installing")
                    .completeExceptionally(failure);
        }

        /** The interleaving of this service's calls with {@code binder}'s, as one sequence. */
        @NotNull List<String> order(@NotNull RecordingBinder binder) {
            final List<String> merged = new ArrayList<>(binder.order);
            merged.addAll(this.order);
            return merged;
        }
    }

    /**
     * A {@link VehicleBinder} that records what it was asked for and hands back a stub.
     *
     * <p>Only the five-argument {@code create} is implemented, which is the point:
     * {@code createPiloted} and the four-argument overload are both defaults written in terms of it, so a
     * binder that satisfies one satisfies all three.
     */
    private static final class RecordingBinder implements VehicleBinder {

        private final @NotNull List<String> order = new ArrayList<>();
        private final @NotNull AtomicBoolean despawned = new AtomicBoolean();

        private boolean alive = true;
        private int created;
        private @Nullable VehiclePilot pilot;
        private @Nullable Vehicle vehicle;

        @Override
        public @NotNull Vehicle create(
                @NotNull Rig rig,
                @NotNull VehicleProfile profile,
                @NotNull Entity root,
                @NotNull VehicleSetup setup,
                @NotNull VehiclePilot withPilot
        ) {
            this.created++;
            this.order.add("create");
            this.pilot = withPilot;
            this.vehicle = StubVehicle.create(this.alive, this.despawned);
            return this.vehicle;
        }
    }

    /** A {@link Vehicle} that answers whether it is alive and remembers being despawned. */
    private static final class StubVehicle {

        private StubVehicle() {
        }

        static @NotNull Vehicle create() {
            return create(true, new AtomicBoolean());
        }

        static @NotNull Vehicle create(boolean alive, @NotNull AtomicBoolean despawned) {
            return (Vehicle) Proxy.newProxyInstance(
                    StubVehicle.class.getClassLoader(),
                    new Class<?>[]{Vehicle.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "equals" -> proxy == arguments[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "toString" -> "StubVehicle";
                        case "isAlive", "isSpawned" -> alive;
                        case "despawn" -> {
                            despawned.set(true);
                            yield null;
                        }
                        default -> throw new AssertionError("The AI-on-vehicle path read "
                                + method.getName() + " off the vehicle; it should not need to");
                    });
        }
    }
}
