package pandastic.studio.models.internal.config;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pandastic.studio.models.api.vehicle.VehicleBounds;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code vehicle.yml}: its validation, its two relationships with other keys, and the trip from a
 * declared default through a file and back.
 *
 * <h2>Why the cross-checks are the cases worth having</h2>
 * Every bound in this file is individually harmless and two combinations of them are not. A top speed
 * above the pose pipeline's teleport threshold freezes a vehicle's gait exactly when it is going
 * fastest; a top speed above the local-step threshold pays a scheduled teleport every tick. Both
 * present as something other than a configuration problem, and neither can be caught by looking at one
 * file — which is the entire reason this project publishes its configuration as a single snapshot.
 */
class VehicleConfigTest {

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

    @Test
    void theDeclaredDefaultsMapAndReachTheSnapshot() {
        final ConfigMapper.Result result = mapVehicle(new VehicleSettings() {
        });
        final ConfigSnapshot snapshot = result.snapshotOrThrow();
        final VehicleBounds bounds = snapshot.vehicleBounds();

        assertAll(
                () -> assertTrue(result.isSuccess(), () -> result.diagnostics().toString()),
                () -> assertEquals(0.5F, bounds.maxSpeedBlocksPerTick()),
                () -> assertEquals(0.2F, bounds.maxAccelerationBlocksPerTickSquared()),
                () -> assertEquals(9F, bounds.maxTurnRateDegreesPerTick()),
                () -> assertEquals(2.5F, bounds.maxBoostMultiplier()),
                () -> assertEquals(1, snapshot.vehicle().controlPeriodTicks()),
                () -> assertEquals(4F, snapshot.vehicle().maxStepTicks()),
                () -> assertEquals(4F, snapshot.vehicle().maxLocalStepBlocks()),
                () -> assertEquals(0.001F, snapshot.vehicle().restSpeedBlocksPerTick())
        );
    }

    @Test
    void theSixArgumentOverloadTakesTheDeclaredVehicleDefaults() {
        // The overload exists so every other file's tests are not made to construct a configuration they
        // do not care about. It has to agree with the long form about everything it does not say.
        final ConfigSnapshot viaOverload = ConfigMapper.map(new PhysicsSettings() {
        }, new GaitSettings() {
        }, new RenderSettings() {
        }, new SimulationSettings() {
        }, new DiagnosticsSettings() {
        }, new AiSettings() {
        }).snapshotOrThrow();
        final ConfigSnapshot viaLongForm = mapVehicle(new VehicleSettings() {
        }).snapshotOrThrow();

        assertAll(
                () -> assertEquals(viaLongForm.vehicleBounds(), viaOverload.vehicleBounds()),
                () -> assertEquals(viaLongForm.vehicle(), viaOverload.vehicle())
        );
    }

