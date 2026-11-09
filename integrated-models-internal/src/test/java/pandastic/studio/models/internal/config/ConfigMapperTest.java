package pandastic.studio.models.internal.config;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import pandastic.studio.models.api.rig.PhysicsKind;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the validator directly, with no files involved.
 *
 * <h2>How a bad value is injected</h2>
 * By overriding one {@code default} method of a declaration interface. The other twenty-nine keep their
 * declared defaults, so each case is exactly one thing wrong — which is what lets the assertions be
 * about the diagnostic rather than about which of several complaints came out first. It also means
 * these tests need no temporary directory, no YAML and no Bukkit; the validator is a function.
 *
 * <h2>Every case asserts on the diagnostic, not just the refusal</h2>
 * A refusal that does not say which file and which key is a refusal the operator cannot act on. The
 * whole reason this layer refuses instead of clamping is that the operator is going to go and edit
 * something, so the message naming what to edit is the deliverable.
 */
class ConfigMapperTest {

    /** The five declarations this suite is about, with every other file at its declared defaults. */
    private static @NotNull ConfigMapper.Result mapWith(
            @NotNull PhysicsSettings physics,
            @NotNull GaitSettings gait,
            @NotNull RenderSettings render,
            @NotNull SimulationSettings simulation,
            @NotNull DiagnosticsSettings diagnostics
    ) {
        return ConfigMapper.map(physics, gait, render, simulation, diagnostics);
    }

    private static @NotNull ConfigMapper.Result mapPhysics(@NotNull PhysicsSettings physics) {
        return mapWith(physics, new GaitSettings() {
        }, new RenderSettings() {
        }, new SimulationSettings() {
        }, new DiagnosticsSettings() {
        });
    }

    private static @NotNull ConfigMapper.Result mapGait(@NotNull GaitSettings gait) {
        return mapWith(new PhysicsSettings() {
        }, gait, new RenderSettings() {
        }, new SimulationSettings() {
        }, new DiagnosticsSettings() {
        });
    }

    private static @NotNull ConfigMapper.Result mapRender(@NotNull RenderSettings render) {
        return mapWith(new PhysicsSettings() {
        }, new GaitSettings() {
        }, render, new SimulationSettings() {
        }, new DiagnosticsSettings() {
        });
    }

    private static @NotNull ConfigMapper.Result mapSimulation(@NotNull SimulationSettings simulation) {
        return mapWith(new PhysicsSettings() {
        }, new GaitSettings() {
        }, new RenderSettings() {
        }, simulation, new DiagnosticsSettings() {
        });
    }

    private static @NotNull ConfigMapper.Result mapDiagnostics(@NotNull DiagnosticsSettings diagnostics) {
        return mapWith(new PhysicsSettings() {
        }, new GaitSettings() {
        }, new RenderSettings() {
        }, new SimulationSettings() {
        }, diagnostics);
    }

    /**
     * Asserts that exactly one key was complained about, and which one.
     *
     * @param result the mapping result
     * @param file   the file the diagnostic has to name
     * @param key    the key the diagnostic has to name
     */
    private static void assertRefusedAt(
            @NotNull ConfigMapper.Result result,
            @NotNull ConfigFile file,
            @NotNull String key
    ) {
        assertAll(
                () -> assertFalse(result.isSuccess(), "the value should have been refused"),
                () -> assertNull(result.snapshot(), "a refused mapping must publish nothing"),
                () -> assertTrue(result.diagnostics().stream()
                                .anyMatch(d -> d.file() == file && key.equals(d.key())),
                        () -> "no diagnostic named " + file.fileName() + ":" + key + ", only "
                                + result.diagnostics()),
                () -> assertTrue(result.diagnostics().stream()
                                .filter(d -> d.file() == file && key.equals(d.key()))
                                .allMatch(d -> d.format().contains(file.fileName())
                                        && d.format().contains(key)),
                        "the formatted line must carry both the file name and the key")
        );
    }

    @Test
    void theDefaultsMap() {
        final ConfigMapper.Result result = mapPhysics(new PhysicsSettings() {
        });
        assertAll(
                () -> assertTrue(result.isSuccess()),
                () -> assertEquals(List.of(), result.diagnostics()),
                () -> assertNotNull(result.snapshot())
        );
    }

    @Test
    void anOutOfRangeValueIsRefusedNamingTheFileAndTheKey() {
        assertRefusedAt(mapPhysics(new PhysicsSettings() {
            @Override
            public float minMass() {
                return -1F;
            }
        }), ConfigFile.PHYSICS, "minMass");
    }

    @Test
    void aNotANumberValueIsRefused() {
        // Every comparison against NaN is false, so a plain range test waves it straight through -
        // and a NaN that reaches a rotation deletes the model on the client rather than looking wrong.
        assertRefusedAt(mapGait(new GaitSettings() {
            @Override
            public float torsoBobRadians() {
                return Float.NaN;
            }
        }), ConfigFile.GAIT, "torsoBobRadians");
    }

