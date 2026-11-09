package pandastic.studio.models.internal.config;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pandastic.studio.models.api.ai.AiActivity;
import pandastic.studio.models.api.ai.AiBounds;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ai.yml}: its validation, and the trip from a declared default through a file and back.
 *
 * <h2>Why the whole file matters as much as each key</h2>
 * Every value here is multiplied by the creature count before the server pays for it, so an operator
 * who mistypes one is going to notice as a performance problem rather than as an error. What makes that
 * diagnosable is the refusal naming the key, which is what most of these cases assert on.
 *
 * <p>The round trip has its own reason: a {@code long} tick count and a {@code double} radius both pass
 * through Bukkit's parser, which has no {@code getFloat} and widens everything. A value that survives
 * that approximately rather than exactly would silently move an operator's bound.
 */
class AiConfigTest {

    @TempDir
    Path directory;

    /** The validator, with one key overridden and the other four files at their declared defaults. */
    private static @NotNull ConfigMapper.Result mapAi(@NotNull AiSettings ai) {
        return ConfigMapper.map(new PhysicsSettings() {
        }, new GaitSettings() {
        }, new RenderSettings() {
        }, new SimulationSettings() {
        }, new DiagnosticsSettings() {
        }, ai);
    }

    private static boolean complainsAbout(@NotNull ConfigMapper.Result result, @NotNull String key) {
        return result.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.file() == ConfigFile.AI
                        && key.equals(diagnostic.key()));
    }

    @Test
    void theDeclaredDefaultsMapAndReachTheSnapshot() {
        final ConfigMapper.Result result = mapAi(new AiSettings() {
        });
        final AiBounds bounds = result.snapshotOrThrow().aiBounds();

        assertAll(
                () -> assertTrue(result.isSuccess(), () -> result.diagnostics().toString()),
                () -> assertEquals(8, bounds.maxActivities()),
                () -> assertEquals(12, bounds.maxBehavioursPerActivity()),
                () -> assertEquals(1L, bounds.minSensorPeriodTicks()),
                () -> assertEquals(100L, bounds.maxSensorPeriodTicks()),
                () -> assertEquals(32.0D, bounds.maxScanRadiusBlocks()),
                () -> assertEquals(72_000L, bounds.maxMemoryExpiryTicks()),
                () -> assertEquals(5, result.snapshotOrThrow().ai().faultsBeforeDisable())
        );
    }

    @Test
    void theFiveArgumentOverloadTakesTheDeclaredAiDefaults() {
        // The overload exists so every other file's tests are not made to construct a configuration
        // they do not care about. It has to agree with the long form about everything it does not say.
        final ConfigSnapshot viaOverload = ConfigMapper.map(new PhysicsSettings() {
        }, new GaitSettings() {
        }, new RenderSettings() {
        }, new SimulationSettings() {
        }, new DiagnosticsSettings() {
        }).snapshotOrThrow();

        assertAll(
                () -> assertEquals(mapAi(new AiSettings() {
                }).snapshotOrThrow().aiBounds(), viaOverload.aiBounds()),
                () -> assertEquals(mapAi(new AiSettings() {
                }).snapshotOrThrow().ai(), viaOverload.ai())
        );
    }

    @Test
    void anActivityCeilingAboveTheServersOwnSlotCountIsRefused() {
        // A cardinality rather than a sanity ceiling: the activity registry is frozen before any plugin
        // loads, so no file can produce more slots than it holds.
        final ConfigMapper.Result result = mapAi(new AiSettings() {
            @Override
            public int maxActivities() {
                return AiActivity.COUNT + 1;
            }
        });

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(complainsAbout(result, "maxActivities"),
                        () -> result.diagnostics().toString())
        );
    }

    @Test
    void aZeroBehaviourCeilingIsRefusedWhereTheFileIsStillTheSubject() {
        // Left to the api's record it would throw from a constructor, and every plan would then fail
        // with a message about the plan.
        final ConfigMapper.Result result = mapAi(new AiSettings() {
            @Override
            public int maxBehavioursPerActivity() {
                return 0;
            }
        });

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(complainsAbout(result, "maxBehavioursPerActivity"))
        );
    }

    @Test
    void aSensorPeriodRangeWithNoLegalValueIsRefusedNamingBothKeys() {
        final ConfigMapper.Result result = mapAi(new AiSettings() {
            @Override
            public int minSensorPeriodTicks() {
                return 40;
            }

            @Override
            public int maxSensorPeriodTicks() {
                return 2;
            }
        });

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(complainsAbout(result, "minSensorPeriodTicks")),
                () -> assertTrue(result.diagnostics().stream()
                                .anyMatch(d -> d.message().contains("maxSensorPeriodTicks")),
                        () -> "the operator edited one of the two and the fix might be in either: "
                                + result.diagnostics())
        );
    }

    @Test
    void aSensorPeriodBelowOneTickIsRefusedBecauseThePlatformWouldRoundItUp() {
        final ConfigMapper.Result result = mapAi(new AiSettings() {
            @Override
            public int minSensorPeriodTicks() {
                return 0;
            }
        });

        assertTrue(complainsAbout(result, "minSensorPeriodTicks"));
    }

    @Test
    void aZeroScanCeilingIsRefusedBecauseItMakesEveryCreatureBlind() {
        final ConfigMapper.Result result = mapAi(new AiSettings() {
            @Override
            public float maxScanRadiusBlocks() {
                return 0F;
            }
        });

        assertTrue(complainsAbout(result, "maxScanRadiusBlocks"));
    }

    @Test
    void aScanCeilingPastAnyViewDistanceIsRefusedAsAMisplacedDecimalPoint() {
        final ConfigMapper.Result result = mapAi(new AiSettings() {
            @Override
            public float maxScanRadiusBlocks() {
                return 5_000F;
            }
        });

        assertTrue(complainsAbout(result, "maxScanRadiusBlocks"));
    }

    @Test
    void aZeroFailureLimitIsRefusedBecauseABodyHasToBeAllowedToFailOnce() {
        final ConfigMapper.Result result = mapAi(new AiSettings() {
            @Override
            public int faultsBeforeDisable() {
                return 0;
            }
        });

        assertTrue(complainsAbout(result, "faultsBeforeDisable"));
    }

    @Test
    void aNegativeLogIntervalIsRefused() {
        final ConfigMapper.Result result = mapAi(new AiSettings() {
            @Override
            public float faultLogIntervalSeconds() {
                return -1F;
            }
        });

        assertTrue(complainsAbout(result, "faultLogIntervalSeconds"));
    }

    @Test
    void theLogIntervalIsConvertedToTheUnitTheThrottleComparesIn() {
        assertEquals(5_000_000_000L,
                mapAi(new AiSettings() {
                }).snapshotOrThrow().ai().faultLogIntervalNanos());
    }

    @Test
    void theFileIsCreatedWithItsCommentsAndReadBackExactly() {
        final ConfigService service = ConfigService.inDirectory(this.directory.toFile());
        assertTrue(service.reloadNow().applied(), "the first load creates the files");

        final ConfigSnapshot reread = service.reloadNow().snapshot();
        final ConfigSnapshot declared = ConfigSnapshot.defaults();

        assertAll(
                () -> assertTrue(ConfigFixture.read(this.directory, ConfigFile.AI)
                                .contains("# How many activity slots one plan may fill"),
                        "ai.yml lost the comment above maxActivities"),
                () -> assertEquals(declared.aiBounds(), reread.aiBounds(),
                        "a bound changed somewhere between the declaration and the parsed file"),
                () -> assertEquals(declared.ai(), reread.ai())
        );
    }

    @Test
    void anEditedKeyIsReadBackAndTheOtherFilesAreUndisturbed() {
        ConfigFixture.write(this.directory, ConfigFile.AI, "maxSensors: 1");

        final ConfigSnapshot snapshot =
                ConfigService.inDirectory(this.directory.toFile()).reloadNow().snapshot();
        final ConfigSnapshot declared = ConfigSnapshot.defaults();

        assertAll(
                () -> assertEquals(1, snapshot.aiBounds().maxSensors()),
                () -> assertEquals(declared.aiBounds().maxActivities(),
                        snapshot.aiBounds().maxActivities(),
                        "a key the file omits must come from the declaration"),
                () -> assertEquals(declared.physics(), snapshot.physics()),
                () -> assertEquals(declared.gait(), snapshot.gait())
        );
    }

    @Test
    void aBrokenAiFileRefusesTheWholeReloadAndLeavesThePreviousValuesLive() {
        final ConfigService service = ConfigService.inDirectory(this.directory.toFile());
        service.reloadNow();
        final ConfigSnapshot before = service.snapshot();

        ConfigFixture.corrupt(this.directory, ConfigFile.AI);
        final ConfigReloadResult refused = service.reloadNow();

        assertAll(
                () -> assertFalse(refused.applied()),
                () -> assertEquals(before, service.snapshot(),
                        "a half-applied reload leaves the server in a state no file describes"),
                () -> assertTrue(refused.diagnostics().stream()
                        .anyMatch(diagnostic -> diagnostic.file() == ConfigFile.AI))
        );
    }

    @Test
    void theServiceExposesTheBoundsAsASupplier() {
        final ConfigService service = ConfigService.inDirectory(this.directory.toFile());

        assertEquals(service.snapshot().aiBounds(), service.aiBounds());
    }
}
