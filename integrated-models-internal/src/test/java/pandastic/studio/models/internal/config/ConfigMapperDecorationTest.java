package pandastic.studio.models.internal.config;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import pandastic.studio.models.internal.decoration.DecorationSettings;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ambient field's keys, validated.
 *
 * <p>A separate class from {@link ConfigMapperTest} rather than more cases inside it: the subject is
 * one block of {@code simulation.yml} with its own reasons for each bound, and the two halves of that
 * file are read by different layers at different rates. The injection technique is the same — override
 * one {@code default} method of the declaration and leave the rest — so each case is exactly one thing
 * wrong, and the assertions are about the diagnostic rather than about which complaint came out first.
 *
 * <h2>Why every bound here is an input-sanity ceiling</h2>
 * Nothing in the field has a stability condition: it is a sum of two sines evaluated in closed form, so
 * no combination of these values can diverge. What the bounds catch is a misplaced decimal point. The
 * two exceptions are the values where zero is <em>undefined</em> rather than small — the wavelength is
 * divided by, and a gust frozen at zero frequency would leave a per-position constant offset — and both
 * are refused with a floor rather than a ceiling.
 */
class ConfigMapperDecorationTest {

    private static @NotNull ConfigMapper.Result map(@NotNull SimulationSettings simulation) {
        return ConfigMapper.map(new PhysicsSettings() {
        }, new GaitSettings() {
        }, new RenderSettings() {
        }, simulation, new DiagnosticsSettings() {
        });
    }

    /** Asserts that {@code simulation} is refused with a diagnostic naming {@code key}. */
    private static void refusedNaming(@NotNull String key, @NotNull SimulationSettings simulation) {
        final ConfigMapper.Result result = map(simulation);
        assertAll(
                () -> assertFalse(result.isSuccess(), () -> key + " must be refused"),
                () -> assertTrue(result.diagnostics().stream()
                                .anyMatch(diagnostic -> diagnostic.file() == ConfigFile.SIMULATION
                                        && key.equals(diagnostic.key())),
                        () -> "the diagnostic has to name " + key + " in simulation.yml: "
                                + result.diagnostics()));
    }

    // ------------------------------------------------------------------ the defaults

    @Test
    void theDeclaredFieldReachesTheSnapshot() {
        final ConfigSnapshot.DecorationOptions field = ConfigSnapshot.defaults().decoration();

        // The whole mapping in one assertion. A key added to the declaration and forgotten in the
        // mapper would read as a zero here, and a zero wavelength or gust rate is the one thing the
        // consumer-side record refuses outright.
        assertAll(
                () -> assertEquals(20, field.driverUpdateHz()),
                () -> assertEquals(0.02F, field.windBaseSpeedBlocksPerTick()),
                () -> assertEquals(0.04F, field.windGustSpeedBlocksPerTick()),
                () -> assertEquals(0.3F, field.windGustRateHz()),
                () -> assertEquals(2.7F, field.windGustHarmonicRatio()),
                () -> assertEquals(0.35F, field.windGustHarmonicWeight()),
                () -> assertEquals(24F, field.windGustWavelengthBlocks()),
                () -> assertEquals(45F, field.windDirectionDegrees()),
                () -> assertEquals(12F, field.windDirectionSwayDegrees()),
                () -> assertEquals(0.07F, field.windDirectionSwayRateHz()),
                () -> assertEquals(0.25F, field.windVerticalFraction()));
    }

    @Test
    void theDefaultFieldSurvivesTheConsumerSidesOwnValidation() {
        // The two validators are deliberately separate — the mapper names the file and the key, the
        // record refuses a value no file could have produced — so a default that passed one and failed
        // the other would only surface on the first server to place a decoration.
        final ConfigSnapshot.DecorationOptions field = ConfigSnapshot.defaults().decoration();

        assertNotNull(new DecorationSettings(
                field.driverUpdateHz(),
                field.windBaseSpeedBlocksPerTick(),
                field.windGustSpeedBlocksPerTick(),
                field.windGustRateHz(),
                field.windGustHarmonicRatio(),
                field.windGustHarmonicWeight(),
                field.windGustWavelengthBlocks(),
                field.windDirectionDegrees(),
                field.windDirectionSwayDegrees(),
                field.windDirectionSwayRateHz(),
                field.windVerticalFraction()));
    }

    // ------------------------------------------------------------------ refusals

