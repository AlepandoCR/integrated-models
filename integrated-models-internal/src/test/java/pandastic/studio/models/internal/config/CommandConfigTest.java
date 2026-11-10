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
 * {@code command.yml}: that it exists, that every line in it reaches the command, and what it refuses.
 *
 * <p>The refusals are the half worth having, and the asymmetry between them is the subject. Four of the
 * seven lines are the reload command's <em>only</em> answer to whoever typed it, so emptying one
 * produces a command that appears to do nothing at all — those are refused with the key named. The other
 * three are lines an operator may legitimately not want, and emptying a key is how they turn one off.
 * Same split {@code menu.yml} has, for the same reason.
 */
class CommandConfigTest {

    @TempDir
    Path directory;

    private ConfigService service() {
        return ConfigService.inDirectory(this.directory.toFile());
    }

    private static boolean names(ConfigReloadResult result, String key) {
        return result.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.file() == ConfigFile.COMMAND
                        && key.equals(diagnostic.key()));
    }

    // ------------------------------------------------------------------ the file

    @Test
    void theFileIsCreatedWithItsCommentsAndItsSection() {
        assertTrue(service().reloadNow().applied());

        final String written = ConfigFixture.read(this.directory, ConfigFile.COMMAND);
        assertAll(
                () -> assertTrue(Files.exists(this.directory.resolve("command.yml"))),
                () -> assertTrue(written.contains("reload:"), written),
                () -> assertTrue(written.contains("  appliedMessage:"), written),
                () -> assertTrue(written.contains("  diagnosticLine:"), written),
                // The comment is the entire operator-facing half of a declaration: a template with
                // placeholders in it and no explanation is a key nobody dares to change.
                () -> assertTrue(written.contains("Placeholders: files, rigs, revision, summary"),
                        written));
    }

    @Test
    void theDeclaredDefaultsMapAndReachTheSnapshot() {
        final ConfigService service = service();
        assertTrue(service.reloadNow().applied());

        final var command = service.command();
        assertAll(
                () -> assertTrue(command.hasStartedMessage()),
                () -> assertTrue(command.hasRebuildMessage()),
                () -> assertTrue(command.appliedMessage().contains("<rigs>"),
                        command.appliedMessage()),
                () -> assertTrue(command.diagnosticLine().contains("<diagnostic>"),
                        command.diagnosticLine()),
                () -> assertFalse(command.busyMessage().isBlank()),
                // The accessor and the snapshot are the same object, which is what makes the source
                // allocation-free and its identity stable while the configuration is.
                () -> assertSame(service.snapshot().command(), service.command()));
    }

    @Test
    void anEditedKeyIsReadBackAndTheOtherFilesAreUndisturbed() {
        ConfigFixture.write(this.directory, ConfigFile.COMMAND,
                "reload:",
                "  busyMessage: 'wait your turn'",
                "  appliedMessage: 'done, <rigs> rig(s)'");

        final ConfigService service = service();
        assertTrue(service.reloadNow().applied());

        assertAll(
                () -> assertEquals("wait your turn", service.command().busyMessage()),
                () -> assertEquals("done, <rigs> rig(s)", service.command().appliedMessage()),
                // Untouched keys keep their declared defaults, which is what makes a partial file legal.
                () -> assertTrue(service.command().hasStartedMessage()),
                () -> assertEquals(10, service.snapshot().render().emissionHz()));
    }

    @Test
    void theOverloadWithoutCommandTakesTheDeclaredCommandDefaults() {
        final ConfigMapper.Result result = ConfigMapper.map(
                new PhysicsConfig(), new GaitConfig(), new RenderConfig(), new SimulationConfig(),
                new DiagnosticsConfig(), new AiConfig(), new VehicleConfig(), new MenuConfig(),
                new ViewConfig(), new BindingConfig());

        assertAll(
                () -> assertTrue(result.isSuccess()),
                () -> assertTrue(result.snapshotOrThrow().command().hasStartedMessage()));
    }

    // ------------------------------------------------------------------ what it refuses

    @Test
    void aBlankRequiredLineIsRefusedNamingItsKey() {
        ConfigFixture.write(this.directory, ConfigFile.COMMAND,
                "reload:",
                "  appliedMessage: ''");

        final ConfigReloadResult result = service().reloadNow();
        assertAll(
                () -> assertFalse(result.applied()),
                () -> assertTrue(names(result, "reload.appliedMessage"), result.summary()));
    }

    @Test
    void everyRequiredLineIsRefusedWhenBlankAndAllOfThemAreReportedAtOnce() {
        // One pass over the file produces the complete list, because an operator who emptied four keys
        // would rather hear about four than find out one reload at a time.
        ConfigFixture.write(this.directory, ConfigFile.COMMAND,
                "reload:",
                "  appliedMessage: ''",
                "  refusedMessage: ''",
                "  diagnosticLine: ''",
                "  busyMessage: ''",
                "  failedMessage: ''");

        final ConfigReloadResult result = service().reloadNow();
        assertAll(
                () -> assertFalse(result.applied()),
                () -> assertTrue(names(result, "reload.appliedMessage"), result.summary()),
                () -> assertTrue(names(result, "reload.refusedMessage"), result.summary()),
                () -> assertTrue(names(result, "reload.diagnosticLine"), result.summary()),
                () -> assertTrue(names(result, "reload.busyMessage"), result.summary()),
                () -> assertTrue(names(result, "reload.failedMessage"), result.summary()));
    }

    @Test
    void anEmptyOptionalLineIsAcceptedBecauseThatIsHowALineIsTurnedOff() {
        ConfigFixture.write(this.directory, ConfigFile.COMMAND,
                "reload:",
                "  startedMessage: ''",
                "  rebuildMessage: ''");

        final ConfigService service = service();
        assertAll(
                () -> assertTrue(service.reloadNow().applied()),
                () -> assertFalse(service.command().hasStartedMessage()),
                () -> assertFalse(service.command().hasRebuildMessage()));
    }

    @Test
    void aKeyPresentWithNoValueFallsBackToItsDeclaredDefault() {
        // "busyMessage:" on its own line is an edit an operator can make, and what it produces is a key
        // whose value is null. The generated configuration guards every read with contains(), which
        // answers false for exactly that — so the declared default survives and the file still loads.
        // The null guards in the validator and in the settings record are therefore a backstop rather
        // than a path this file can reach; CommandSettingsTest asserts them from the other side.
        ConfigFixture.write(this.directory, ConfigFile.COMMAND,
                "reload:",
                "  busyMessage:");

        final ConfigService service = service();
        assertAll(
                () -> assertTrue(service.reloadNow().applied()),
                () -> assertFalse(service.command().busyMessage().isBlank()));
    }

    @Test
    void aBrokenCommandFileRefusesTheWholeReloadAndLeavesThePreviousValuesLive() {
        final ConfigService service = service();
        assertTrue(service.reloadNow().applied());
        final ConfigSnapshot before = service.snapshot();

        ConfigFixture.corrupt(this.directory, ConfigFile.COMMAND);
        final ConfigReloadResult refused = service.reloadNow();

        assertAll(
                () -> assertFalse(refused.applied()),
                // By identity: a half-applied reload would leave the server in a state no file
                // describes, and a command that could not say what it had done would be the half that
                // moved.
                () -> assertSame(before, service.snapshot()),
                () -> assertTrue(refused.diagnostics().stream()
                        .anyMatch(d -> d.file() == ConfigFile.COMMAND && d.key() == null)));
    }

    @Test
    void theFileIsOneOfTheFilesTheServiceKnowsAbout() {
        // The enum is what a diagnostic names and what the first load creates, so a file declared and
        // never read would be a file an operator edits with no effect.
        assertAll(
                () -> assertEquals("command.yml", ConfigFile.COMMAND.fileName()),
                () -> assertTrue(service().reloadNow().applied()),
                () -> assertTrue(Files.isRegularFile(
                        this.directory.resolve(ConfigFile.COMMAND.fileName()))));
    }
}
