package pandastic.studio.models.internal.config;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code view.yml}: its bounds, the three relationships between its own keys, and the trip from a
 * declared default through a file and back.
 *
 * <h2>Why the three relationships are the cases worth having</h2>
 * Every key in this file is individually harmless and three combinations of them are not, and all three
 * fail the same way: the hysteresis quietly stops working and a model strobes — taken away and handed
 * back on alternate passes, with every hand-back re-sending every bone. That presents as a rendering
 * fault rather than as a configuration one, which is exactly the class of problem a validator exists to
 * convert into a sentence.
 *
 * <h2>Why the fixture numbers are odd</h2>
 * Every value written to disk here disagrees with its declared default, and in both directions. A test
 * using plausible figures cannot tell a mapper that reads the file from one that ignores it and happens
 * to agree.
 */
class ViewConfigTest {

    @TempDir
    Path directory;

    /** The validator, with the view file overridden and every other file at its declared defaults. */
    private static @NotNull ConfigMapper.Result mapView(@NotNull ViewSettings view) {
        return ConfigMapper.map(new PhysicsSettings() {
        }, new GaitSettings() {
        }, new RenderSettings() {
        }, new SimulationSettings() {
        }, new DiagnosticsSettings() {
        }, new AiSettings() {
        }, new VehicleSettings() {
        }, new MenuSettings() {
        }, view, new BindingSettings() {
        });
    }