    @Test
    void aPassRateBelowOneIsRefused() {
        refusedNaming("decorationDriverUpdateHz", new SimulationSettings() {
            @Override
            public int decorationDriverUpdateHz() {
                return 0;
            }
        });
    }

    @Test
    void aNegativeAirSpeedIsRefused() {
        refusedNaming("decorationWindBaseSpeedBlocksPerTick", new SimulationSettings() {
            @Override
            public float decorationWindBaseSpeedBlocksPerTick() {
                return -0.01F;
            }
        });
        refusedNaming("decorationWindGustSpeedBlocksPerTick", new SimulationSettings() {
            @Override
            public float decorationWindGustSpeedBlocksPerTick() {
                return -0.01F;
            }
        });
    }

    @Test
    void anAbsurdAirSpeedIsRefused() {
        // A thousand blocks a tick is twenty thousand blocks a second, which is a decimal point rather
        // than a preference.
        refusedNaming("decorationWindGustSpeedBlocksPerTick", new SimulationSettings() {
            @Override
            public float decorationWindGustSpeedBlocksPerTick() {
                return 1_000F;
            }
        });
    }

    @Test
    void aFrozenGustIsRefusedBecauseZeroIsNotTheWayToTurnGustingOff() {
        refusedNaming("decorationWindGustRateHz", new SimulationSettings() {
            @Override
            public float decorationWindGustRateHz() {
                return 0F;
            }
        });
    }

    @Test
    void aZeroHarmonicRatioIsRefused() {
        refusedNaming("decorationWindGustHarmonicRatio", new SimulationSettings() {
            @Override
            public float decorationWindGustHarmonicRatio() {
                return 0F;
            }
        });
    }

    @Test
    void aHarmonicWeightThatIsNotAFractionIsRefused() {
        refusedNaming("decorationWindGustHarmonicWeight", new SimulationSettings() {
            @Override
            public float decorationWindGustHarmonicWeight() {
                return 1.5F;
            }
        });
    }

    @Test
    void aZeroWavelengthIsRefusedBecauseItIsDividedBy() {
        refusedNaming("decorationWindGustWavelengthBlocks", new SimulationSettings() {
            @Override
            public float decorationWindGustWavelengthBlocks() {
                return 0F;
            }
        });
    }

    @Test
    void aBearingPastAFullTurnIsRefused() {
        refusedNaming("decorationWindDirectionDegrees", new SimulationSettings() {
            @Override
            public float decorationWindDirectionDegrees() {
                return 400F;
            }
        });
    }

    @Test
    void aSwayPastHalfATurnIsRefused() {
        refusedNaming("decorationWindDirectionSwayDegrees", new SimulationSettings() {
            @Override
            public float decorationWindDirectionSwayDegrees() {
                return 200F;
            }
        });
    }

    @Test
    void aNegativeSwayRateIsRefused() {
        refusedNaming("decorationWindDirectionSwayRateHz", new SimulationSettings() {
            @Override
            public float decorationWindDirectionSwayRateHz() {
                return -1F;
            }
        });
    }

    @Test
    void aSwayRateOfZeroIsAcceptedBecauseItMeansNoSway() {
        assertTrue(map(new SimulationSettings() {
            @Override
            public float decorationWindDirectionSwayRateHz() {
                return 0F;
            }
        }).isSuccess());
    }

    @Test
    void aLiftFractionAboveOneIsRefused() {
        refusedNaming("decorationWindVerticalFraction", new SimulationSettings() {
            @Override
            public float decorationWindVerticalFraction() {
                return 1.5F;
            }
        });
    }

    @Test
    void aCalmFieldIsAccepted() {
        // Zeroing both speeds is the supported way to switch the weather off, and it has to be legal:
        // it is also the only configuration under which a whole server of exposed decorations is free.
        assertTrue(map(new SimulationSettings() {
            @Override
            public float decorationWindBaseSpeedBlocksPerTick() {
                return 0F;
            }

            @Override
            public float decorationWindGustSpeedBlocksPerTick() {
                return 0F;
            }
        }).isSuccess());
    }

    @Test
    void aPassRateAboveTheLoopRateIsAcceptedRatherThanRefused() {
        // Deliberately not a cross-check. It is wasteful — the extra passes publish samples the
        // simulation never reads — but it is not incoherent, and refusing a configuration that works is
        // a worse failure than the one it would prevent.
        assertTrue(map(new SimulationSettings() {
            @Override
            public int decorationDriverUpdateHz() {
                return 120;
            }
        }).isSuccess());
    }
}
