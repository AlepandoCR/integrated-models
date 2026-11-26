package pandastic.studio.models.internal.config;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pandastic.studio.models.api.vehicle.TerrainBounds;
import pandastic.studio.models.api.vehicle.TerrainResponse;
import pandastic.studio.models.api.vehicle.VehicleKind;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The terrain half of {@code vehicle.yml}: six ceilings, fourteen inherited defaults, and the
 * relationship between them.
 *
 * <h2>Why the ceiling-against-default check is the case worth having</h2>
 * The two halves of this file are independent keys and either can be edited alone. A plugin is entitled
 * to ship a vehicle that says nothing at all about terrain, in which case what it drives like is made
 * entirely of the {@code terrain.<kind>*} defaults and is then validated against the
 * {@code terrain.max*} ceilings — so an operator who narrows a ceiling past its own default has broken
 * every vehicle of that kind on the server, and the only symptom without this validation is a refusal
 * naming a number that appears nowhere in the plugin that asked for the vehicle. One complaint about two
 * keys in one file is the honest report, and it names the default because a narrowed ceiling was
 * deliberate.
 *
 * <h2>Why there is no key for a term a kind ignores</h2>
 * A ground vehicle's pitch is the hill it is on, so it has no acceleration pitch; nothing but a ground
 * vehicle has a surface beneath it, so nothing else has a slope, a step height or a climb duration. Those
 * ten of the twenty-four possible keys are absent rather than present-and-unread: a key no kind can use
 * is worse than no key, because an operator would set it and watch nothing happen. The assertions below
 * pin the absent terms to their identity values, which is what the mapper writes in their place.
 */
class VehicleTerrainConfigTest {

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

