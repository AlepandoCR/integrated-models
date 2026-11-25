package pandastic.studio.models.internal.rig;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.internal.config.ConfigMapper;
import pandastic.studio.models.internal.config.ConfigSnapshot;
import pandastic.studio.models.internal.config.DiagnosticsSettings;
import pandastic.studio.models.internal.config.GaitSettings;
import pandastic.studio.models.internal.config.PhysicsSettings;
import pandastic.studio.models.internal.config.RenderSettings;
import pandastic.studio.models.internal.config.SimulationSettings;
import pandastic.studio.models.internal.physics.AngularPhysicsSolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loads every rig this repository actually ships and demands a clean read.
 *
 * <h2>Why this suite exists next to {@link RigLoaderTest}</h2>
 * {@code RigLoaderTest} is about the <em>format</em>: it feeds the loader documents written to exercise
 * one rule each. Nothing in it reads a file an operator will ever be handed. So a rig file in
 * {@code rigs/} could acquire a typo, a bone whose parent is declared after it, a {@code seats:} block
 * on a creature or a physics profile on an animation-owned bone, and the whole suite would stay green —
 * the defect would first appear in a server log, as a warning nobody is watching for.
 *
 * <p>These seven files are the product: they are copied into a server's data folder and they are the
 * only rigs anyone has seen a model wear. A warning on one of them is a real defect, which is why this
 * suite asserts the empty list rather than merely that the rig loaded.
 *
 * <h2>Why the real configuration, not {@code RigFixtures}</h2>
 * Because the bounds are what decide whether a coefficient in one of these files is honoured or
 * clamped, and a clamp is a warning. {@code RigFixtures} carries figures chosen to match the shipped
 * configuration, which is exactly the kind of agreement that stops being true silently. So the settings
 * here are built from the declaration interfaces with every key at its declared default — the state a
 * server that has never been configured is in — and assembled the same way
 * {@code ModelRuntime.rigLoadSettings()} assembles them. Changing a default in {@code physics.yml}'s
 * declaration now fails here if it makes a shipped rig complain.
 *
 * <h2>Why the path arrives as a system property</h2>
 * {@code rigs/} sits at the repository root rather than in a module's resources, because it is a
 * server's data and not a module's payload: {@code ModelRuntime} reads it out of the plugin's data
 * folder. A test's working directory is its own module, so the root has to be handed in; the build file
 * sets {@code integratedModels.rigsDir}. An absent property fails loudly rather than skipping — a
 * silently skipped suite is the failure mode this suite was written to close.
 */
class ShippedRigsTest {

    /** Set by {@code integrated-models-internal/build.gradle.kts}; see the class javadoc. */
    private static final String RIGS_DIRECTORY_PROPERTY = "integratedModels.rigsDir";

    private static final String RIG_EXTENSION = ".yml";

    /**
     * The settings the shipped rigs are read against: every configuration key at its declared default.
     *
     * <p>Assembled exactly as {@code ModelRuntime.rigLoadSettings()} does, from a snapshot built by the
     * real validator. The five-argument {@code map} overload defaults the remaining files, which is
     * where the seat budget comes from.
     */
    private static @NotNull RigLoadSettings shippedSettings() {
        final ConfigSnapshot snapshot = ConfigMapper.map(
                new PhysicsSettings() {
                },
                new GaitSettings() {
                },
                new RenderSettings() {
                },
                new SimulationSettings() {
                },
                new DiagnosticsSettings() {
                }).snapshotOrThrow();

        return new RigLoadSettings(
                new AngularPhysicsSolver(snapshot.physics()),
                snapshot.rigBounds(),
                snapshot.seatBounds(),
                snapshot.profileBounds(),
                snapshot.profileDefaults(),
                snapshot.rig().stabilityMargin(),
                snapshot.rig().minReachLengthBlocks());
    }

    private static @NotNull Path rigsDirectory() {
        final String configured = System.getProperty(RIGS_DIRECTORY_PROPERTY);
        assertTrue(configured != null && !configured.isBlank(),
                () -> "-D" + RIGS_DIRECTORY_PROPERTY + " was not set; see this suite's javadoc and "
                        + "integrated-models-internal/build.gradle.kts");
        final Path directory = Path.of(configured);
        assertTrue(Files.isDirectory(directory), () -> "not a directory: " + directory);
        return directory;
    }

    private static @NotNull List<Path> shippedRigs() {
        final Path directory = rigsDirectory();
        final List<Path> files = new ArrayList<>();
        try (Stream<Path> listing = Files.list(directory)) {
            listing.filter(file -> file.getFileName().toString().endsWith(RIG_EXTENSION))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(files::add);
        } catch (final IOException unreadable) {
            throw new UncheckedIOException("Could not list " + directory, unreadable);
        }
        // Guards the vacuous pass: a dynamic-test factory that yields nothing reports success.
        assertFalse(files.isEmpty(), () -> "no " + RIG_EXTENSION + " rigs under " + directory);
        return files;
    }

    private static @NotNull String render(@NotNull RigLoadResult result) {
        final StringBuilder rendered = new StringBuilder();
        for (final RigDiagnostic diagnostic : result.diagnostics()) {
            rendered.append("\n  ").append(diagnostic.format());
        }
        return rendered.toString();
    }

    /**
     * One dynamic test per shipped rig, so a failure names the file rather than the folder.
     *
     * @return a test per {@code .yml} under {@code rigs/}
     */
    @TestFactory
    Stream<DynamicTest> everyShippedRigLoadsWithNoDiagnostics() {
        final RigLoadSettings settings = shippedSettings();
        return shippedRigs().stream().map(file -> DynamicTest.dynamicTest(
                file.getFileName().toString(),
                () -> {
                    final RigLoadResult result = RigLoader.loadFile(file, settings);
                    assertTrue(result.diagnostics().isEmpty(),
                            () -> file.getFileName() + " did not load cleanly:" + render(result));
                    // isSuccess() is implied by an empty diagnostic list - a refusal is an ERROR - but
                    // asserting it keeps the test honest if that ever stops being true.
                    assertTrue(result.isSuccess(), () -> file.getFileName() + " produced no rig");
                    final Rig rig = result.rigOrThrow();
                    assertTrue(rig.boneCount() > 0, () -> file.getFileName() + " declares no bones");
                }));
    }
}
