package pandastic.studio.models.internal.ai;

import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.ai.AiActivity;
import pandastic.studio.models.api.ai.AiBounds;
import pandastic.studio.models.api.ai.AiContext;
import pandastic.studio.models.api.ai.AiController;
import pandastic.studio.models.api.ai.AiMemory;
import pandastic.studio.models.api.ai.AiPlan;
import pandastic.studio.models.api.ai.AiSensor;
import pandastic.studio.models.internal.nms.AiFaultSink;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Installing, and the thread it happens on.
 *
 * <h2>Why the dispatch is the thing most worth testing here</h2>
 * Registering behaviour on a creature is the one operation in this area with <strong>no thread checking
 * at all</strong> — the goal selector holds a plain set and the brain's maps are plain maps — so a
 * registration from the wrong thread does not throw, it interleaves with the creature's tick. There is
 * no mode that would catch it, which means the only protection is this layer refusing to do it on its
 * caller's thread, which in turn means the only protection against <em>that</em> regressing is a test
 * that proves nothing was touched before the dispatch ran.
 *
 * <p>{@link FakeAiHost} makes that observable: it queues submissions instead of running them, so a test
 * can assert that the seam was untouched and then drain the queue.
 */
class CreatureAiImplTest {

    private final FakeAiHost host = new FakeAiHost();
    private final FakeBrainBridge brains = new FakeBrainBridge();
    private final List<String> reports = new ArrayList<>();

    private final ThrottledFaultSink faults = new ThrottledFaultSink(
            (message, failure) -> this.reports.add(message),
            AiSettingsSource.fixed(new AiSettings(AiFixtures.BOUNDS, 0L, 3)),
            () -> 0L);

    private CreatureAiImpl service() {
        return service(AiFixtures.BOUNDS);
    }

    private CreatureAiImpl service(AiBounds bounds) {
        return new CreatureAiImpl(this.brains,
                AiSettingsSource.fixed(new AiSettings(bounds, 0L, 3)),
                this.faults,
                mob -> this.host);
    }

    @Test
    void nothingTouchesTheCreatureBeforeTheDispatchRuns() {
        final CompletableFuture<AiController> installing =
                service().install(AiFixtures.minimal("guard").build(), this.host.mob());

        assertAll(
                () -> assertFalse(installing.isDone(), "the future must not complete on this thread"),
                () -> assertTrue(this.brains.requests.isEmpty(),
                        "the seam must not have been reached on the calling thread"),
                () -> assertEquals(1, this.host.submitted.size(),
                        "the install must have been handed to the creature's own scheduler")
        );
    }

    @Test
    void theDispatchedInstallCompletesTheFutureWithALiveController() {
        final AiPlan plan = AiFixtures.minimal("guard").build();
        final CompletableFuture<AiController> installing = service().install(plan, this.host.mob());

        this.host.drainSubmitted();

        assertAll(
                () -> assertTrue(installing.isDone()),
                () -> assertSame(plan, installing.join().getPlan()),
                () -> assertTrue(installing.join().isInstalled()),
                () -> assertEquals(1, this.brains.requests.size()),
                () -> assertSame(plan, this.brains.requests.get(0).plan())
        );
    }

