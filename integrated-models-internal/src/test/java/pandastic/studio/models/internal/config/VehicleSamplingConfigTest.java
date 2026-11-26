package pandastic.studio.models.internal.config;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pandastic.studio.models.internal.vehicle.TerrainSample;
import pandastic.studio.models.internal.vehicle.TerrainSampling;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code terrain.sample*} group of {@code vehicle.yml}: the only keys in that file about cost.
 *
 * <p>They are here because the terrain reaction is the only thing in this plugin that reads <em>blocks</em>
 * — five reads per vehicle per control step at the shipped reach — and a block read cannot be moved off
 * the thread that owns the chunk on a regionised server. So these five keys are the whole of what an
 * operator can do about that cost, and two of their bounds are more than a range check: a deadband wider
 * than the probe's own look-ahead is a key that could not take effect, and an age below the interval is a
 * saving the file would describe and the server would not make.
 */
class VehicleSamplingConfigTest {

    @TempDir
    Path directory;

    /** The validator, with the vehicle file overridden and every other file at its declared defaults. */
    private static @NotNull ConfigMapper.Result mapVehicle(@NotNull VehicleSettings vehicle) {
        return ConfigMapper.map(new PhysicsSettings() {
        }, new GaitSettings() {
        }, new RenderSettings() {
        }, new SimulationSettings() {
        }, new DiagnosticsSettings() {
        }, new AiSettings() {
        }, vehicle);
    }

