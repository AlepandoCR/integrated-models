package pandastic.studio.models.internal.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code menu.yml}: that it exists, that every label in it reaches a screen, and what it refuses.
 *
 * <p>The refusals are the half worth having. A blank label is <em>invisible</em> rather than wrong — an
 * operator who empties the Wear button's label gets a button with no text on it and nothing saying
 * why — so the keys where blank is not a legitimate value are refused with the key named. The ones where
 * blank is how a line is turned off are left alone, and that asymmetry is what these tests pin down.
 */
class MenuConfigTest {

    @TempDir
    Path directory;

    private ConfigService service() {
        return ConfigService.inDirectory(this.directory.toFile());
    }

    private static boolean names(ConfigReloadResult result, String key) {
        return result.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.file() == ConfigFile.MENU
                        && key.equals(diagnostic.key()));
    }

    // ------------------------------------------------------------------ the file

    @Test
    void theFileIsCreatedWithItsCommentsAndNestedSections() {
        assertTrue(service().reloadNow().applied());

        final String written = ConfigFixture.read(this.directory, ConfigFile.MENU);
        assertAll(
                () -> assertTrue(Files.exists(this.directory.resolve("menu.yml"))),
                () -> assertTrue(written.contains("picker:"), written),
                () -> assertTrue(written.contains("  title:"), written),
                () -> assertTrue(written.contains("appearance:"), written),
                () -> assertTrue(written.contains("animation:"), written),
                () -> assertTrue(written.contains("session:"), written),
                () -> assertTrue(written.contains("# Title of the model picker"), written));
    }

    @Test
    void theDeclaredDefaultsMapAndReachTheSnapshot() {
        final ConfigService service = service();
        assertTrue(service.reloadNow().applied());

        final var menu = service.menu();
        assertAll(
                () -> assertEquals(180F, menu.session().timeoutSeconds()),
                () -> assertEquals(256, menu.session().maxSessions()),
                () -> assertEquals(8, menu.picker().pageSize()),
                () -> assertEquals(8, menu.animation().pageSize()),
                () -> assertEquals(0, menu.appearance().minChannel()),
                () -> assertEquals(255, menu.appearance().maxChannel()),
                () -> assertEquals(0.5F, menu.appearance().minScale()),
                () -> assertEquals(4F, menu.appearance().maxScale()),
                () -> assertTrue(menu.picker().hasItemModel()),
                () -> assertFalse(menu.layout().closeLabel().isBlank()));
    }

    @Test
    void anEditedKeyIsReadBackAndTheOtherFilesAreUndisturbed() {
        ConfigFixture.write(this.directory, ConfigFile.MENU,
                "picker:",
                "  pageSize: 3",
                "layout:",
                "  buttonWidth: 77");

        final ConfigService service = service();
        assertTrue(service.reloadNow().applied());

        assertAll(
                () -> assertEquals(3, service.menu().picker().pageSize()),
                () -> assertEquals(77, service.menu().layout().buttonWidth()),
                // Untouched keys keep their declared defaults, which is what makes a partial file legal.
                () -> assertEquals(8, service.menu().animation().pageSize()),
                () -> assertEquals(10, service.snapshot().render().emissionHz()));
    }

    @Test
    void theOverloadWithoutMenuTakesTheDeclaredMenuDefaults() {
        final ConfigMapper.Result result = ConfigMapper.map(
                new PhysicsConfig(), new GaitConfig(), new RenderConfig(), new SimulationConfig(),
                new DiagnosticsConfig(), new AiConfig(), new VehicleConfig());

        assertAll(
                () -> assertTrue(result.isSuccess()),
                () -> assertEquals(8, result.snapshotOrThrow().menu().picker().pageSize()));
    }

    // ------------------------------------------------------------------ what it refuses

    @Test
    void aBlankRequiredLabelIsRefusedNamingItsKey() {
        ConfigFixture.write(this.directory, ConfigFile.MENU,
                "picker:",
                "  wearLabel: ''");

        final ConfigReloadResult result = service().reloadNow();
        assertAll(
                () -> assertFalse(result.applied()),
                () -> assertTrue(names(result, "picker.wearLabel"), result.summary()));
    }

    @Test
    void aKeyPresentWithNoValueFallsBackToItsDeclaredDefault() {
        // "title:" on its own line is an edit an operator can make, and what it produces is a key whose
        // value is null. The generated configuration guards every read with contains(), which answers
        // false for exactly that — so the declared default survives and the file still loads.
        //
        // Which means the null guards in the validator and in the settings record are a backstop rather
        // than a path this file can reach. Worth pinning down in both directions: the behaviour an
        // operator sees is asserted here, and the guard that catches a generated class which ever stops
        // doing this is asserted in MenuSettingsTest.
        ConfigFixture.write(this.directory, ConfigFile.MENU,
                "appearance:",
                "  title:");

        final ConfigService service = service();
        assertAll(
                () -> assertTrue(service.reloadNow().applied()),
                () -> assertFalse(service.menu().appearance().title().isBlank()));
    }

    @Test
    void anEmptyOptionalLineIsAcceptedBecauseThatIsHowALineIsTurnedOff() {
        ConfigFixture.write(this.directory, ConfigFile.MENU,
                "picker:",
                "  heading: ''",
                "  wearTooltip: ''",
                "  thumbnailItemModel: ''",
                "appearance:",
                "  swatchNote: ''",
                "animation:",
                "  entryTooltip: ''");

        final ConfigService service = service();
        assertAll(
                () -> assertTrue(service.reloadNow().applied()),
                () -> assertTrue(service.menu().picker().heading().isEmpty()),
                () -> assertFalse(service.menu().picker().hasItemModel()));
    }

    @Test
    void aWidthTheClientWouldDiscardIsRefused() {
        ConfigFixture.write(this.directory, ConfigFile.MENU,
                "layout:",
                "  bodyWidth: 4096");

        final ConfigReloadResult result = service().reloadNow();
        assertAll(
                () -> assertFalse(result.applied()),
                () -> assertTrue(names(result, "layout.bodyWidth"), result.summary()));
    }

    @Test
    void aColourRangeWiderThanTheByteItTravelsInIsRefused() {
        ConfigFixture.write(this.directory, ConfigFile.MENU,
                "appearance:",
                "  maxChannel: 1024");

        final ConfigReloadResult result = service().reloadNow();
        assertAll(
                () -> assertFalse(result.applied()),
                () -> assertTrue(names(result, "appearance.maxChannel"), result.summary()));
    }

    @Test
    void aNarrowedColourRangeIsAcceptedBecauseThatIsAServersChoice() {
        ConfigFixture.write(this.directory, ConfigFile.MENU,
                "appearance:",
                "  minChannel: 64",
                "  maxChannel: 192",
                "  defaultRed: 100",
                "  defaultGreen: 100",
                "  defaultBlue: 100");

        final ConfigService service = service();
        assertAll(
                () -> assertTrue(service.reloadNow().applied()),
                () -> assertEquals(64, service.menu().appearance().minChannel()),
                () -> assertEquals(192, service.menu().appearance().maxChannel()));
    }

    @Test
    void anInvertedColourRangeIsRefusedNamingTheFloor() {
        ConfigFixture.write(this.directory, ConfigFile.MENU,
                "appearance:",
                "  minChannel: 200",
                "  maxChannel: 100");

        final ConfigReloadResult result = service().reloadNow();
        assertAll(
                () -> assertFalse(result.applied()),
                () -> assertTrue(names(result, "appearance.minChannel"), result.summary()));
    }

    @Test
    void aStepLargerThanItsSliderIsRefused() {
        ConfigFixture.write(this.directory, ConfigFile.MENU,
                "appearance:",
                "  minScale: 1.0",
                "  maxScale: 2.0",
                "  scaleStep: 5.0");

        final ConfigReloadResult result = service().reloadNow();
        assertAll(
                () -> assertFalse(result.applied()),
                () -> assertTrue(names(result, "appearance.scaleStep"), result.summary()));
    }

    @Test
    void aDefaultOutsideItsSliderIsRefusedBecauseResetCouldNotBeUndone() {
        ConfigFixture.write(this.directory, ConfigFile.MENU,
                "appearance:",
                "  minChannel: 64",
                "  maxChannel: 192",
                "  defaultRed: 255");

        final ConfigReloadResult result = service().reloadNow();
        assertAll(
                () -> assertFalse(result.applied()),
                () -> assertTrue(names(result, "appearance.defaultRed"), result.summary()));
    }

    @Test
    void aDefaultScaleOutsideItsSliderIsRefused() {
        ConfigFixture.write(this.directory, ConfigFile.MENU,
                "appearance:",
                "  defaultScale: 16.0");

        final ConfigReloadResult result = service().reloadNow();
        assertAll(
                () -> assertFalse(result.applied()),
                () -> assertTrue(names(result, "appearance.defaultScale"), result.summary()));
    }

    @Test
    void aTimeoutOfZeroIsRefusedBecauseNothingElseEndsASession() {
        ConfigFixture.write(this.directory, ConfigFile.MENU,
                "session:",
                "  timeoutSeconds: 0");

        final ConfigReloadResult result = service().reloadNow();
        assertAll(
                () -> assertFalse(result.applied()),
                () -> assertTrue(names(result, "session.timeoutSeconds"), result.summary()));
    }

    @Test
    void aPageSizeNobodyCouldClickThroughIsRefused() {
        ConfigFixture.write(this.directory, ConfigFile.MENU,
                "picker:",
                "  pageSize: 500");

        final ConfigReloadResult result = service().reloadNow();
        assertAll(
                () -> assertFalse(result.applied()),
                () -> assertTrue(names(result, "picker.pageSize"), result.summary()));
    }

    @Test
    void aMaterialNameThatCouldNotBeOneIsRefused() {
        // A shape test, not a lookup: resolving a material initialises the server's registry, which the
        // validator deliberately cannot reach. A misspelling passes here and is reported by the surface.
        ConfigFixture.write(this.directory, ConfigFile.MENU,
                "picker:",
                "  thumbnailMaterial: 'not a material!'");

        final ConfigReloadResult result = service().reloadNow();
        assertAll(
                () -> assertFalse(result.applied()),
                () -> assertTrue(names(result, "picker.thumbnailMaterial"), result.summary()));
    }

    @Test
    void aNamespacedMaterialNameIsAccepted() {
        ConfigFixture.write(this.directory, ConfigFile.MENU,
                "picker:",
                "  thumbnailMaterial: 'minecraft:item_frame'");

        assertTrue(service().reloadNow().applied());
    }

    @Test
    void aBrokenMenuFileRefusesTheWholeReloadAndLeavesThePreviousValuesLive() {
        final ConfigService service = service();
        assertTrue(service.reloadNow().applied());
        final ConfigSnapshot before = service.snapshot();

        ConfigFixture.corrupt(this.directory, ConfigFile.MENU);
        final ConfigReloadResult refused = service.reloadNow();

        assertAll(
                () -> assertFalse(refused.applied()),
                // By identity: a half-applied reload would leave the server in a state no file
                // describes, and the menus would be the half that moved.
                () -> assertSame(before, service.snapshot()),
                () -> assertTrue(refused.diagnostics().stream()
                        .anyMatch(d -> d.file() == ConfigFile.MENU && d.key() == null)));
    }
}