    private static boolean mentions(@NotNull ConfigMapper.Result result, @NotNull String text) {
        return result.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.message().contains(text));
    }

    private static @NotNull TerrainResponse defaultFor(@NotNull VehicleKind kind) {
        return mapVehicle(new VehicleSettings() {
        }).snapshotOrThrow().vehicle().terrainDefaults().forKind(kind);
    }

    // ------------------------------------------------------------------ the declared defaults

    @Test
    void theDeclaredCeilingsReachTheSnapshotNestedInsideTheVehicleBounds() {
        // Nested rather than flattened on purpose: TerrainBounds is already the operator's complete
        // statement, so one bounds object is checked against one profile object, component for component.
        final TerrainBounds terrain =
                mapVehicle(new VehicleSettings() {
                }).snapshotOrThrow().vehicleBounds().terrain();

        assertAll(
                () -> assertEquals(45F, terrain.maxBankDegrees()),
                () -> assertEquals(45F, terrain.maxSlopePitchDegrees()),
                () -> assertEquals(30F, terrain.maxAccelerationPitchDegrees()),
                () -> assertEquals(1.5F, terrain.maxStepHeightBlocks()),
                () -> assertEquals(10, terrain.maxStepDurationTicks()),
                () -> assertEquals(0.02F, terrain.minAngleBlendPerTick())
        );
    }

    @Test
    void theDeclaredGroundDefaultReachesTheSnapshot() {
        final TerrainResponse ground = defaultFor(VehicleKind.GROUND);

        assertAll(
                () -> assertEquals(8F, ground.bankDegrees()),
                () -> assertEquals(25F, ground.slopePitchDegrees()),
                () -> assertEquals(1F, ground.stepHeightBlocks()),
                () -> assertEquals(3, ground.stepDurationTicks()),
                () -> assertEquals(0.2F, ground.angleBlendPerTick()),
                // The one term a ground vehicle does not honour, and therefore the one it has no key
                // for: a car's pitch is the hill it is on, and a squat laid on top would fight it.
                () -> assertEquals(0F, ground.accelerationPitchDegrees()),
                () -> assertTrue(ground.climbs(VehicleKind.GROUND),
                        "the shipped default has to be a vehicle that gets over a kerb")
        );
    }

    @Test
    void theDeclaredFlyerDefaultReachesTheSnapshotWithNoSurfaceTerms() {
        final TerrainResponse flyer = defaultFor(VehicleKind.FLYER);

        assertAll(
                () -> assertEquals(30F, flyer.bankDegrees()),
                () -> assertEquals(10F, flyer.accelerationPitchDegrees()),
                () -> assertEquals(0.25F, flyer.angleBlendPerTick()),
                () -> assertEquals(0F, flyer.slopePitchDegrees()),
                () -> assertEquals(0F, flyer.stepHeightBlocks()),
                // One rather than zero: TerrainResponse refuses a climb shorter than the tick it is
                // applied in, so with no height to climb this is the structural minimum and means
                // nothing more than "no climb".
                () -> assertEquals(1, flyer.stepDurationTicks()),
                () -> assertFalse(flyer.climbs(VehicleKind.FLYER))
        );
    }

    @Test
    void theDeclaredWaterDefaultsReachTheSnapshot() {
        final TerrainResponse submarine = defaultFor(VehicleKind.SUBMARINE);
        final TerrainResponse boat = defaultFor(VehicleKind.BOAT);

        assertAll(
                () -> assertEquals(15F, submarine.bankDegrees()),
                () -> assertEquals(20F, submarine.accelerationPitchDegrees()),
                () -> assertEquals(0.1F, submarine.angleBlendPerTick()),
                () -> assertEquals(12F, boat.bankDegrees()),
                () -> assertEquals(8F, boat.accelerationPitchDegrees()),
                () -> assertEquals(0.15F, boat.angleBlendPerTick()),
                () -> assertEquals(0F, submarine.stepHeightBlocks()),
                () -> assertEquals(0F, boat.stepHeightBlocks()),
                // The point of the pair: water resists, so the submerged hull reaches a new attitude
                // more slowly than the surface one, and both more slowly than a flyer.
                () -> assertTrue(submarine.angleBlendPerTick() < boat.angleBlendPerTick())
        );
    }

    @Test
    void everyDeclaredDefaultIsAResponseTheDeclaredCeilingsAdmit() {
        // The assertion that would break on somebody else's edit to either half. A vehicle that inherits
        // a default is validated against these ceilings, so a default outside them is a plugin refused
        // for a value it never wrote.
        final ConfigSnapshot snapshot = mapVehicle(new VehicleSettings() {
        }).snapshotOrThrow();
        final TerrainBounds bounds = snapshot.vehicleBounds().terrain();

        assertAll(() -> {
            for (final VehicleKind kind : VehicleKind.values()) {
                final TerrainResponse response = snapshot.vehicle().terrainDefaults().forKind(kind);
                assertDoesNotThrow(() -> response.requireWithin(bounds),
                        () -> "the inherited response for " + kind + " is outside this file's ceilings");
            }
        });
    }

    // ------------------------------------------------------------------ the ceilings

    @Test
    void aLeanPastAQuarterTurnIsRefused() {
        // A geometric fact rather than taste. A quarter turn lays the model on its side and past it the
        // vehicle is upside down, so a larger angle is not a steeper lean.
        final ConfigMapper.Result result = mapVehicle(new VehicleSettings() {
            @Override
            public float terrainMaxBankDegrees() {
                return 120F;
            }
        });

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(complainsAbout(result, "terrain.maxBankDegrees"),
                        () -> result.diagnostics().toString())
        );
    }

    @Test
    void aStepCeilingTallerThanAnythingTheGameStepsIsRefused() {
        // An input-sanity ceiling: a player walks up a slab, a horse climbs a block, nothing climbs two.
        // Four blocks of step is a vehicle flying over a wall, and the honest way to ask for that is a
        // flyer.
        final ConfigMapper.Result result = mapVehicle(new VehicleSettings() {
            @Override
            public float terrainMaxStepHeightBlocks() {
                return 40F;
            }
        });

        assertTrue(complainsAbout(result, "terrain.maxStepHeightBlocks"),
                () -> result.diagnostics().toString());
    }

    @Test
    void aClimbLongerThanTheClientsOwnCountdownIsRefused() {
        // The same protocol fact that bounds an interpolation window: the server re-sends an entity's
        // position unprompted every sixty ticks and that restarts the client's countdown, so a climb
        // spread over more than fifty-nine ticks can never complete.
        final ConfigMapper.Result result = mapVehicle(new VehicleSettings() {
            @Override
            public int terrainMaxStepDurationTicks() {
                return 120;
            }
        });

        assertTrue(complainsAbout(result, "terrain.maxStepDurationTicks"),
                () -> result.diagnostics().toString());
    }

    @Test
    void aClimbCeilingOfZeroTicksIsRefusedBecauseItWouldAdmitNoResponseAtAll() {
        // Not the same statement as "no climbing", which is what a step height of zero says. A climb
        // takes at least the tick it is applied in, so a ceiling of zero refuses every response rather
        // than refusing the climb.
        final ConfigMapper.Result result = mapVehicle(new VehicleSettings() {
            @Override
            public int terrainMaxStepDurationTicks() {
                return 0;
            }
        });

        assertTrue(complainsAbout(result, "terrain.maxStepDurationTicks"),
                () -> result.diagnostics().toString());
    }

    @Test
    void aBlendFloorOfZeroIsRefused() {
        // The one terrain term whose dangerous direction is downward. A blend is the fraction of the
        // remaining difference closed each tick, so at zero a model closes none of it and never arrives
        // at the angle it was asked for — which is not a slow lean, it is no lean.
        final ConfigMapper.Result result = mapVehicle(new VehicleSettings() {
            @Override
            public float terrainMinAngleBlendPerTick() {
                return 0F;
            }
        });

        assertTrue(complainsAbout(result, "terrain.minAngleBlendPerTick"),
                () -> result.diagnostics().toString());
    }

    @Test
    void aServerThatPermitsNoTerrainAtAllIsLegalWhenItsOwnDefaultsAreInert() {
        // Zero is a statement rather than a mistake: it is what VehicleBounds.NO_TERRAIN said before
        // these keys existed, and the only response it admits is the inert one. Which is exactly why the
        // defaults have to be zeroed with it — the pair is the configuration, not either half.
        final ConfigMapper.Result result = mapVehicle(new VehicleSettings() {
            @Override
            public float terrainMaxBankDegrees() {
                return 0F;
            }

            @Override
            public float terrainMaxSlopePitchDegrees() {
                return 0F;
            }

            @Override
            public float terrainMaxAccelerationPitchDegrees() {
                return 0F;
            }

            @Override
            public float terrainMaxStepHeightBlocks() {
                return 0F;
            }

            @Override
            public float defaultGroundBankDegrees() {
                return 0F;
            }

            @Override
            public float defaultGroundSlopePitchDegrees() {
                return 0F;
            }

            @Override
            public float defaultGroundStepHeightBlocks() {
                return 0F;
            }

            @Override
            public float defaultFlyerBankDegrees() {
                return 0F;
            }

            @Override
            public float defaultFlyerAccelerationPitchDegrees() {
                return 0F;
            }

            @Override
            public float defaultSubmarineBankDegrees() {
                return 0F;
            }

            @Override
            public float defaultSubmarineAccelerationPitchDegrees() {
                return 0F;
            }

            @Override
            public float defaultBoatBankDegrees() {
                return 0F;
            }

            @Override
            public float defaultBoatAccelerationPitchDegrees() {
                return 0F;
            }
        });

        assertAll(
                () -> assertTrue(result.isSuccess(), () -> result.diagnostics().toString()),
                () -> assertTrue(result.snapshotOrThrow().vehicle().terrainDefaults().isInert(),
                        "with every angle and the step zeroed, nothing a vehicle inherits can move it")
        );
    }

    // ------------------------------------------------------------------ the defaults, and the pair

    @Test
    void aNegativeInheritedAngleIsRefused() {
        // Every angle is a magnitude: which way the model leans is taken from the sign of the input, so a
        // negative figure here does not mirror a lean, it names no lean at all.
        final ConfigMapper.Result result = mapVehicle(new VehicleSettings() {
            @Override
            public float defaultGroundBankDegrees() {
                return -5F;
            }
        });

        assertTrue(complainsAbout(result, "terrain.groundBankDegrees"),
                () -> result.diagnostics().toString());
    }

    @Test
    void aCeilingNarrowedPastItsOwnDefaultIsRefusedNamingTheDefault() {
        // The relationship inside this half of the file. The complaint names the default rather than the
        // ceiling, because an operator who narrowed a ceiling did so deliberately.
        final ConfigMapper.Result result = mapVehicle(new VehicleSettings() {
            @Override
            public float terrainMaxBankDegrees() {
                return 5F;
            }
        });

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(complainsAbout(result, "terrain.flyerBankDegrees"),
                        () -> result.diagnostics().toString()),
                () -> assertTrue(complainsAbout(result, "terrain.groundBankDegrees"),
                        () -> "every kind whose default the ceiling now excludes has to be named: "
                                + result.diagnostics()),
                () -> assertTrue(mentions(result, "terrain.maxBankDegrees"),
                        () -> "the refusal has to name the other half of the pair: "
                                + result.diagnostics()),
                () -> assertTrue(mentions(result, "inherits this default"),
                        () -> "the message has to say why an unused-looking key matters: "
                                + result.diagnostics())
        );
    }

    @Test
    void anInheritedStepTallerThanTheCeilingIsRefusedNamingTheDefault() {
        final ConfigMapper.Result result = mapVehicle(new VehicleSettings() {
            @Override
            public float terrainMaxStepHeightBlocks() {
                return 0.5F;
            }
        });

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(complainsAbout(result, "terrain.groundStepHeightBlocks"),
                        () -> result.diagnostics().toString()),
                () -> assertTrue(mentions(result, "terrain.maxStepHeightBlocks"),
                        () -> result.diagnostics().toString())
        );
    }

    @Test
    void anInheritedClimbLongerThanTheCeilingIsRefusedNamingTheDefault() {
        final ConfigMapper.Result result = mapVehicle(new VehicleSettings() {
            @Override
            public int terrainMaxStepDurationTicks() {
                return 2;
            }
        });

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(complainsAbout(result, "terrain.groundStepDurationTicks"),
                        () -> result.diagnostics().toString()),
                () -> assertTrue(mentions(result, "terrain.maxStepDurationTicks"),
                        () -> result.diagnostics().toString())
        );
    }

    @Test
    void anInheritedBlendBelowTheFloorIsRefusedNamingTheDefault() {
        // The floor's own direction, checked the other way round from every ceiling in this file: the
        // default has to be at least the floor, not at most it.
        final ConfigMapper.Result result = mapVehicle(new VehicleSettings() {
            @Override
            public float terrainMinAngleBlendPerTick() {
                return 0.5F;
            }
        });

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(complainsAbout(result, "terrain.submarineAngleBlendPerTick"),
                        () -> result.diagnostics().toString()),
                () -> assertTrue(mentions(result, "terrain.minAngleBlendPerTick"),
                        () -> result.diagnostics().toString()),
                () -> assertTrue(mentions(result, "must be at least"),
                        () -> "a floor has to read as a floor, or the operator raises the wrong key: "
                                + result.diagnostics())
        );
    }

    @Test
    void anInheritedBlendOfZeroIsRefused() {
        final ConfigMapper.Result result = mapVehicle(new VehicleSettings() {
            @Override
            public float defaultBoatAngleBlendPerTick() {
                return 0F;
            }
        });

        assertTrue(complainsAbout(result, "terrain.boatAngleBlendPerTick"),
                () -> result.diagnostics().toString());
    }

    @Test
    void anInheritedClimbOfZeroTicksIsRefused() {
        // Zero blocks is how climbing is turned off; zero ticks is a duration nothing can mean, and the
        // api refuses it at construction — so it is refused here with the key named instead.
        final ConfigMapper.Result result = mapVehicle(new VehicleSettings() {
            @Override
            public int defaultGroundStepDurationTicks() {
                return 0;
            }
        });

        assertTrue(complainsAbout(result, "terrain.groundStepDurationTicks"),
                () -> result.diagnostics().toString());
    }

    // ------------------------------------------------------------------ the file

    @Test
    void everyTerrainValueSurvivesTheTripThroughAFileExactly() {
        // The figures pass through Bukkit's parser, which has no getFloat and widens everything. A value
        // that survived that approximately rather than exactly would silently move an operator's ceiling.
        ConfigFixture.write(this.directory, ConfigFile.VEHICLE,
                "terrain:",
                "  maxBankDegrees: 60.0",
                "  maxSlopePitchDegrees: 40.0",
                "  maxAccelerationPitchDegrees: 25.0",
                "  maxStepHeightBlocks: 1.25",
                "  maxStepDurationTicks: 6",
                "  minAngleBlendPerTick: 0.05",
                "  groundBankDegrees: 10.5",
                "  groundSlopePitchDegrees: 20.0",
                "  groundStepHeightBlocks: 0.75",
                "  groundStepDurationTicks: 4",
                "  groundAngleBlendPerTick: 0.125",
                "  flyerBankDegrees: 35.0",
                "  flyerAccelerationPitchDegrees: 12.0",
                "  flyerAngleBlendPerTick: 0.3",
                "  submarineBankDegrees: 18.0",
                "  submarineAccelerationPitchDegrees: 22.0",
                "  submarineAngleBlendPerTick: 0.0625",
                "  boatBankDegrees: 14.0",
                "  boatAccelerationPitchDegrees: 9.0",
                "  boatAngleBlendPerTick: 0.2");
        final ConfigService service = ConfigService.inDirectory(this.directory.toFile());

        final ConfigReloadResult reloaded = service.reloadNow();
        final ConfigSnapshot snapshot = service.snapshot();
        final TerrainBounds bounds = snapshot.vehicleBounds().terrain();
        final TerrainResponse ground = snapshot.vehicle().terrainDefaults().forKind(VehicleKind.GROUND);
        final TerrainResponse flyer = snapshot.vehicle().terrainDefaults().forKind(VehicleKind.FLYER);
        final TerrainResponse submarine =
                snapshot.vehicle().terrainDefaults().forKind(VehicleKind.SUBMARINE);
        final TerrainResponse boat = snapshot.vehicle().terrainDefaults().forKind(VehicleKind.BOAT);

        assertAll(
                () -> assertTrue(reloaded.applied(), reloaded::summary),
                () -> assertEquals(60F, bounds.maxBankDegrees()),
                () -> assertEquals(40F, bounds.maxSlopePitchDegrees()),
                () -> assertEquals(25F, bounds.maxAccelerationPitchDegrees()),
                () -> assertEquals(1.25F, bounds.maxStepHeightBlocks()),
                () -> assertEquals(6, bounds.maxStepDurationTicks()),
                () -> assertEquals(0.05F, bounds.minAngleBlendPerTick()),
                () -> assertEquals(10.5F, ground.bankDegrees()),
                () -> assertEquals(20F, ground.slopePitchDegrees()),
                () -> assertEquals(0.75F, ground.stepHeightBlocks()),
                () -> assertEquals(4, ground.stepDurationTicks()),
                () -> assertEquals(0.125F, ground.angleBlendPerTick()),
                () -> assertEquals(35F, flyer.bankDegrees()),
                () -> assertEquals(12F, flyer.accelerationPitchDegrees()),
                () -> assertEquals(0.3F, flyer.angleBlendPerTick()),
                () -> assertEquals(18F, submarine.bankDegrees()),
                () -> assertEquals(22F, submarine.accelerationPitchDegrees()),
                () -> assertEquals(0.0625F, submarine.angleBlendPerTick()),
                () -> assertEquals(14F, boat.bankDegrees()),
                () -> assertEquals(9F, boat.accelerationPitchDegrees()),
                () -> assertEquals(0.2F, boat.angleBlendPerTick())
        );
    }

    @Test
    void theFileIsCreatedWithItsTerrainSectionAndItsComments() {
        // The comment is the entire operator-facing half of a declaration, and these keys need it more
        // than most: half of them are a term some kinds ignore, which is the first thing an operator
        // looking at a flat list of angles would get wrong.
        final ConfigService service = ConfigService.inDirectory(this.directory.toFile());
        assertTrue(service.reloadNow().applied());

        final String written = ConfigFixture.read(this.directory, ConfigFile.VEHICLE);
        assertAll(
                () -> assertTrue(written.contains("terrain:"), written),
                () -> assertTrue(written.contains("  maxBankDegrees:"), written),
                () -> assertTrue(written.contains("  groundStepDurationTicks:"), written),
                () -> assertTrue(written.contains("# Furthest any vehicle may roll into a turn"),
                        "vehicle.yml lost the comment above terrain.maxBankDegrees"),
                () -> assertTrue(written.contains("buys the smoothness"),
                        "the climb duration's comment has to say what the duration is for, or an"
                                + " operator sets it to 1 and calls the result a bug")
        );
    }

    @Test
    void twoReadsWithTheConfigurationUnchangedHandBackTheSameDefaultsObject() {
        // The identity guarantee this project's worst bug was about, applied to the one object a consumer
        // could be tempted to compare by identity. It comes for free here because the snapshot stores the
        // defaults whole rather than assembling them per read: a source that rebuilt them would tell
        // every consumer the configuration had changed on every call.
        final ConfigService service = ConfigService.inDirectory(this.directory.toFile());
        assertTrue(service.reloadNow().applied());

        assertSame(service.snapshot().vehicle().terrainDefaults(),
                service.snapshot().vehicle().terrainDefaults(),
                "the terrain defaults have to be identity-stable while the configuration is unchanged");
    }
}