    private static boolean complainsAbout(@NotNull ConfigMapper.Result result, @NotNull String key) {
        return result.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.file() == ConfigFile.VEHICLE
                        && key.equals(diagnostic.key()));
    }

    // ------------------------------------------------------------------ the declared defaults

    @Test
    void theDeclaredSamplingReachesTheSnapshot() {
        final TerrainSampling sampling = mapVehicle(new VehicleSettings() {
        }).snapshotOrThrow().vehicle().terrainSampling();

        assertAll(
                () -> assertEquals(0.25F, sampling.moveDeadbandBlocks()),
                () -> assertEquals(15F, sampling.turnDeadbandDegrees()),
                () -> assertEquals(2, sampling.minIntervalTicks()),
                () -> assertEquals(40, sampling.maxAgeTicks()),
                () -> assertTrue(sampling.onlyWhenSeen())
        );
    }

    @Test
    void theShippedDefaultsSuppressSomething() {
        // The point of the group. A shipped rule that happened to be the identity would leave every
        // vehicle on every server reading blocks on every step, which is the state these keys exist to
        // leave behind — and nothing else in the configuration would show it.
        final TerrainSampling sampling = mapVehicle(new VehicleSettings() {
        }).snapshotOrThrow().vehicle().terrainSampling();

        assertAll(
                () -> assertTrue(sampling.moveDeadbandBlocks() > 0F),
                () -> assertTrue(sampling.minIntervalTicks() > 0),
                () -> assertTrue(sampling.maxAgeTicks() >= sampling.minIntervalTicks())
        );
    }

    // ------------------------------------------------------------------ the two bounds that matter

    @Test
    void aDeadbandWiderThanTheProbesOwnLookAheadIsRefused() {
        // Structural rather than a matter of taste: past one column the vehicle is standing in the column
        // the reading described as ahead, and the sampler re-reads there whatever the file says — so a
        // larger value is a key an operator could set and watch do nothing.
        final ConfigMapper.Result result = mapVehicle(new VehicleSettings() {

            @Override
            public float terrainSampleMoveDeadbandBlocks() {
                return TerrainSample.PROBE_RUN_BLOCKS + 1F;
            }
        });

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(complainsAbout(result, "terrain.sampleMoveDeadbandBlocks"),
                        () -> result.diagnostics().toString())
        );
    }

    @Test
    void anAgeBelowTheIntervalIsRefusedNamingBothKeys() {
        // The age is the override and is read before the interval, so an age under it fires on the first
        // step past the interval and the deadband suppresses nothing. The fix is in whichever of the two
        // the operator did not mean to leave where it was, so both are named.
        final ConfigMapper.Result result = mapVehicle(new VehicleSettings() {

            @Override
            public int terrainSampleMinIntervalTicks() {
                return 10;
            }

            @Override
            public int terrainSampleMaxAgeTicks() {
                return 4;
            }
        });

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(complainsAbout(result, "terrain.sampleMinIntervalTicks")),
                () -> assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                                diagnostic.message().contains("terrain.sampleMaxAgeTicks")),
                        () -> "the complaint has to name the other key too: " + result.diagnostics())
        );
    }

    @Test
    void anAgeOfZeroIsRefusedBecauseAReadingHasToLiveAtLeastATick() {
        final ConfigMapper.Result result = mapVehicle(new VehicleSettings() {

            @Override
            public int terrainSampleMaxAgeTicks() {
                return 0;
            }
        });

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(complainsAbout(result, "terrain.sampleMaxAgeTicks"))
        );
    }

    @Test
    void aNegativeDeadbandIsRefused() {
        final ConfigMapper.Result result = mapVehicle(new VehicleSettings() {

            @Override
            public float terrainSampleMoveDeadbandBlocks() {
                return -1F;
            }
        });

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(complainsAbout(result, "terrain.sampleMoveDeadbandBlocks"))
        );
    }

    @Test
    void readingOnEveryStepIsLegalAndIsWhatZeroThresholdsMean() {
        // An operator is entitled to pay for a reading every step — it is what every vehicle did before
        // these keys existed — and the way to say so is zeroes. Only the age keeps a floor of one tick,
        // because a reading that expires inside the tick it was taken in is the same statement written
        // less clearly.
        final ConfigMapper.Result result = mapVehicle(new VehicleSettings() {

            @Override
            public float terrainSampleMoveDeadbandBlocks() {
                return 0F;
            }

            @Override
            public float terrainSampleTurnDeadbandDegrees() {
                return 0F;
            }

            @Override
            public int terrainSampleMinIntervalTicks() {
                return 0;
            }

            @Override
            public int terrainSampleMaxAgeTicks() {
                return 1;
            }
        });

        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
    }

    // ------------------------------------------------------------------ the file

    @Test
    void everySamplingValueSurvivesTheTripThroughAFileExactly() {
        // The figures pass through Bukkit's parser, which has no getFloat and widens everything. A
        // deadband that survived that approximately would move an operator's threshold silently.
        ConfigFixture.write(this.directory, ConfigFile.VEHICLE,
                "terrain:",
                "  sampleMoveDeadbandBlocks: 0.5",
                "  sampleTurnDeadbandDegrees: 7.5",
                "  sampleMinIntervalTicks: 3",
                "  sampleMaxAgeTicks: 60",
                "  sampleOnlyWhenSeen: false");
        final ConfigService service = ConfigService.inDirectory(this.directory.toFile());

        final ConfigReloadResult reloaded = service.reloadNow();
        final TerrainSampling sampling = service.snapshot().vehicle().terrainSampling();

        assertAll(
                () -> assertTrue(reloaded.applied(), reloaded::summary),
                () -> assertEquals(0.5F, sampling.moveDeadbandBlocks()),
                () -> assertEquals(7.5F, sampling.turnDeadbandDegrees()),
                () -> assertEquals(3, sampling.minIntervalTicks()),
                () -> assertEquals(60, sampling.maxAgeTicks()),
                () -> assertFalse(sampling.onlyWhenSeen())
        );
    }

    @Test
    void theFileIsCreatedWithItsSamplingKeysAndTheirComments() {
        // These comments carry more than most: the saving is invisible and the cost of the viewer gate is
        // real, so an operator who cannot read what the keys do will either leave money on the table or
        // wonder why an unobserved vehicle stopped at a kerb.
        final ConfigService service = ConfigService.inDirectory(this.directory.toFile());
        assertTrue(service.reloadNow().applied());

        final String written = ConfigFixture.read(this.directory, ConfigFile.VEHICLE);
        assertAll(
                () -> assertTrue(written.contains("  sampleMoveDeadbandBlocks:"), written),
                () -> assertTrue(written.contains("  sampleOnlyWhenSeen:"), written),
                () -> assertTrue(written.contains("the ground does not change while the vehicle has not"
                                + " moved"),
                        "the deadband's comment has to say why it is safe"),
                () -> assertTrue(written.contains("does not climb"),
                        "the viewer gate's comment has to state its cost, not just its saving")
        );
    }

    @Test
    void twoReadsWithTheConfigurationUnchangedHandBackTheSameSamplingObject() {
        // The identity guarantee this project's worst bug was about. The vehicle layer's settings source
        // hands this object through rather than rebuilding it, so a consumer comparing a bundle by
        // identity sees one configuration generation as one object.
        final ConfigService service = ConfigService.inDirectory(this.directory.toFile());
        assertTrue(service.reloadNow().applied());

        assertSame(service.snapshot().vehicle().terrainSampling(),
                service.snapshot().vehicle().terrainSampling(),
                "the sampling rule has to be identity-stable while the configuration is unchanged");
    }
}