    @Test
    void anInfiniteValueIsRefused() {
        assertRefusedAt(mapGait(new GaitSettings() {
            @Override
            public float breathRateHz() {
                return Float.POSITIVE_INFINITY;
            }
        }), ConfigFile.GAIT, "breathRateHz");
    }

    @Test
    void anInvertedPairIsRefusedNamingTheKeyThatIsTooLarge() {
        assertRefusedAt(mapPhysics(new PhysicsSettings() {
            @Override
            public float minMass() {
                return 20F;
            }
        }), ConfigFile.PHYSICS, "minMass");
    }

    @Test
    void aCoarserSubstepRateIsRefusedWhenItBreaksTheSpringBound() {
        // 30 Hz is a perfectly reasonable-looking edit and it is fatal: the stiffest spring on the
        // lightest legal mass runs at about 72 rad/s, and 72 / 30 is past the integrator's bound of 2.
        final ConfigMapper.Result result = mapPhysics(new PhysicsSettings() {
            @Override
            public int substepRateHz() {
                return 30;
            }
        });
        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(result.diagnostics().stream()
                                .anyMatch(d -> "stiffnessToRate".equals(d.key())),
                        () -> "the spring bound did not fire: " + result.diagnostics()),
                () -> assertTrue(result.diagnostics().stream()
                                .anyMatch(d -> "dampingToRate".equals(d.key())),
                        () -> "the damping bound did not fire: " + result.diagnostics()),
                () -> assertTrue(result.diagnostics().stream()
                                .anyMatch(d -> d.message().contains("substepRateHz")),
                        "the message has to point at the value that actually changed")
        );
    }

    @Test
    void aFinerSubstepRateIsAccepted() {
        // The bound is one-sided. Stepping faster only ever makes the integrator safer, so raising the
        // rate must not be refused by the same check that refuses lowering it.
        assertTrue(mapPhysics(new PhysicsSettings() {
            @Override
            public int substepRateHz() {
                return 120;
            }
        }).isSuccess());
    }

    @Test
    void anUnstableDampingCoefficientIsRefused() {
        assertRefusedAt(mapPhysics(new PhysicsSettings() {
            @Override
            public float dampingToRate() {
                return 240F;
            }
        }), ConfigFile.PHYSICS, "dampingToRate");
    }

    @Test
    void anEmptyInverseKinematicsWindowIsRefused() {
        assertRefusedAt(mapGait(new GaitSettings() {
            @Override
            public float minIkDownFraction() {
                return 0.99F;
            }
        }), ConfigFile.GAIT, "minIkDownFraction");
    }

    @Test
    void aWrapCountOfZeroIsRefused() {
        // The odometer wraps after a whole number of breathing cycles, which is why the key is an int;
        // zero cycles would make it wrap every step and freeze the breathing at its first value.
        assertRefusedAt(mapGait(new GaitSettings() {
            @Override
            public int elapsedWrapBreathCycles() {
                return 0;
            }
        }), ConfigFile.GAIT, "elapsedWrapBreathCycles");
    }

    @Test
    void aDefaultStrideOutsideItsOwnBoundsIsRefused() {
        assertRefusedAt(mapGait(new GaitSettings() {
            @Override
            public float maxStrideLength() {
                return 1F;
            }
        }), ConfigFile.GAIT, "defaultStrideLength");
    }

    @Test
    void theSeededCoefficientsReachTheSnapshotAsConfigured() {
        // Three values no removed literal held, including a spring gravity off the zero the api builder
        // used to hardcode and a reach pointing up rather than down.
        final ConfigMapper.Result result = mapPhysics(new PhysicsSettings() {
            @Override
            public float defaultStiffness() {
                return 0.91F;
            }

            @Override
            public float defaultSpringGravity() {
                return 0.75F;
            }

            @Override
            public float defaultReachY() {
                return 2F;
            }
        });

        assertAll(
                () -> assertTrue(result.isSuccess(), () -> result.diagnostics().toString()),
                () -> assertEquals(0.91F, result.snapshotOrThrow().profileDefaults().stiffness()),
                () -> assertEquals(0.75F, result.snapshotOrThrow().profileDefaults()
                        .gravityFor(PhysicsKind.SPRING)),
                () -> assertEquals(2F, result.snapshotOrThrow().profileDefaults().reachY()),
                // The per-kind difference survives: only the spring moved.
                () -> assertEquals(0.25F, result.snapshotOrThrow().profileDefaults()
                        .gravityFor(PhysicsKind.PENDULUM))
        );
    }

    @Test
    void aSeededMassOutsideItsOwnBoundsIsRefusedNamingTheFileAndTheKey() {
        // maxMass is 10, so a seeded 20 refuses every bone that declares no mass of its own — and the
        // only symptom without this check is a per-bone rig warning quoting a value that appears
        // nowhere in the rig file.
        assertRefusedAt(mapPhysics(new PhysicsSettings() {
            @Override
            public float defaultMass() {
                return 20F;
            }
        }), ConfigFile.PHYSICS, "defaultMass");
    }

    @Test
    void aBoundNarrowedPastItsOwnSeedIsRefusedNamingTheSeedAndTheBound() {
        // The same invariant from the other side: the operator edited the ceiling, so the value that is
        // now wrong relative to the other two is the default.
        final ConfigMapper.Result result = mapPhysics(new PhysicsSettings() {
            @Override
            public float maxGravity() {
                return 0.1F;
            }
        });
        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(result.diagnostics().stream()
                                .anyMatch(d -> d.file() == ConfigFile.PHYSICS
                                        && "defaultPendulumGravity".equals(d.key())
                                        && d.message().contains("maxGravity")),
                        () -> "the complaint must name the seed and the bound: " + result.diagnostics()),
                // Each kind's seed is its own key, so each one that no longer fits is reported.
                () -> assertTrue(result.diagnostics().stream()
                                .anyMatch(d -> "defaultClothStripGravity".equals(d.key())),
                        () -> "only one kind was checked: " + result.diagnostics()),
                // The spring's seed is zero and still fits, so it must not be complained about.
                () -> assertFalse(result.diagnostics().stream()
                                .anyMatch(d -> "defaultSpringGravity".equals(d.key())),
                        () -> "a seed that fits was reported anyway: " + result.diagnostics())
        );
    }

    @Test
    void aSeededWindAboveItsCeilingIsRefused() {
        assertRefusedAt(mapPhysics(new PhysicsSettings() {
            @Override
            public float defaultWind() {
                return 9F;
            }
        }), ConfigFile.PHYSICS, "defaultWind");
    }

    @Test
    void aSeededReachWithNoDirectionIsRefusedNamingAllThreeKeys() {
        // The three keys are one value, so the complaint is filed against the first and names the other
        // two: a seeded bone with no reach has no axis to apply gravity or wind along, and the api
        // cannot normalise it.
        final ConfigMapper.Result result = mapPhysics(new PhysicsSettings() {
            @Override
            public float defaultReachY() {
                return 0F;
            }
        });
        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(result.diagnostics().stream()
                                .anyMatch(d -> d.file() == ConfigFile.PHYSICS
                                        && "defaultReachX".equals(d.key())
                                        && d.message().contains("defaultReachY")
                                        && d.message().contains("defaultReachZ")),
                        () -> "the other two components have to be named: " + result.diagnostics())
        );
    }

    @Test
    void aSeededFractionOutsideZeroToOneIsRefused() {
        assertRefusedAt(mapPhysics(new PhysicsSettings() {
            @Override
            public float defaultLag() {
                return 1.5F;
            }
        }), ConfigFile.PHYSICS, "defaultLag");
    }

    @Test
    void anEmissionRateAboveTheClientTickRateIsRefused() {
        // The protocol ceiling: a display entity consumes its pending-update flags once per client
        // tick, so the extra packets are discarded unseen and paid for in full.
        assertRefusedAt(mapRender(new RenderSettings() {
            @Override
            public int emissionHz() {
                return 60;
            }
        }), ConfigFile.RENDER, "emissionHz");
    }

    @Test
    void anInterpolationWindowBeyondTheResendIntervalIsRefused() {
        assertRefusedAt(mapRender(new RenderSettings() {
            @Override
            public int maxInterpolationTicks() {
                return 60;
            }
        }), ConfigFile.RENDER, "maxInterpolationTicks");
    }

    @Test
    void anInvertedInterpolationWindowIsRefused() {
        assertRefusedAt(mapRender(new RenderSettings() {
            @Override
            public int minInterpolationTicks() {
                return 30;
            }

            @Override
            public int maxInterpolationTicks() {
                return 10;
            }
        }), ConfigFile.RENDER, "minInterpolationTicks");
    }

    @Test
    void aDirtyThresholdBelowTheRestThresholdIsRefusedNamingBothFiles() {
        // The cross-file invariant: with the thresholds the wrong way round, a model the solver calls
        // settled stays above the renderer's epsilon and emits packets for the rest of its life.
        final ConfigMapper.Result result = mapRender(new RenderSettings() {
            @Override
            public float boneDirtyEpsilonRadians() {
                return 1.0E-6F;
            }
        });
        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertEquals(1, result.diagnostics().size(),
                        () -> "exactly one invariant is broken: " + result.diagnostics()),
                () -> assertEquals(ConfigFile.PHYSICS, result.diagnostics().getFirst().file()),
                () -> assertEquals("restEpsilonRadians", result.diagnostics().getFirst().key()),
                () -> assertTrue(result.diagnostics().getFirst().format()
                                .contains("boneDirtyEpsilonRadians in render.yml"),
                        () -> "the other side of the invariant has to be named too: "
                                + result.diagnostics().getFirst().format())
        );
    }

    @Test
    void aLoopSlowerThanEmissionIsRefusedNamingBothFiles() {
        final ConfigMapper.Result result = mapSimulation(new SimulationSettings() {
            @Override
            public int loopRateHz() {
                return 10;
            }
        });
        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(result.diagnostics().stream()
                                .anyMatch(d -> d.file() == ConfigFile.RENDER
                                        && "emissionHz".equals(d.key())
                                        && d.format().contains("loopRateHz in simulation.yml")),
                        () -> "the loop/emission invariant must name both sides: "
                                + result.diagnostics())
        );
    }

    @Test
    void aLoopRateThatIsNotAMultipleOfTheEmissionRateIsRefused() {
        // Not merely untidy: the interval between two updates would alternate between two lengths, and
        // the interpolation window is sized once. EmissionCadence refuses this at construction, so a
        // snapshot that carried it would turn a diagnostic into an exception thrown downstream after the
        // reload had already been reported as applied.
        final ConfigMapper.Result result = mapSimulation(new SimulationSettings() {
            @Override
            public int loopRateHz() {
                return 50;
            }
        });
        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(result.diagnostics().stream()
                                .anyMatch(d -> d.file() == ConfigFile.RENDER
                                        && "emissionHz".equals(d.key())
                                        && d.message().contains("remainder")),
                        () -> "the cadence coherence check did not fire: " + result.diagnostics())
        );
    }

    @Test
    void aLoopRateThatIsACleanMultipleIsAccepted() {
        assertTrue(mapSimulation(new SimulationSettings() {
            @Override
            public int loopRateHz() {
                return 40;
            }
        }).isSuccess());
    }

    @Test
    void anUnknownSeverityNameIsRefusedRatherThanDefaulted() {
        // An operator who wrote WARN meant WARNING. Substituting the default would leave them believing
        // a threshold is in effect that is not.
        assertRefusedAt(mapDiagnostics(new DiagnosticsSettings() {
            @Override
            public @NotNull String rigDiagnosticSeverity() {
                return "WARN";
            }
        }), ConfigFile.DIAGNOSTICS, "rigDiagnosticSeverity");
    }

    @Test
    void aSeverityNameIsCaseInsensitiveAndTrimmed() {
        final ConfigMapper.Result result = mapDiagnostics(new DiagnosticsSettings() {
            @Override
            public @NotNull String rigDiagnosticSeverity() {
                return "  error ";
            }
        });
        assertAll(
                () -> assertTrue(result.isSuccess(), () -> result.diagnostics().toString()),
                () -> assertEquals("ERROR",
                        result.snapshotOrThrow().diagnostics().rigDiagnosticSeverity().name())
        );
    }

    @Test
    void everyFailureIsReportedNotJustTheFirst() {
        // Three mistakes in one pass. An operator who broke three values would rather hear about three
        // than find out about them one restart at a time.
        final ConfigMapper.Result result = mapWith(
                new PhysicsSettings() {
                    @Override
                    public float minMass() {
                        return -1F;
                    }
                },
                new GaitSettings() {
                    @Override
                    public float breathRateHz() {
                        return 0F;
                    }
                },
                new RenderSettings() {
                    @Override
                    public int emissionHz() {
                        return 0;
                    }
                },
                new SimulationSettings() {
                },
                new DiagnosticsSettings() {
                });

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(result.diagnostics().size() >= 3,
                        () -> "only " + result.diagnostics().size() + " of three mistakes reported: "
                                + result.diagnostics()),
                () -> assertTrue(result.diagnostics().stream()
                        .anyMatch(d -> d.file() == ConfigFile.PHYSICS && "minMass".equals(d.key()))),
                () -> assertTrue(result.diagnostics().stream()
                        .anyMatch(d -> d.file() == ConfigFile.GAIT && "breathRateHz".equals(d.key()))),
                () -> assertTrue(result.diagnostics().stream()
                        .anyMatch(d -> d.file() == ConfigFile.RENDER && "emissionHz".equals(d.key())))
        );
    }

    @Test
    void aRefusedMappingRefusesToHandOverASnapshot() {
        final ConfigMapper.Result result = mapPhysics(new PhysicsSettings() {
            @Override
            public int maxBones() {
                return 0;
            }
        });
        final IllegalStateException failure =
                assertThrows(IllegalStateException.class, result::snapshotOrThrow);
        assertTrue(failure.getMessage().contains("maxBones"),
                () -> "the message has to carry the diagnostics: " + failure.getMessage());
    }
}
