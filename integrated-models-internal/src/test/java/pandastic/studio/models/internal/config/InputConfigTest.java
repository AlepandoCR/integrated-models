package pandastic.studio.models.internal.config;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.input.InputTuning;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code input.yml}: five windows, two switches and a fault policy.
 *
 * <h2>What is worth testing here, and why it is not the ranges</h2>
 * The ranges are input-sanity ceilings and nothing reads them to decide how a gesture is judged, so a
 * test enumerating them would be testing the accumulator. What matters is the two things that span this
 * file and another:
 *
 * <ul>
 *   <li>the millisecond-to-nanosecond conversion, because the file is in milliseconds and every
 *       comparison downstream is against a monotonic clock — a conversion in the wrong direction gives
 *       a double-tap window of a quarter of a microsecond and a gesture that can never fire;</li>
 *   <li>the identity of the mapped value, because the snapshot carries it whole and
 *       {@code ConfigService::input} is therefore identity-stable by construction. That is the property
 *       this project has broken once, in a different layer, with no test noticing.</li>
 * </ul>
 */
class InputConfigTest {

    /** The validator, with the input file overridden and every other file at its declared defaults. */
    private static @NotNull ConfigMapper.Result mapInput(@NotNull InputSettings input) {
        return ConfigMapper.map(new PhysicsSettings() {
        }, new GaitSettings() {
        }, new RenderSettings() {
        }, new SimulationSettings() {
        }, new DiagnosticsSettings() {
        }, new AiSettings() {
        }, new VehicleSettings() {
        }, new MenuSettings() {
        }, new ViewSettings() {
        }, new BindingSettings() {
        }, new CommandSettings() {
        }, input);
    }

    private static boolean complainsAbout(@NotNull ConfigMapper.Result result, @NotNull String key) {
        return result.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.file() == ConfigFile.INPUT
                        && key.equals(diagnostic.key()));
    }

    @Test
    void theDeclaredDefaultsMapAndReachTheSnapshot() {
        final ConfigMapper.Result result = mapInput(new InputSettings() {
        });

        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
        final pandastic.studio.models.internal.input.InputSettings mapped =
                result.snapshotOrThrow().input();

        assertAll(
                () -> assertEquals(250L * InputTuning.NANOS_PER_MILLISECOND,
                        mapped.tuning().doubleTapWindowNanos()),
                () -> assertEquals(500L * InputTuning.NANOS_PER_MILLISECOND,
                        mapped.tuning().holdThresholdNanos()),
                () -> assertEquals(250L * InputTuning.NANOS_PER_MILLISECOND,
                        mapped.tuning().repeatIntervalNanos()),
                () -> assertEquals(1_500L * InputTuning.NANOS_PER_MILLISECOND,
                        mapped.tuning().staleAfterNanos()),
                () -> assertEquals(100L * InputTuning.NANOS_PER_MILLISECOND,
                        mapped.pulseVisibilityNanos()),
                () -> assertTrue(mapped.packetSourceEnabled()),
                () -> assertTrue(mapped.actionCaptureEnabled()),
                () -> assertEquals(256, mapped.maxBindings()),
                () -> assertEquals(3, mapped.faultsBeforeDisable())
        );
    }

    @Test
    void everyWindowIsConvertedFromTheFiguresTheFileStates() {
        // Not one window: all five, because a conversion applied to four of them and not the fifth is
        // exactly the kind of mistake that reads correctly in the diff.
        final ConfigMapper.Result result = mapInput(new InputSettings() {
            @Override
            public int doubleTapWindowMillis() {
                return 1;
            }

            @Override
            public int holdThresholdMillis() {
                return 2;
            }

            @Override
            public int repeatIntervalMillis() {
                return 3;
            }

            @Override
            public int staleAfterMillis() {
                return 4;
            }

            @Override
            public int actionPulseVisibilityMillis() {
                return 5;
            }
        });

        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
        final pandastic.studio.models.internal.input.InputSettings mapped =
                result.snapshotOrThrow().input();

        assertAll(
                () -> assertEquals(1_000_000L, mapped.tuning().doubleTapWindowNanos()),
                () -> assertEquals(2_000_000L, mapped.tuning().holdThresholdNanos()),
                () -> assertEquals(3_000_000L, mapped.tuning().repeatIntervalNanos()),
                () -> assertEquals(4_000_000L, mapped.tuning().staleAfterNanos()),
                () -> assertEquals(5_000_000L, mapped.pulseVisibilityNanos())
        );
    }

    @Test
    void theMappedValueIsCarriedWholeSoItsIdentityIsStable() {
        // The snapshot stores the assembled value, so two reads of the service's seam answer the same
        // object while the configuration is unchanged. A source that rebuilt it per call would satisfy
        // every declaration and break any consumer that compares by identity — which is the worst bug
        // this project has had, in a different layer.
        final ConfigSnapshot snapshot = ConfigSnapshot.defaults();

        assertSame(snapshot.input(), snapshot.input());
        assertSame(snapshot.input().tuning(), snapshot.input().tuning());
        assertSame(snapshot.input(), snapshot.withRevision(7L).input(),
                "stamping a revision must not rebuild the value a consumer compares");
    }

    @Test
    void aZeroWindowIsRefusedBecauseZeroIsADifferentBehaviour() {
        // Not a weaker setting. A zero double-tap window can never match and a zero repeat interval
        // dispatches on every publication, which is the flood the key exists to bound.
        final ConfigMapper.Result refused = mapInput(new InputSettings() {
            @Override
            public int doubleTapWindowMillis() {
                return 0;
            }
        });

        assertAll(
                () -> assertFalse(refused.isSuccess()),
                () -> assertTrue(complainsAbout(refused, "doubleTapWindowMillis"),
                        () -> refused.diagnostics().toString())
        );
    }

    @Test
    void aBindingCeilingBelowOneIsRefused() {
        final ConfigMapper.Result refused = mapInput(new InputSettings() {
            @Override
            public int maxBindings() {
                return 0;
            }
        });

        assertAll(
                () -> assertFalse(refused.isSuccess()),
                () -> assertTrue(complainsAbout(refused, "maxBindings"),
                        () -> refused.diagnostics().toString())
        );
    }

    @Test
    void theFileIsNamedSoADiagnosticCanPointAtIt() {
        assertEquals("input.yml", ConfigFile.INPUT.fileName());
    }
}
