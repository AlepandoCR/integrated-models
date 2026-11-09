package pandastic.studio.models.internal.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that the declared defaults are the values a fresh installation actually runs on.
 *
 * <p>Which is less obvious than it sounds. A default travels from a {@code default} method in a
 * declaration interface, through generated code, into YAML text, through Bukkit's parser — where every
 * {@code float} becomes a {@code double} on the way out and back, because
 * {@code ConfigurationSection} has no {@code getFloat} — and finally into an api value object. Four of
 * those five hops can quietly change a number. This suite runs the whole path against real files and
 * compares the result to the in-memory defaults with exact equality, so a value that survives the trip
 * approximately rather than exactly is a failure.
 */
class ConfigRoundTripTest {

    @TempDir
    Path directory;

    @Test
    void everyDeclaredDefaultSurvivesAFileRoundTrip() {
        final ConfigSnapshot declared = ConfigSnapshot.defaults();

        // No files exist, so this writes every one of them with its defaults and comments, and then the
        // next reload reads them back off disk.
        final ConfigService service = ConfigService.inDirectory(this.directory.toFile());
        assertTrue(service.reloadNow().applied(), "the first load creates the files");
        final ConfigReloadResult reread = service.reloadNow();

        assertAll(
                () -> assertTrue(reread.applied(), () -> reread.summary()),
                () -> assertEquals(declared.withRevision(ConfigSnapshot.UNPUBLISHED),
                        reread.snapshot().withRevision(ConfigSnapshot.UNPUBLISHED),
                        "a default changed somewhere between the declaration and the parsed file")
        );
    }

    @Test
    void theFirstLoadCreatesEveryFileWithItsComments() {
        final ConfigService service = ConfigService.inDirectory(this.directory.toFile());
        service.reloadNow();

        assertAll(
                () -> {
                    for (final ConfigFile file : ConfigFile.values()) {
                        assertTrue(Files.isRegularFile(this.directory.resolve(file.fileName())),
                                () -> file.fileName() + " was not created");
                    }
                },
                // The comment is the entire operator-facing half of a declaration: a key with a number
                // and no explanation is a key nobody dares to change.
                () -> assertTrue(ConfigFixture.read(this.directory, ConfigFile.PHYSICS)
                                .contains("# Fixed integrator substep rate"),
                        "physics.yml lost the comment above substepRateHz"),
                () -> assertTrue(ConfigFixture.read(this.directory, ConfigFile.RENDER)
                                .contains("# Pose updates sent per second"),
                        "render.yml lost the comment above emissionHz")
        );
    }

    @Test
    void aKeyMissingFromTheFileFallsBackToItsDeclaredDefault() {
        // One key, one line: everything else has to come from the declaration.
        ConfigFixture.write(this.directory, ConfigFile.PHYSICS, "stiffnessToRate: 200.0");

        final ConfigService service = ConfigService.inDirectory(this.directory.toFile());
        final ConfigReloadResult result = service.reloadNow();
        final ConfigSnapshot declared = ConfigSnapshot.defaults();

        assertAll(
                () -> assertTrue(result.applied(), () -> result.summary()),
                () -> assertEquals(200F, result.snapshot().physics().stiffnessToRate(),
                        "the one key the file names was ignored"),
                () -> assertEquals(declared.physics().substepRateHz(),
                        result.snapshot().physics().substepRateHz(),
                        "a key the file omits must come from the declaration"),
                () -> assertEquals(declared.gait(), result.snapshot().gait(),
                        "a file this test never wrote must be entirely defaults")
        );
    }

    @Test
    void aValueEditedInOneFileDoesNotDisturbTheOthers() {
        ConfigFixture.write(this.directory, ConfigFile.RENDER, "emissionHz: 10");

        final ConfigSnapshot snapshot = ConfigService.inDirectory(this.directory.toFile())
                .reloadNow()
                .snapshot();
        final ConfigSnapshot declared = ConfigSnapshot.defaults();

        assertAll(
                () -> assertEquals(10, snapshot.render().emissionHz()),
                () -> assertEquals(declared.physics(), snapshot.physics()),
                () -> assertEquals(declared.bindingBounds(), snapshot.bindingBounds()),
                () -> assertEquals(declared.simulation(), snapshot.simulation()),
                () -> assertEquals(declared.diagnostics(), snapshot.diagnostics())
        );
    }

    @Test
    void theAwkwardFloatsSurviveExactly() {
        // These are the values whose decimal text is not their float value: a round trip through
        // getDouble and back is where a gait would silently drift by a few parts in a million.
        final ConfigSnapshot snapshot = ConfigService.inDirectory(this.directory.toFile())
                .reloadNow()
                .snapshot();

        assertAll(
                () -> assertEquals(0.215F, snapshot.gait().fullStrideSpeedBlocksPerTick()),
                () -> assertEquals(0.035F, snapshot.gait().torsoBobRadians()),
                () -> assertEquals(1.0E-5F, snapshot.physics().restEpsilonRadians()),
                () -> assertEquals(1.0E-4F, snapshot.render().boneDirtyEpsilonRadians()),
                () -> assertEquals(1.0E-4F, snapshot.ikTolerance().degenerateTargetBlocks()),
                () -> assertEquals(0.05F, snapshot.profileBounds().minMass()),
                () -> assertEquals(0.015F, snapshot.profileDefaults().damping()),
                () -> assertEquals(0.08F, snapshot.profileDefaults().stiffness()),
                // A negative default, which is the one a text round trip could lose the sign of.
                () -> assertEquals(-1F, snapshot.profileDefaults().reachY())
        );
    }

    @Test
    void theDeclaredDefaultsPassTheirOwnValidator() {
        // A default that the validator in the same package would refuse is a defect in the declaration,
        // not operator input, so ConfigSnapshot.defaults() throws rather than reporting it. This test
        // is what notices.
        final ConfigSnapshot defaults = ConfigSnapshot.defaults();
        assertEquals(ConfigSnapshot.UNPUBLISHED, defaults.revision());
    }
}
