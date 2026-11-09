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
 * {@code binding.yml}: the one rate in this project that is paid on a region thread.
 *
 * <h2>Why the floor is the case worth having</h2>
 * It is a platform constraint rather than a preference. The entity scheduler treats any period below
 * one tick as one tick, so a file saying {@code 0} would configure a value that is not the value that
 * runs — the sampler would behave exactly as if the key said one, and nothing anywhere would say so.
 * Refusing it is what keeps the configured number and the scheduled number the same number, and it is
 * the reason this file has a validator at all for a single key.
 */
class BindingConfigTest {

    @TempDir
    Path directory;

    /** The validator, with the binding file overridden and every other file at its declared defaults. */
    private static @NotNull ConfigMapper.Result mapBinding(@NotNull BindingSettings binding) {
        return ConfigMapper.map(new PhysicsSettings() {
        }, new GaitSettings() {
        }, new RenderSettings() {
        }, new SimulationSettings() {
        }, new DiagnosticsSettings() {
        }, new AiSettings() {
        }, new VehicleSettings() {
        }, new MenuSettings() {
        }, new ViewSettings() {
        }, binding);
    }

    private static boolean complainsAbout(@NotNull ConfigMapper.Result result, @NotNull String key) {
        return result.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.file() == ConfigFile.BINDING
                        && key.equals(diagnostic.key()));
    }

    @Test
    void theDeclaredDefaultMapsAndReachesTheSnapshot() {
        final ConfigMapper.Result result = mapBinding(new BindingSettings() {
        });

        assertAll(
                () -> assertTrue(result.isSuccess(), () -> result.diagnostics().toString()),
                () -> assertEquals(1L, result.snapshotOrThrow().binding().samplePeriodTicks())
        );
    }

    @Test
    void aPeriodBelowOneTickIsRefusedAsAPlatformLimit() {
        // Not an input-sanity figure. The entity scheduler floors any sub-tick delay at one tick, so a
        // zero does not sample continuously - it silently becomes one, and the file then describes a
        // configuration the server is not running.
        final ConfigMapper.Result result = mapBinding(new BindingSettings() {
            @Override
            public int samplePeriodTicks() {
                return 0;
            }
        });

        assertAll(
                () -> assertFalse(result.isSuccess()),
                () -> assertTrue(complainsAbout(result, "samplePeriodTicks"),
                        () -> result.diagnostics().toString())
        );
    }

    @Test
    void aNegativePeriodIsRefused() {
        final ConfigMapper.Result result = mapBinding(new BindingSettings() {
            @Override
            public int samplePeriodTicks() {
                return -3;
            }
        });

        assertTrue(complainsAbout(result, "samplePeriodTicks"), () -> result.diagnostics().toString());
    }

    @Test
    void aPeriodPastASecondIsRefused() {
        // An input-sanity ceiling: twenty ticks is a second, and a gait driven from an owner sample a
        // second old is not that owner's gait.
        final ConfigMapper.Result result = mapBinding(new BindingSettings() {
            @Override
            public int samplePeriodTicks() {
                return 60;
            }
        });

        assertTrue(complainsAbout(result, "samplePeriodTicks"), () -> result.diagnostics().toString());
    }

    @Test
    void theFloorItselfIsAccepted() {
        // One is the default and has to be legal: the floor is inclusive, not something the validator
        // leaves no room above.
        final ConfigMapper.Result result = mapBinding(new BindingSettings() {
            @Override
            public int samplePeriodTicks() {
                return 1;
            }
        });

        assertTrue(result.isSuccess(), () -> result.diagnostics().toString());
    }

    @Test
    void theValueSurvivesTheTripThroughAFileExactly() {
        // Three rather than one or two: a value the mapper ignored would agree with the default, and a
        // value it halved or doubled would agree with a neighbour of it.
        ConfigFixture.write(this.directory, ConfigFile.BINDING, "samplePeriodTicks: 3");
        final ConfigService service = ConfigService.inDirectory(this.directory.toFile());

        final ConfigReloadResult reloaded = service.reloadNow();

        assertAll(
                () -> assertTrue(reloaded.applied(), reloaded::summary),
                () -> assertEquals(3L, service.snapshot().binding().samplePeriodTicks()),
                // The accessor the runtime wires its sampler to has to answer the same object, because
                // the binding layer reads it on a region thread and must not allocate to do so.
                () -> assertSame(service.snapshot().binding(), service.binding())
        );
    }

    @Test
    void aSuccessfulReloadSwapsTheBindingSettings() {
        final ConfigService service = ConfigService.inDirectory(this.directory.toFile());
        assertTrue(service.reloadNow().applied(), "the first load creates the files");
        assertEquals(1L, service.binding().samplePeriodTicks());

        ConfigFixture.write(this.directory, ConfigFile.BINDING, "samplePeriodTicks: 7");
        final ConfigReloadResult applied = service.reloadNow();

        assertAll(
                () -> assertTrue(applied.applied(), applied::summary),
                () -> assertEquals(7L, service.binding().samplePeriodTicks())
        );
    }

    @Test
    void aRefusedBindingFileLeavesThePreviousSnapshotLiveByIdentity() {
        final ConfigService service = ConfigService.inDirectory(this.directory.toFile());
        ConfigFixture.write(this.directory, ConfigFile.BINDING, "samplePeriodTicks: 4");
        assertTrue(service.reloadNow().applied());
        final ConfigSnapshot live = service.snapshot();

        ConfigFixture.write(this.directory, ConfigFile.BINDING, "samplePeriodTicks: 0");
        final ConfigReloadResult refused = service.reloadNow();

        assertAll(
                () -> assertFalse(refused.applied()),
                () -> assertSame(live, service.snapshot(),
                        "the live snapshot must be the same object, not an equal one"),
                () -> assertEquals(4L, service.binding().samplePeriodTicks(),
                        "the previous period has to stay live"),
                () -> assertTrue(refused.diagnostics().stream()
                                .anyMatch(diagnostic -> diagnostic.file() == ConfigFile.BINDING),
                        () -> refused.diagnostics().toString())
        );
    }

    @Test
    void theFileIsCreatedWithItsComment() {
        // One key and one comment, and the comment is doing more work than most: the floor it describes
        // is a platform constraint, so an operator who reads it knows that one is not a cautious default
        // but the only value below two.
        final ConfigService service = ConfigService.inDirectory(this.directory.toFile());
        service.reloadNow();
        final String written = ConfigFixture.read(this.directory, ConfigFile.BINDING);

        assertAll(
                () -> assertTrue(written.contains("# Ticks between two reads of a bound model's anchor"),
                        "binding.yml lost the comment above samplePeriodTicks"),
                () -> assertTrue(written.contains("floors any sub-tick delay at one tick"),
                        "the comment has to say why one is the floor")
        );
    }
}
