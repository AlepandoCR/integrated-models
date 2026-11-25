package pandastic.studio.models.internal.config;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seating half of {@code vehicle.yml}: its defaults, its one in-file relationship, and what it
 * deliberately does not check.
 *
 * <h2>What is not checked here, on purpose</h2>
 * Whether {@code seatCarrierEntityType} names a real entity type, and whether that type is one the server
 * would let a rider's client steer. Both need to name a platform class, and this mapper is a pure
 * validator — so the answer arrives where an entity can be spawned, and an unrecognised or refused type
 * leaves every seat unoccupiable and reported in the seat chart rather than failing a reload.
 */
class VehicleSeatConfigTest {

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
    void theDeclaredSeatDefaultsReachTheSnapshotWhole() {
        final ConfigSnapshot snapshot = mapVehicle(new VehicleSettings() {
        }).snapshotOrThrow();
        final pandastic.studio.models.internal.vehicle.SeatSettings seats = snapshot.vehicle().seats();

        assertAll(
                () -> assertEquals("ARMOR_STAND", seats.carrierEntityType()),
                () -> assertEquals(0F, seats.carrierOffsetYBlocks()),
                () -> assertEquals(4F, seats.maxCarrierStepBlocks()),
                () -> assertEquals(0.002F, seats.followDeadbandBlocks()),
                () -> assertTrue(seats.logSeatChart(), "the chart is the only place a broken seat is"
                        + " reported, so it is on until an operator turns it off"),
                () -> assertEquals(2, seats.chart().columnGap()),
                () -> assertTrue(seats.chart().boneLabel().contains("bone"),
                        "the rigger's mistake has to be named in the label that reports it")
        );
    }

    @Test
    void aDeadbandAtOrAboveTheCarrierCeilingIsRefusedAndNamesBothKeys() {
        // The one relationship inside the seating keys. Above it a carrier is either left alone or
        // teleported and never moved in place, which is seats that stutter and a scheduled teleport per
        // rider per tick.
        final ConfigMapper.Result result = mapVehicle(new VehicleSettings() {
            @Override
            public float seatMaxCarrierStepBlocks() {
                return 0.5F;
            }

            @Override
            public float seatFollowDeadbandBlocks() {
                return 0.5F;
            }
        });

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(complainsAbout(result, "seatFollowDeadbandBlocks"),
                        () -> result.diagnostics().toString()),
                () -> assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                                diagnostic.message().contains("seatMaxCarrierStepBlocks")),
                        () -> "the refusal has to name the other half of the pair: "
                                + result.diagnostics())
        );
    }

    @Test
    void aBlankCarrierTypeIsRefused() {
        final ConfigMapper.Result result = mapVehicle(new VehicleSettings() {
            @Override
            public String seatCarrierEntityType() {
                return "   ";
            }
        });

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(complainsAbout(result, "seatCarrierEntityType"))
        );
    }

    @Test
    void aCarrierTrimPastTheInputSanityCeilingIsRefused() {
        // No entity in the game sits its rider four blocks from its feet, so what this catches is a seat
        // offset written into the server's own key by mistake.
        final ConfigMapper.Result result = mapVehicle(new VehicleSettings() {
            @Override
            public float seatCarrierOffsetYBlocks() {
                return 40F;
            }
        });

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(complainsAbout(result, "seatCarrierOffsetYBlocks"))
        );
    }

    @Test
    void aNegativeCarrierTrimIsLegalBecauseATrimsSignIsADirection() {
        assertTrue(mapVehicle(new VehicleSettings() {
            @Override
            public float seatCarrierOffsetYBlocks() {
                return -0.4F;
            }
        }).isSuccess());
    }

    @Test
    void everyChartLabelMayBeEmptiedBecauseThatIsHowALineIsRemoved() {
        final ConfigMapper.Result result = mapVehicle(new VehicleSettings() {
            @Override
            public String seatChartTop() {
                return "";
            }

            @Override
            public String seatChartUnusableNote() {
                return "";
            }

            @Override
            public String seatChartBottom() {
                return "";
            }
        });

        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
    }

    @Test
    void aNegativeColumnGapIsRefused() {
        final ConfigMapper.Result result = mapVehicle(new VehicleSettings() {
            @Override
            public int seatChartColumnGap() {
                return -1;
            }
        });

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(complainsAbout(result, "seatChartColumnGap"))
        );
    }

    @Test
    void theSeatSettingsAreStoredWholeSoTheirIdentityIsStable() {
        // The identity hazard, from the other side: the vehicle layer's settings source hands this
        // object straight through, so a snapshot that rebuilt it per read would be rebuilt per control
        // step — and a consumer comparing by identity would see a different value every time.
        final ConfigSnapshot snapshot = mapVehicle(new VehicleSettings() {
        }).snapshotOrThrow();

        assertEquals(System.identityHashCode(snapshot.vehicle().seats()),
                System.identityHashCode(snapshot.vehicle().seats()));
    }
}