    @Test
    void aPlanThatDoesNotFitTheLiveBoundsThrowsOnTheCallingThread() {
        // A programming error, and one that arrives a callback later is one whose stack trace no longer
        // contains the code that caused it.
        final AiPlan plan = AiFixtures.minimal("yesterday")
                .sensor(AiFixtures.sensor("look", Set.of()), 20L)
                .build();
        final AiBounds tightened = new AiBounds(3, 2, 7, 2, 2L, 10L, 11.0D, 500L, 8);

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> service(tightened).install(plan, this.host.mob())),
                () -> assertTrue(this.host.submitted.isEmpty(),
                        "a refused plan must not have been dispatched at all")
        );
    }

    @Test
    void anUnsupportedSpeciesThrowsOnTheCallingThreadAndNamesTheAlternatives() {
        this.brains.supports = false;

        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> service().install(AiFixtures.minimal("guard").build(), this.host.mob()));

        assertAll(
                () -> assertTrue(refused.getMessage().contains("Villager"), refused::getMessage),
                () -> assertTrue(refused.getMessage().contains("no effect at all"),
                        () -> "the refusal must say why it would not merely fail: "
                                + refused.getMessage()),
                () -> assertTrue(this.host.submitted.isEmpty())
        );
    }

    @Test
    void aCreatureWhoseSchedulerIsGoneFailsTheFutureRatherThanThrowing() {
        this.host.schedulable = false;

        final CompletableFuture<AiController> installing =
                service().install(AiFixtures.minimal("guard").build(), this.host.mob());

        assertAll(
                () -> assertTrue(installing.isCompletedExceptionally()),
                () -> assertInstanceOf(IllegalStateException.class,
                        assertThrows(ExecutionException.class, installing::get).getCause())
        );
    }

    @Test
    void aCreatureThatDiedBeforeTheDispatchRanFailsTheFuture() {
        // A race a caller cannot avoid, which is why it is a failed future and not an exception.
        final CompletableFuture<AiController> installing =
                service().install(AiFixtures.minimal("guard").build(), this.host.mob());
        this.host.alive = false;
        this.host.drainSubmitted();

        assertAll(
                () -> assertTrue(installing.isCompletedExceptionally()),
                () -> assertTrue(this.brains.requests.isEmpty(),
                        "the seam must not be reached for a creature that is gone")
        );
    }

    @Test
    void aFailureBelowTheSeamFailsTheFutureRatherThanEscapingTheScheduler() {
        // An adapter compiled against a different server fails on its first real call, and that call is
        // inside the dispatched body. An exception escaping there is swallowed by the platform and the
        // future hangs forever.
        this.brains.installFailure = new IllegalStateException("no adapter for this server");

        final CompletableFuture<AiController> installing =
                service().install(AiFixtures.minimal("guard").build(), this.host.mob());
        this.host.drainSubmitted();

        assertTrue(installing.isCompletedExceptionally());
    }

    @Test
    void installingASecondPlanUninstallsTheFirstBeforeBuildingTheSecond() {
        // There must never be a moment with two plans driving one brain.
        final CreatureAiImpl service = service();
        final AiController first = install(service, AiFixtures.minimal("first").build());
        final AiController second = install(service, AiFixtures.minimal("second").build());

        assertAll(
                () -> assertFalse(first.isInstalled()),
                () -> assertTrue(second.isInstalled()),
                () -> assertEquals(1, service.installationCount(),
                        "a creature may hold one plan, keyed by the creature rather than the handle"),
                () -> assertEquals(1, this.brains.installations.get(0).uninstalls),
                () -> assertEquals(0, this.brains.installations.get(1).uninstalls)
        );
    }

    @Test
    void anUninstalledControllerIsForgottenByTheService() {
        final CreatureAiImpl service = service();
        final AiController controller = install(service, AiFixtures.minimal("guard").build());

        controller.uninstall();

        assertAll(
                () -> assertEquals(0, service.installationCount()),
                () -> assertNull(service.installationFor(this.host.creatureId()))
        );
    }

    @Test
    void aLateTeardownOfAReplacedPlanDoesNotEvictItsReplacement() {
        // The reason the map removal is by identity: both controllers share one key.
        final CreatureAiImpl service = service();
        final AiController first = install(service, AiFixtures.minimal("first").build());
        final AiController second = install(service, AiFixtures.minimal("second").build());

        first.uninstall();

        assertAll(
                () -> assertEquals(1, service.installationCount()),
                () -> assertSame(second, service.installationFor(this.host.creatureId()))
        );
    }

    @Test
    void uninstallingEverythingTearsDownEveryInstallationAndCancelsItsTasks() {
        final CreatureAiImpl service = service();
        install(service, AiFixtures.minimal("guard")
                .sensor(AiFixtures.sensor("look", Set.of()), 4L)
                .build());

        assertAll(
                () -> assertEquals(1L, this.host.liveTaskCount()),
                () -> assertEquals(1, service.uninstallAll()),
                () -> assertEquals(0L, this.host.liveTaskCount(),
                        "a repeating task dispatched through a creature's scheduler is never cancelled"
                                + " for a plugin on disable, so this layer has to do it"),
                () -> assertEquals(0, service.installationCount())
        );
    }

    @Test
    void theContextHandedToBehavioursGuardsMemoriesAgainstTheInstalledPlan() {
        // The callback the seam exists for: the adapter supplies the store, the layer supplies the rules.
        final AiMemory<Long> declared = AiMemory.defined("cooldown", Long.class);
        final AiMemory<Long> undeclared = AiMemory.defined("elsewhere", Long.class);
        install(service(), AiFixtures.minimal("guard").declareMemory(declared).build());

        final AiContext context = this.brains.lastContext;

        assertAll(
                () -> {
                    context.memories().set(declared, 3L);
                    assertEquals(3L, this.brains.store.values.get("minecraft:cooldown"));
                },
                () -> assertThrows(IllegalArgumentException.class,
                        () -> context.memories().set(undeclared, 3L))
        );
    }

    @Test
    void theBoundsReportedToAPlanBuilderAreTheLiveOnes() {
        assertSame(AiFixtures.BOUNDS, service().getBounds());
    }

    @Test
    void theSupportedSpeciesComeFromTheSeamSoTheyCannotGoStale() {
        assertEquals(List.of("Villager", "Warden"), service().supportedSpecies());
    }

    @Test
    void aSensorBodyThatThrowsIsContainedAndEventuallyStopped() {
        final AiSensor broken = new AiSensor() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public void sense(AiContext context) {
                throw new IllegalStateException("sensor blew up");
            }
        };
        install(service(), AiFixtures.minimal("guard").sensor(broken, 4L).build());

        // Three is the configured limit, so the third failure is the one that stops the body.
        this.host.tickSensor(0);
        this.host.tickSensor(0);
        this.host.tickSensor(0);

        assertAll(
                () -> assertEquals(0L, this.host.liveTaskCount(),
                        "the platform swallows an exception from a repeating body and does not cancel"
                                + " the task, so a sensor that keeps failing has to be cancelled here"),
                () -> assertEquals(3, this.reports.size()),
                () -> assertTrue(this.reports.get(2).contains("will not be run again"),
                        () -> this.reports.get(2))
        );
    }

    @Test
    void aSensorBodyThatSucceedsIsRunAtItsConfiguredPeriod() {
        final List<Long> senses = new ArrayList<>();
        final AiSensor working = new AiSensor() {
            @Override
            public String name() {
                return "working";
            }

            @Override
            public void sense(AiContext context) {
                senses.add(context.gameTime());
            }
        };
        install(service(), AiFixtures.minimal("guard").sensor(working, 7L).build());

        this.host.gameTime = 100L;
        this.host.tickSensor(0);

        assertAll(
                () -> assertEquals(7L, this.host.scheduled.get(0).periodTicks(),
                        "the period must come from the plan, not from a default"),
                () -> assertEquals(List.of(100L), senses),
                () -> assertEquals(0L, this.faults.consecutiveFailures(AiFaultSink.SENSOR, "working"))
        );
    }

    @Test
    void aCreatureThatRetiresTearsTheInstallationDownWithoutRestoringTheBrain() {
        // The platform's retired callback is critical code with no thread left to defer to, and a
        // creature that has been removed has nothing to restore a brain onto.
        final CreatureAiImpl service = service();
        final AiController controller = install(service, AiFixtures.minimal("guard")
                .sensor(AiFixtures.sensor("look", Set.of()), 4L)
                .build());

        this.host.retire();

        assertAll(
                () -> assertFalse(controller.isInstalled()),
                () -> assertEquals(0L, this.host.liveTaskCount()),
                () -> assertEquals(0, service.installationCount()),
                () -> assertEquals(0, this.brains.installations.get(0).uninstalls,
                        "the brain must not be restored from a retired callback")
        );
    }

    @Test
    void anUninstallFromAForeignThreadDefersTheBrainRestorationToTheOwningThread() {
        final AiController controller = install(service(), AiFixtures.minimal("guard").build());
        this.host.owned = false;

        controller.uninstall();

        assertAll(
                () -> assertFalse(controller.isInstalled(), "the handle must be dead immediately"),
                () -> assertEquals(0, this.brains.installations.get(0).uninstalls,
                        "the brain must not be replaced from a thread that does not own the creature"),
                () -> {
                    this.host.drainSubmitted();
                    assertEquals(1, this.brains.installations.get(0).uninstalls);
                }
        );
    }

    @Test
    void uninstallingIsIdempotentHoweverManyWaysItIsReached() {
        final AiController controller = install(service(), AiFixtures.minimal("guard").build());

        controller.uninstall();
        controller.uninstall();
        controller.close();
        this.host.retire();

        assertEquals(1, this.brains.installations.get(0).uninstalls);
    }

    @Test
    void aSensorThatCannotBeScheduledRollsTheWholeInstallBack() {
        // Neither the body nor the retired callback would ever run, so nothing would tear it down.
        final FakeAiHost unschedulable = new FakeAiHost();
        final CreatureAiImpl service = new CreatureAiImpl(this.brains,
                AiSettingsSource.fixed(new AiSettings(AiFixtures.BOUNDS, 0L, 3)),
                this.faults,
                mob -> unschedulable);

        final CompletableFuture<AiController> installing = service.install(AiFixtures.minimal("guard")
                .sensor(AiFixtures.sensor("look", Set.of()), 4L)
                .build(), unschedulable.mob());
        // Let the install itself through, then refuse the sensor dispatch.
        final List<Runnable> queued = List.copyOf(unschedulable.submitted);
        unschedulable.submitted.clear();
        unschedulable.schedulable = false;
        queued.forEach(Runnable::run);

        assertAll(
                () -> assertTrue(installing.isCompletedExceptionally()),
                () -> assertEquals(0, service.installationCount()),
                () -> assertEquals(1, this.brains.installations.get(0).uninstalls,
                        "a rolled-back install must not leave a brain installed")
        );
    }

    @Test
    void thePlanOnlyFillsTheSlotsItDeclaredAndTheSeamSeesThemAll() {
        final AiPlan plan = AiPlan.builder("layered", AiFixtures.BOUNDS)
                .activity(AiFixtures.activity(AiActivity.IDLE))
                .activity(AiFixtures.activity(AiActivity.FIGHT))
                .coreActivity(AiActivity.FIGHT)
                .defaultActivity(AiActivity.IDLE)
                .build();

        install(service(), plan);

        assertEquals(2, this.brains.requests.get(0).plan().getActivities().size());
    }

    /** Installs synchronously by dispatching and then draining, which is what a region tick does. */
    private AiController install(CreatureAiImpl service, AiPlan plan) {
        final CompletableFuture<AiController> installing = service.install(plan, this.host.mob());
        this.host.drainSubmitted();
        return installing.join();
    }
}
