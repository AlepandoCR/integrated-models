package pandastic.studio.models.internal.config;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the publish-and-swap machinery: what a reload does, what a refused reload does not do, and
 * what a reader concurrent with both can observe.
 *
 * <h2>No server anywhere</h2>
 * {@link ConfigService#inDirectory(java.io.File)} exists for exactly this. The only entry point that
 * needs a {@code Plugin} is {@link ConfigService#create(org.bukkit.plugin.Plugin)}, and all it does is
 * resolve the plugin's data folder before delegating here — so the untested surface is one call to
 * {@code ConfigFolder.root().resolve(plugin)} and nothing else.
 */
class ConfigServiceTest {

    @TempDir
    Path directory;

    private @NotNull ConfigService service() {
        return ConfigService.inDirectory(this.directory.toFile());
    }

    @Test
    void aServiceStartsOnTheDeclaredDefaultsBeforeAnythingIsLoaded() {
        // So that a plugin whose very first load fails is still running on values somebody chose,
        // rather than on zeroes or on nothing at all.
        final ConfigService service = service();
        assertAll(
                () -> assertEquals(ConfigSnapshot.UNPUBLISHED, service.snapshot().revision()),
                () -> assertEquals(ConfigSnapshot.defaults().withRevision(ConfigSnapshot.UNPUBLISHED),
                        service.snapshot().withRevision(ConfigSnapshot.UNPUBLISHED))
        );
    }

    @Test
    void aSuccessfulReloadSwapsTheSnapshot() {
        final ConfigService service = service();
        service.reloadNow();
        final ConfigSnapshot before = service.snapshot();

        ConfigFixture.write(this.directory, ConfigFile.GAIT, "defaultStrideLength: 2.5");
        final ConfigReloadResult result = service.reloadNow();

        assertAll(
                () -> assertTrue(result.applied(), () -> result.summary()),
                () -> assertEquals(2.5F, service.snapshot().gait().defaultStrideLength()),
                () -> assertEquals(before.revision() + 1L, service.snapshot().revision()),
                () -> assertSame(service.snapshot(), result.snapshot(),
                        "an applied result has to report the snapshot it published")
        );
    }

    @Test
    void aRefusedReloadLeavesThePreviousSnapshotUntouched() {
        final ConfigService service = service();
        ConfigFixture.write(this.directory, ConfigFile.PHYSICS, "stiffnessToRate: 180.0");
        assertTrue(service.reloadNow().applied());
        final ConfigSnapshot live = service.snapshot();

        // minMass above maxMass: legal YAML, refused arithmetic.
        ConfigFixture.write(this.directory, ConfigFile.PHYSICS,
                "stiffnessToRate: 300.0", "minMass: 50.0");
        final ConfigReloadResult result = service.reloadNow();

        assertAll(
                () -> assertFalse(result.applied()),
                () -> assertSame(live, service.snapshot(),
                        "the live snapshot must be the same object, not an equal one"),
                () -> assertSame(live, result.snapshot(),
                        "a refused result has to report what is still live"),
                () -> assertEquals(180F, service.snapshot().physics().stiffnessToRate(),
                        "the half of the file that was fine must not have been applied either"),
                () -> assertFalse(result.diagnostics().isEmpty()),
                () -> assertTrue(result.diagnostics().stream()
                                .anyMatch(d -> d.file() == ConfigFile.PHYSICS
                                        && "minMass".equals(d.key())),
                        () -> "the refusal has to name the file and the key: " + result.diagnostics()),
                () -> assertTrue(result.summary().contains("refused")),
                () -> assertFalse(result.requiresSimulationRebuild(),
                        "a reload that was not applied cannot have invalidated anything")
        );
    }

    @Test
    void aFileThatIsNotYamlIsRefusedNamingTheFileAndNoKey() {
        final ConfigService service = service();
        assertTrue(service.reloadNow().applied());
        final ConfigSnapshot live = service.snapshot();

        ConfigFixture.corrupt(this.directory, ConfigFile.SIMULATION);
        final ConfigReloadResult result = service.reloadNow();

        assertAll(
                () -> assertFalse(result.applied()),
                () -> assertSame(live, service.snapshot()),
                () -> assertTrue(result.diagnostics().stream()
                                .anyMatch(d -> d.file() == ConfigFile.SIMULATION && d.key() == null),
                        () -> "a syntax error belongs to the file, not to a key: "
                                + result.diagnostics()),
                () -> assertTrue(result.diagnostics().getFirst().format().contains("simulation.yml"))
        );
    }

    @Test
    void aChangedSubstepRateIsReportedAsRequiringARebuild() {
        // The signal the simulation owner needs: a PhysicsState is built against a step size and its
        // stability bound is checked at construction, so a state built at 60 Hz carries a proof about
        // 60 Hz and nothing else.
        final ConfigService service = service();
        assertTrue(service.reloadNow().applied());

        ConfigFixture.write(this.directory, ConfigFile.PHYSICS, "substepRateHz: 120");
        final ConfigReloadResult result = service.reloadNow();

        assertAll(
                () -> assertTrue(result.applied(), () -> result.summary()),
                () -> assertTrue(result.requiresSimulationRebuild()),
                () -> assertEquals(120, service.snapshot().physics().substepRateHz()),
                () -> assertTrue(result.summary().contains("rebuilt"),
                        () -> "the operator has to be told as well: " + result.summary())
        );
    }

    @Test
    void aReloadThatLeavesTheSubstepRateAloneRequiresNoRebuild() {
        final ConfigService service = service();
        assertTrue(service.reloadNow().applied());

        ConfigFixture.write(this.directory, ConfigFile.PHYSICS, "windToRadians: 80.0");
        final ConfigReloadResult result = service.reloadNow();

        assertAll(
                () -> assertTrue(result.applied()),
                () -> assertFalse(result.requiresSimulationRebuild()),
                () -> assertEquals(80F, service.snapshot().physics().windToRadians())
        );
    }

    @Test
    void aSubstepRateChangeThatBreaksTheStabilityBoundIsRefusedRatherThanApplied() {
        // The decision this layer makes about a value that cannot be applied hot: it is applied, with a
        // rebuild reported - unless it is unsafe, in which case it is refused like any other bad value.
        // Deferring it to the next restart was the option rejected, because the file and the running
        // server would then disagree with nothing saying so.
        final ConfigService service = service();
        assertTrue(service.reloadNow().applied());
        final ConfigSnapshot live = service.snapshot();

        ConfigFixture.write(this.directory, ConfigFile.PHYSICS, "substepRateHz: 30");
        final ConfigReloadResult result = service.reloadNow();

        assertAll(
                () -> assertFalse(result.applied()),
                () -> assertSame(live, service.snapshot()),
                () -> assertTrue(result.diagnostics().stream()
                        .anyMatch(d -> "stiffnessToRate".equals(d.key())))
        );
    }

    @Test
    void reloadsAreSerialisedAndRevisionsStayMonotonic() throws InterruptedException {
        final ConfigService service = service();
        assertTrue(service.reloadNow().applied());

        final int reloads = 16;
        final List<CompletableFuture<ConfigReloadResult>> futures = new ArrayList<>(reloads);
        final ExecutorService pool = Executors.newFixedThreadPool(8);
        final CountDownLatch start = new CountDownLatch(1);
        try {
            for (int index = 0; index < reloads; index++) {
                futures.add(CompletableFuture
                        .runAsync(() -> await(start), pool)
                        .thenCompose(ignored -> service.reload()));
            }
            start.countDown();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
                    .orTimeout(30, TimeUnit.SECONDS)
                    .join();
        } finally {
            pool.shutdownNow();
        }

        final Set<Long> revisions = new HashSet<>();
        for (final CompletableFuture<ConfigReloadResult> future : futures) {
            final ConfigReloadResult result = future.join();
            assertTrue(result.applied(), () -> result.summary());
            revisions.add(result.snapshot().revision());
        }

        assertAll(
                () -> assertEquals(reloads, revisions.size(),
                        "two reloads published the same revision, so they were not serialised"),
                () -> assertEquals(reloads + 1L, service.snapshot().revision())
        );
    }

    @Test
    void concurrentReadersNeverSeeATornSnapshot() throws InterruptedException {
        // The reader's half of the contract. A reader does one volatile read and gets a complete,
        // internally consistent set of values; it can never observe physics from one reload and render
        // from another, which is the failure a reference per area would have allowed.
        final ConfigService service = service();
        assertTrue(service.reloadNow().applied());

        final ConfigSnapshot generationOne = service.snapshot();
        writeGenerationTwo();
        assertTrue(service.reloadNow().applied());
        final ConfigSnapshot generationTwo = service.snapshot();
        assertAll(
                () -> assertEquals(70F, generationTwo.physics().windToRadians()),
                () -> assertEquals(10, generationTwo.render().emissionHz())
        );

        final AtomicBoolean running = new AtomicBoolean(true);
        final AtomicReference<String> torn = new AtomicReference<>();
        final int readers = 4;
        final CountDownLatch ready = new CountDownLatch(readers);
        final ExecutorService pool = Executors.newFixedThreadPool(readers);
        try {
            for (int index = 0; index < readers; index++) {
                pool.execute(() -> {
                    ready.countDown();
                    while (running.get() && torn.get() == null) {
                        final ConfigSnapshot observed = service.snapshot();
                        // Comparing whole snapshots is the point: a mixture of the two generations
                        // matches neither, no matter which component was mixed in.
                        final ConfigSnapshot stripped =
                                observed.withRevision(ConfigSnapshot.UNPUBLISHED);
                        if (!stripped.equals(generationOne.withRevision(ConfigSnapshot.UNPUBLISHED))
                                && !stripped.equals(
                                generationTwo.withRevision(ConfigSnapshot.UNPUBLISHED))) {
                            torn.compareAndSet(null, "observed a snapshot that is neither generation: "
                                    + observed);
                        }
                    }
                });
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));

            // Each rewrite waits for the reload that reads it, so no reload ever parses a file that is
            // being written. The thing under test is the publish, not the file system.
            for (int round = 0; round < 24; round++) {
                if (round % 2 == 0) {
                    writeGenerationOne();
                } else {
                    writeGenerationTwo();
                }
                assertTrue(service.reloadNow().applied());
            }
        } finally {
            running.set(false);
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertAll(
                () -> assertNull(torn.get(), torn.get()),
                () -> assertEquals(generationTwo.withRevision(ConfigSnapshot.UNPUBLISHED),
                        service.snapshot().withRevision(ConfigSnapshot.UNPUBLISHED),
                        "the last generation written has to be the one left live")
        );
    }

    /** Restores the declared defaults by emptying every file. */
    private void writeGenerationOne() {
        for (final ConfigFile file : ConfigFile.values()) {
            ConfigFixture.write(this.directory, file, "# defaults");
        }
    }

    /**
     * A value changed in one file per configuration area, so a mixture is detectable wherever it came
     * from.
     */
    private void writeGenerationTwo() {
        ConfigFixture.write(this.directory, ConfigFile.PHYSICS, "windToRadians: 70.0");
        ConfigFixture.write(this.directory, ConfigFile.GAIT, "armSwingRadians: 0.6");
        ConfigFixture.write(this.directory, ConfigFile.RENDER, "emissionHz: 10");
        ConfigFixture.write(this.directory, ConfigFile.SIMULATION, "maxStepBlocks: 1.25");
        ConfigFixture.write(this.directory, ConfigFile.DIAGNOSTICS, "logRigDiagnostics: false");
        ConfigFixture.write(this.directory, ConfigFile.VIEW, "spawnBudgetPerViewer: 9");
        ConfigFixture.write(this.directory, ConfigFile.BINDING, "samplePeriodTicks: 2");
    }

    /**
     * Waits on a latch, translating the interrupt into an unchecked failure.
     *
     * @param latch the latch to wait on
     */
    private static void await(final CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The start latch was never released");
            }
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to start", exception);
        }
    }
}