    @Test
    void aBoostCeilingBelowTheNeutralMultiplierIsRefused() {
        // One rather than zero, because a multiplier's identity is its floor: a ceiling below it would
        // refuse even a profile that declares no boost at all, which is every profile a plugin writes
        // before it thinks about boosting.
        final ConfigMapper.Result result = mapVehicle(new VehicleSettings() {
            @Override
            public float maxBoostMultiplier() {
                return 0.5F;
            }
        });

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(complainsAbout(result, "maxBoostMultiplier"),
                        () -> result.diagnostics().toString())
        );
    }

    @Test
    void aControlPeriodBelowOneTickIsRefused() {
        // The platform treats any period below one tick as one, so a zero would be a configured value
        // that is not the value that runs.
        final ConfigMapper.Result result = mapVehicle(new VehicleSettings() {
            @Override
            public int controlPeriodTicks() {
                return 0;
            }
        });

        assertTrue(complainsAbout(result, "controlPeriodTicks"),
                () -> result.diagnostics().toString());
    }

    @Test
    void aStepClampBelowTheControlPeriodIsRefusedNamingTheClamp() {
        // With the ceiling below the period, every ordinary step is clamped and every vehicle on the
        // server covers less ground than its profile says — silently, with nothing wrong except the speed.
        final ConfigMapper.Result result = mapVehicle(new VehicleSettings() {
            @Override
            public int controlPeriodTicks() {
                return 4;
            }

            @Override
            public float maxStepTicks() {
                return 2F;
            }
        });

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(complainsAbout(result, "controlPeriodTicks"),
                        () -> result.diagnostics().toString())
        );
    }

    @Test
    void aLocalStepThresholdOfAWholeChunkIsRefusedAsAPlatformLimit() {
        // Not an input-sanity figure. A chunk is sixteen blocks wide and a write is legal only inside the
        // chunks the ticking region already owns, so a displacement of a chunk width or more cannot be
        // inside the owned set whatever the entity's position within its own chunk.
        final ConfigMapper.Result result = mapVehicle(new VehicleSettings() {
            @Override
            public float maxLocalStepBlocks() {
                return 32F;
            }
        });

        assertTrue(complainsAbout(result, "maxLocalStepBlocks"),
                () -> result.diagnostics().toString());
    }

    @Test
    void aTopSpeedThatWouldLookLikeATeleportToThePosePipelineIsRefused() {
        // The first cross-file check. The pose pipeline discards a per-tick delta above maxStepBlocks as
        // a teleport, so a vehicle faster than that is handed to the gait as a motionless owner: the legs
        // stop moving precisely when the vehicle is going fastest, and nothing anywhere says why.
        final ConfigMapper.Result result = mapVehicle(new VehicleSettings() {
            @Override
            public float maxSpeedBlocksPerTick() {
                return 4F;
            }

            @Override
            public float maxBoostMultiplier() {
                return 1F;
            }

            @Override
            public float maxLocalStepBlocks() {
                return 8F;
            }
        });

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(complainsAbout(result, "maxSpeedBlocksPerTick"),
                        () -> result.diagnostics().toString()),
                () -> assertTrue(result.diagnostics().stream()
                                .anyMatch(diagnostic -> diagnostic.format().contains("maxStepBlocks")),
                        "the complaint has to name the other file's key, because the fix might be there")
        );
    }

    @Test
    void aBoostedTopSpeedPastTheLocalStepThresholdIsRefusedAgainstTheBoost() {
        // The second cross-check, and it is reported against the boost rather than the speed: an operator
        // who raised a speed ceiling meant it, and a boost is the cheaper of the two to give up.
        final ConfigMapper.Result result = mapVehicle(new VehicleSettings() {
            @Override
            public float maxSpeedBlocksPerTick() {
                return 0.3F;
            }

            @Override
            public float maxBoostMultiplier() {
                return 4F;
            }

            @Override
            public float maxLocalStepBlocks() {
                return 1F;
            }
        });

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(complainsAbout(result, "maxBoostMultiplier"),
                        () -> result.diagnostics().toString())
        );
    }

    @Test
    void theShippedDefaultsSatisfyBothCrossChecks() {
        // The one assertion that would break on somebody else's edit: the declared defaults have to be a
        // configuration this validator accepts, or ConfigSnapshot.defaults() throws and the plugin cannot
        // start at all.
        assertTrue(mapVehicle(new VehicleSettings() {
        }).isSuccess());
    }

    @Test
    void everyValueSurvivesTheTripThroughAFileExactly() {
        // The figures pass through Bukkit's parser, which has no getFloat and widens everything. A value
        // that survived that approximately rather than exactly would silently move an operator's bound.
        ConfigFixture.write(this.directory, ConfigFile.VEHICLE,
                "maxSpeedBlocksPerTick: 0.375",
                "maxAccelerationBlocksPerTickSquared: 0.125",
                "maxTurnRateDegreesPerTick: 7.5",
                "maxBoostMultiplier: 2.0",
                "controlPeriodTicks: 2",
                "maxStepTicks: 5.0",
                "maxLocalStepBlocks: 3.5",
                "restSpeedBlocksPerTick: 0.0025");
        final ConfigService service = ConfigService.inDirectory(this.directory.toFile());

        final ConfigReloadResult reloaded = service.reloadNow();
        final ConfigSnapshot snapshot = service.snapshot();

        assertAll(
                () -> assertTrue(reloaded.applied(), reloaded::summary),
                () -> assertEquals(0.375F, snapshot.vehicleBounds().maxSpeedBlocksPerTick()),
                () -> assertEquals(0.125F,
                        snapshot.vehicleBounds().maxAccelerationBlocksPerTickSquared()),
                () -> assertEquals(7.5F, snapshot.vehicleBounds().maxTurnRateDegreesPerTick()),
                () -> assertEquals(2F, snapshot.vehicleBounds().maxBoostMultiplier()),
                () -> assertEquals(2, snapshot.vehicle().controlPeriodTicks()),
                () -> assertEquals(5F, snapshot.vehicle().maxStepTicks()),
                () -> assertEquals(3.5F, snapshot.vehicle().maxLocalStepBlocks()),
                () -> assertEquals(0.0025F, snapshot.vehicle().restSpeedBlocksPerTick())
        );
    }

    @Test
    void aRefusedVehicleFileLeavesThePreviousValuesLive() {
        // All or nothing: a half-applied reload leaves the server in a state no file describes.
        final ConfigService service = ConfigService.inDirectory(this.directory.toFile());
        assertTrue(service.reloadNow().applied(), "the first load creates the files");
        final VehicleBounds before = service.vehicleBounds();

        ConfigFixture.write(this.directory, ConfigFile.VEHICLE, "maxBoostMultiplier: 0.25");
        final ConfigReloadResult refused = service.reloadNow();

        assertAll(
                () -> assertFalse(refused.applied()),
                () -> assertEquals(before, service.vehicleBounds(),
                        "the previous bounds have to stay live, by value as well as by identity"),
                () -> assertTrue(refused.diagnostics().stream()
                        .anyMatch(diagnostic -> diagnostic.file() == ConfigFile.VEHICLE))
        );
    }

    @Test
    void theFileIsCreatedWithItsComments() {
        // The comment is the entire operator-facing half of a declaration: a key with a number and no
        // explanation is a key nobody dares to change.
        final ConfigService service = ConfigService.inDirectory(this.directory.toFile());
        service.reloadNow();

        assertTrue(ConfigFixture.read(this.directory, ConfigFile.VEHICLE)
                        .contains("# Furthest a single in-place position write may reach"),
                "vehicle.yml lost the comment above maxLocalStepBlocks");
    }
}