    private static boolean complainsAbout(@NotNull ConfigMapper.Result result, @NotNull String key) {
        return result.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.file() == ConfigFile.VIEW
                        && key.equals(diagnostic.key()));
    }

    /** Whether some complaint about the view file mentions {@code text} anywhere in its line. */
    private static boolean mentions(@NotNull ConfigMapper.Result result, @NotNull String text) {
        return result.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.file() == ConfigFile.VIEW
                        && diagnostic.format().contains(text));
    }

    @Test
    void theDeclaredDefaultsMapAndReachTheSnapshot() {
        final ConfigMapper.Result result = mapView(new ViewSettings() {
        });
        final ConfigSnapshot snapshot = result.snapshotOrThrow();

        assertAll(
                () -> assertTrue(result.isSuccess(), () -> result.diagnostics().toString()),
                () -> assertEquals(48D, snapshot.view().viewRangeBlocks()),
                () -> assertEquals(16D, snapshot.view().nearRadiusBlocks()),
                () -> assertEquals(8D, snapshot.view().exitMarginBlocks()),
                () -> assertEquals(65D, snapshot.view().coneEnterDegrees()),
                () -> assertEquals(78D, snapshot.view().coneExitDegrees()),
                () -> assertEquals(4D, snapshot.view().cameraOffsetBlocks()),
                () -> assertEquals(2D, snapshot.view().defaultModelRadiusBlocks()),
                () -> assertEquals(2D, snapshot.view().maxViewerSpeedBlocksPerTick()),
                () -> assertEquals(2, snapshot.view().sampleIntervalTicks()),
                () -> assertTrue(snapshot.view().honourClientViewDistance()),
                () -> assertEquals(4, snapshot.view().spawnBudgetPerViewer())
        );
    }

    @Test
    void theShippedDefaultsSatisfyAllThreeRelationships() {
        // The assertion that would break on somebody else's edit: the declared defaults have to be a
        // configuration this validator accepts, or ConfigSnapshot.defaults() throws and the plugin
        // cannot start at all. The near radius is also the one default that is derived rather than
        // chosen - it is where a model of the assumed radius stops subtending more than the angular
        // hysteresis band - so the arithmetic behind it is asserted rather than left in a comment.
        final ConfigSnapshot defaults = mapView(new ViewSettings() {
        }).snapshotOrThrow();
        final double subtended = Math.toDegrees(Math.asin(
                defaults.view().defaultModelRadiusBlocks() / defaults.view().nearRadiusBlocks()));
        final double band = defaults.view().coneExitDegrees() - defaults.view().coneEnterDegrees();

        assertAll(
                () -> assertTrue(subtended < band,
                        () -> "a model at the near radius subtends " + subtended
                                + " degrees, which has to stay inside the " + band
                                + " degrees of angular hysteresis"),
                () -> assertTrue(defaults.view().exitMarginBlocks()
                                > defaults.view().maxViewerSpeedBlocksPerTick()
                                * defaults.view().sampleIntervalTicks(),
                        "the shipped margin has to clear the shipped sampling reach")
        );
    }

    @Test
    void theEightArgumentOverloadTakesTheDeclaredViewAndBindingDefaults() {
        // The overload exists so every other file's tests are not made to construct two configurations
        // they do not care about. It has to agree with the long form about everything it does not say.
        final ConfigSnapshot viaOverload = ConfigMapper.map(new PhysicsSettings() {
        }, new GaitSettings() {
        }, new RenderSettings() {
        }, new SimulationSettings() {
        }, new DiagnosticsSettings() {
        }, new AiSettings() {
        }, new VehicleSettings() {
        }, new MenuSettings() {
        }).snapshotOrThrow();
        final ConfigSnapshot viaLongForm = mapView(new ViewSettings() {
        }).snapshotOrThrow();

        assertAll(
                () -> assertEquals(viaLongForm.view(), viaOverload.view()),
                () -> assertEquals(viaLongForm.binding(), viaOverload.binding())
        );
    }

    // ------------------------------------------------------------------ the individual bounds

    @Test
    void aViewRangeOfZeroIsRefused() {
        // Zero is not a tiny range, it is no range: every model would be beyond it and the layer would
        // be a loop that hides everything.
        final ConfigMapper.Result result = mapView(new ViewSettings() {
            @Override
            public double viewRangeBlocks() {
                return 0D;
            }
        });

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(complainsAbout(result, "viewRangeBlocks"),
                        () -> result.diagnostics().toString())
        );
    }

    @Test
    void aViewRangePastTheLargestServerViewDistanceIsRefused() {
        // Not an input-sanity figure: the largest view distance a server may run is 32 chunks, so past
        // 512 blocks nothing reaches anybody however generous the culler is.
        final ConfigMapper.Result result = mapView(new ViewSettings() {
            @Override
            public double viewRangeBlocks() {
                return 900D;
            }
        });

        assertTrue(complainsAbout(result, "viewRangeBlocks"), () -> result.diagnostics().toString());
    }

    @Test
    void aNegativeCameraOffsetIsRefusedButZeroIsNot() {
        // Zero means "assume first person", which is a legitimate thing for a server that forces it.
        // Negative would move the camera behind the player's own eye, which no mode does.
        final ConfigMapper.Result negative = mapView(new ViewSettings() {
            @Override
            public double cameraOffsetBlocks() {
                return -1.5D;
            }
        });
        final ConfigMapper.Result zero = mapView(new ViewSettings() {
            @Override
            public double cameraOffsetBlocks() {
                return 0D;
            }
        });

        assertAll(
                () -> assertTrue(complainsAbout(negative, "cameraOffsetBlocks"),
                        () -> negative.diagnostics().toString()),
                () -> assertTrue(zero.isSuccess(), () -> zero.diagnostics().toString())
        );
    }

    @Test
    void aHalfAngleAtNinetyDegreesIsRefusedAsDeletingTheTest() {
        // Geometry rather than taste. The angular test admits a model near the look axis in either
        // direction, so at 90 the two halves of the bowtie meet across the perpendicular plane and
        // every direction is inside - an operator who sets it there has removed the test, not widened
        // it, and nothing else in the file would hint at that.
        final ConfigMapper.Result result = mapView(new ViewSettings() {
            @Override
            public double coneExitDegrees() {
                return 90D;
            }
        });

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(complainsAbout(result, "coneExitDegrees"),
                        () -> result.diagnostics().toString()),
                () -> assertTrue(mentions(result, "no angular test"),
                        () -> "the complaint has to say what setting it there does: "
                                + result.diagnostics())
        );
    }

    @Test
    void anEnterAngleOfZeroIsRefused() {
        final ConfigMapper.Result result = mapView(new ViewSettings() {
            @Override
            public double coneEnterDegrees() {
                return 0D;
            }
        });

        assertTrue(complainsAbout(result, "coneEnterDegrees"), () -> result.diagnostics().toString());
    }

    @Test
    void anAngleThatIsNotANumberIsRefusedRatherThanWavedThrough() {
        // Every comparison against NaN is false, so a plain range test admits it - and a NaN that
        // reaches the cosine makes every visibility decision false, which reads as the layer being
        // broken rather than as a configuration mistake.
        final ConfigMapper.Result result = mapView(new ViewSettings() {
            @Override
            public double coneEnterDegrees() {
                return Double.NaN;
            }
        });

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(complainsAbout(result, "coneEnterDegrees"),
                        () -> result.diagnostics().toString()),
                () -> assertTrue(mentions(result, "is not a number"),
                        () -> result.diagnostics().toString())
        );
    }

    @Test
    void aSamplingIntervalBelowOneTickIsRefused() {
        // The platform treats any period below one tick as one, so a zero would be a configured value
        // that is not the value that runs.
        final ConfigMapper.Result result = mapView(new ViewSettings() {
            @Override
            public int sampleIntervalTicks() {
                return 0;
            }
        });

        assertTrue(complainsAbout(result, "sampleIntervalTicks"),
                () -> result.diagnostics().toString());
    }

    @Test
    void aSpawnBudgetOfZeroIsRefused() {
        // Zero is not a tight budget, it is a layer that defers every show for ever: a model would be
        // decided visible and never sent.
        final ConfigMapper.Result result = mapView(new ViewSettings() {
            @Override
            public int spawnBudgetPerViewer() {
                return 0;
            }
        });

        assertTrue(complainsAbout(result, "spawnBudgetPerViewer"),
                () -> result.diagnostics().toString());
    }

    @Test
    void anAssumedViewerSpeedOfZeroIsRefused() {
        // Zero would make the sampling reach zero too, so the margin cross-check would pass for any
        // margin at all - the one bad value in this file that disables another file's guard rather than
        // merely being wrong.
        final ConfigMapper.Result result = mapView(new ViewSettings() {
            @Override
            public double maxViewerSpeedBlocksPerTick() {
                return 0D;
            }
        });

        assertTrue(complainsAbout(result, "maxViewerSpeedBlocksPerTick"),
                () -> result.diagnostics().toString());
    }

    // ------------------------------------------------------------------ the three relationships

    @Test
    void aNearRadiusOutsideTheRangeIsRefusedNamingBothKeys() {
        final ConfigMapper.Result result = mapView(new ViewSettings() {
            @Override
            public double viewRangeBlocks() {
                return 30D;
            }

            @Override
            public double nearRadiusBlocks() {
                return 30D;
            }
        });

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(complainsAbout(result, "nearRadiusBlocks"),
                        () -> result.diagnostics().toString()),
                () -> assertTrue(mentions(result, "viewRangeBlocks"),
                        () -> "the complaint has to name the other key, because the fix might be there: "
                                + result.diagnostics()),
                () -> assertTrue(mentions(result, "angular test could never run"),
                        () -> result.diagnostics().toString())
        );
    }

    @Test
    void anExitAngleThatIsNotWiderThanTheEnterAngleIsRefusedNamingBothKeys() {
        // Equal is refused, not just inverted: with no band at all, a model sitting on the boundary is
        // taken away and handed back on alternate passes.
        final ConfigMapper.Result result = mapView(new ViewSettings() {
            @Override
            public double coneEnterDegrees() {
                return 55D;
            }

            @Override
            public double coneExitDegrees() {
                return 55D;
            }
        });

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(complainsAbout(result, "coneEnterDegrees"),
                        () -> result.diagnostics().toString()),
                () -> assertTrue(mentions(result, "coneExitDegrees"),
                        () -> result.diagnostics().toString()),
                () -> assertTrue(mentions(result, "re-sends every bone"),
                        () -> result.diagnostics().toString())
        );
    }

    @Test
    void anExitMarginAViewerCanCrossBetweenTwoSamplesIsRefusedNamingAllThreeKeys() {
        // The anti-flap guarantee, and the only relationship in the file that is about a product: the
        // figure at fault belongs to none of the three keys on its own, which is exactly why the
        // complaint spells the arithmetic out instead of quoting a bound.
        final ConfigMapper.Result result = mapView(new ViewSettings() {
            @Override
            public double exitMarginBlocks() {
                return 6D;
            }

            @Override
            public double maxViewerSpeedBlocksPerTick() {
                return 1.5D;
            }

            @Override
            public int sampleIntervalTicks() {
                return 6;
            }
        });

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(complainsAbout(result, "exitMarginBlocks"),
                        () -> result.diagnostics().toString()),
                () -> assertTrue(mentions(result, "maxViewerSpeedBlocksPerTick 1.5"),
                        () -> result.diagnostics().toString()),
                () -> assertTrue(mentions(result, "sampleIntervalTicks 6"),
                        () -> result.diagnostics().toString()),
                () -> assertTrue(mentions(result, "is 6.0 blocks"),
                        () -> result.diagnostics().toString()),
                () -> assertTrue(mentions(result, "the 9.0 blocks a viewer covers"),
                        () -> "the computed reach has to be in the message, because it is the number"
                                + " that is too large and it is in none of the three keys: "
                                + result.diagnostics())
        );
    }

    @Test
    void aMarginExactlyEqualToTheSamplingReachIsRefused() {
        // Exclusive, not inclusive. A viewer who covers exactly the band lands on the boundary every
        // sample, which is the strobe this check exists to prevent rather than the edge of it.
        final ConfigMapper.Result result = mapView(new ViewSettings() {
            @Override
            public double exitMarginBlocks() {
                return 5D;
            }

            @Override
            public double maxViewerSpeedBlocksPerTick() {
                return 2.5D;
            }

            @Override
            public int sampleIntervalTicks() {
                return 2;
            }
        });

        assertTrue(complainsAbout(result, "exitMarginBlocks"), () -> result.diagnostics().toString());
    }

    // ------------------------------------------------------------------ the file

    @Test
    void everyValueSurvivesTheTripThroughAFileExactly() {
        // Eleven values, every one of them disagreeing with its declared default, and in both
        // directions. The figures pass through Bukkit's parser on the way out and back; a value that
        // survived that approximately rather than exactly would silently move an operator's bound.
        ConfigFixture.write(this.directory, ConfigFile.VIEW,
                "viewRangeBlocks: 37.5",
                "nearRadiusBlocks: 11.25",
                "exitMarginBlocks: 13.75",
                "coneEnterDegrees: 41.5",
                "coneExitDegrees: 83.25",
                "cameraOffsetBlocks: 6.5",
                "defaultModelRadiusBlocks: 0.75",
                "maxViewerSpeedBlocksPerTick: 1.125",
                "sampleIntervalTicks: 7",
                "honourClientViewDistance: false",
                "spawnBudgetPerViewer: 23");
        final ConfigService service = ConfigService.inDirectory(this.directory.toFile());

        final ConfigReloadResult reloaded = service.reloadNow();
        final ConfigSnapshot snapshot = service.snapshot();

        assertAll(
                () -> assertTrue(reloaded.applied(), reloaded::summary),
                () -> assertEquals(37.5D, snapshot.view().viewRangeBlocks()),
                () -> assertEquals(11.25D, snapshot.view().nearRadiusBlocks()),
                () -> assertEquals(13.75D, snapshot.view().exitMarginBlocks()),
                () -> assertEquals(41.5D, snapshot.view().coneEnterDegrees()),
                () -> assertEquals(83.25D, snapshot.view().coneExitDegrees()),
                () -> assertEquals(6.5D, snapshot.view().cameraOffsetBlocks()),
                () -> assertEquals(0.75D, snapshot.view().defaultModelRadiusBlocks()),
                () -> assertEquals(1.125D, snapshot.view().maxViewerSpeedBlocksPerTick()),
                () -> assertEquals(7, snapshot.view().sampleIntervalTicks()),
                () -> assertFalse(snapshot.view().honourClientViewDistance()),
                () -> assertEquals(23, snapshot.view().spawnBudgetPerViewer()),
                // The accessor the runtime wires its culler to has to answer the same object.
                () -> assertSame(snapshot.view(), service.view())
        );
    }

    @Test
    void aSuccessfulReloadSwapsTheViewSettings() {
        final ConfigService service = ConfigService.inDirectory(this.directory.toFile());
        assertTrue(service.reloadNow().applied(), "the first load creates the files");
        // Written in full: the view layer's value type and the declaration of view.yml share a simple
        // name, which is the deliberate shadow this project uses for every file whose consumer-side
        // value has no api counterpart.
        final pandastic.studio.models.internal.view.ViewSettings before = service.view();

        ConfigFixture.write(this.directory, ConfigFile.VIEW, "spawnBudgetPerViewer: 17");
        final ConfigReloadResult applied = service.reloadNow();

        assertAll(
                () -> assertTrue(applied.applied(), applied::summary),
                () -> assertEquals(17, service.view().spawnBudgetPerViewer()),
                () -> assertEquals(before.viewRangeBlocks(), service.view().viewRangeBlocks(),
                        "a key the file omits has to come back from the declaration")
        );
    }

    @Test
    void aRefusedViewFileLeavesThePreviousSnapshotLiveByIdentity() {
        // All or nothing: a half-applied reload leaves the server culling on a combination no file
        // describes, and this file is one where that combination is a strobe rather than a wrong number.
        final ConfigService service = ConfigService.inDirectory(this.directory.toFile());
        assertTrue(service.reloadNow().applied(), "the first load creates the files");
        final ConfigSnapshot live = service.snapshot();

        ConfigFixture.write(this.directory, ConfigFile.VIEW,
                "spawnBudgetPerViewer: 17", "nearRadiusBlocks: 200.0");
        final ConfigReloadResult refused = service.reloadNow();

        assertAll(
                () -> assertFalse(refused.applied()),
                () -> assertSame(live, service.snapshot(),
                        "the live snapshot must be the same object, not an equal one"),
                () -> assertSame(live, refused.snapshot(),
                        "a refused result has to report what is still live"),
                () -> assertEquals(4, service.view().spawnBudgetPerViewer(),
                        "the half of the file that was fine must not have been applied either"),
                () -> assertTrue(refused.diagnostics().stream()
                                .anyMatch(diagnostic -> diagnostic.file() == ConfigFile.VIEW
                                        && "nearRadiusBlocks".equals(diagnostic.key())),
                        () -> refused.diagnostics().toString())
        );
    }

    @Test
    void theFileIsCreatedWithItsComments() {
        // The comment is the entire operator-facing half of a declaration: a key with a number and no
        // explanation is a key nobody dares to change, and nobody would guess what the assumed viewer
        // speed is for.
        final ConfigService service = ConfigService.inDirectory(this.directory.toFile());
        service.reloadNow();
        final String written = ConfigFixture.read(this.directory, ConfigFile.VIEW);

        assertAll(
                () -> assertTrue(written.contains("# Fastest a viewer is assumed to travel"),
                        "view.yml lost the comment above maxViewerSpeedBlocksPerTick"),
                () -> assertTrue(written.contains("Nothing is clamped to it"),
                        "the comment has to say that the key is a proof obligation, not a limit"),
                () -> assertTrue(written.contains("# Half-angle beyond which a shown model is taken"),
                        "view.yml lost the comment above coneExitDegrees")
        );
    }
}
