package pandastic.studio.models.internal.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The assembled command settings: which lines may be empty, and which may not.
 *
 * <h2>Why the guards are worth a suite</h2>
 * They are a backstop rather than a path {@code command.yml} can reach — the validator in the
 * configuration layer refuses the same values first, with a diagnostic naming the file and the key, and
 * a key present with no value falls back to its declared default before either of them sees it. What is
 * being pinned down here is that the backstop agrees with the validator about what is legal. If the two
 * ever disagree, one of them is wrong and the snapshot would be published with settings the command
 * cannot run on.
 *
 * <p>The {@code null} cases are the same kind of claim. Nothing in the file layer produces one today,
 * because a generated configuration guards every read with {@code contains()}; the guard is what catches
 * a generated class that ever stops doing that, and it has to produce a sentence rather than a
 * {@link NullPointerException} from {@code isBlank()}.
 */
class CommandSettingsTest {

    private static CommandSettings settings() {
        return new CommandSettings(
                "started", "applied", "rebuild", "refused", "problem", "busy", "failed");
    }

    @Test
    void theDeclaredDefaultsAreAWorkingSetOfLines() {
        final CommandSettings defaults = CommandFixtures.defaults();

        assertAll(
                () -> assertTrue(defaults.hasStartedMessage()),
                () -> assertTrue(defaults.hasRebuildMessage()),
                () -> assertFalse(defaults.appliedMessage().isBlank()),
                () -> assertFalse(defaults.refusedMessage().isBlank()),
                () -> assertFalse(defaults.diagnosticLine().isBlank()),
                () -> assertFalse(defaults.busyMessage().isBlank()),
                () -> assertFalse(defaults.failedMessage().isBlank()),
                // Every placeholder the command substitutes has to be somewhere in the defaults, or the
                // numbers it went to the trouble of collecting are reported to nobody.
                () -> assertTrue(defaults.appliedMessage().contains("<files>")),
                () -> assertTrue(defaults.appliedMessage().contains("<rigs>")),
                () -> assertTrue(defaults.appliedMessage().contains("<revision>")),
                () -> assertTrue(defaults.refusedMessage().contains("<count>")),
                () -> assertTrue(defaults.refusedMessage().contains("<revision>")),
                () -> assertTrue(defaults.diagnosticLine().contains("<diagnostic>")),
                () -> assertTrue(defaults.failedMessage().contains("<error>")));
    }

    @Test
    void theTwoOptionalLinesMayBeEmptiedToTurnThemOff() {
        final CommandSettings quiet = new CommandSettings(
                "", "applied", "", "refused", "problem", "busy", "failed");

        assertAll(
                () -> assertFalse(quiet.hasStartedMessage()),
                () -> assertFalse(quiet.hasRebuildMessage()));
    }

    @Test
    void aLineThatIsTheCommandsOnlyAnswerMayNotBeBlank() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new CommandSettings(
                        "started", " ", "rebuild", "refused", "problem", "busy", "failed")),
                () -> assertThrows(IllegalArgumentException.class, () -> new CommandSettings(
                        "started", "applied", "rebuild", "", "problem", "busy", "failed")),
                () -> assertThrows(IllegalArgumentException.class, () -> new CommandSettings(
                        "started", "applied", "rebuild", "refused", "", "busy", "failed")),
                () -> assertThrows(IllegalArgumentException.class, () -> new CommandSettings(
                        "started", "applied", "rebuild", "refused", "problem", "", "failed")),
                () -> assertThrows(IllegalArgumentException.class, () -> new CommandSettings(
                        "started", "applied", "rebuild", "refused", "problem", "busy", "")));
    }

    @Test
    void aMissingLineIsRefusedRatherThanDereferenced() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new CommandSettings(
                        null, "applied", "rebuild", "refused", "problem", "busy", "failed")),
                () -> assertThrows(IllegalArgumentException.class, () -> new CommandSettings(
                        "started", null, "rebuild", "refused", "problem", "busy", "failed")),
                () -> assertThrows(IllegalArgumentException.class, () -> new CommandSettings(
                        "started", "applied", null, "refused", "problem", "busy", "failed")));
    }

    @Test
    void theComplaintNamesTheComponentSoTheMapperCanBeFound() {
        final IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
                () -> new CommandSettings(
                        "started", "applied", "rebuild", "refused", "problem", "", "failed"));
        final IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> new CommandSettings(
                        null, "applied", "rebuild", "refused", "problem", "busy", "failed"));

        assertAll(
                () -> assertTrue(blank.getMessage().startsWith("busyMessage"), blank.getMessage()),
                () -> assertTrue(missing.getMessage().startsWith("startedMessage"),
                        missing.getMessage()));
    }

    @Test
    void aFullyPopulatedValueIsAccepted() {
        assertAll(
                () -> assertTrue(settings().hasStartedMessage()),
                () -> assertTrue(settings().hasRebuildMessage()));
    }
}
