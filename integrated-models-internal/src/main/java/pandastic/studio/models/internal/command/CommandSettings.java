package pandastic.studio.models.internal.command;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Every line the reload command can say, assembled and already validated.
 *
 * <h2>Why the text is still text</h2>
 * The templates arrive here as MiniMessage <em>source</em> rather than as rendered components, for the
 * reason {@link pandastic.studio.models.internal.menu.MenuSettings} gives: most of them carry a
 * placeholder whose value only exists at the moment the command answers — the revision that was
 * published, the rig count, the diagnostic being listed — so a component parsed at reload time would
 * have to be re-parsed anyway. One parse per line sent, with the substitutions in hand, is both cheaper
 * and the only version that can carry the numbers.
 *
 * <p>It also keeps this record free of Adventure, which is what lets {@code ConfigMapper} build it
 * without knowing how text is rendered.
 *
 * <h2>Why it throws</h2>
 * Same split as the rest of the project: a rig file is written by a human and is clamped with a warning,
 * while these values arrive from a validator that has already refused the whole reload with a diagnostic
 * naming the file and the key. Anything reaching this constructor out of range is a defect in the mapper,
 * and swallowing it would hide which of the two is wrong.
 *
 * <h2>Not to be confused with its namesake</h2>
 * {@link pandastic.studio.models.internal.config.CommandSettings} is the <em>declaration</em> of
 * {@code command.yml} — the keys, the comments and the defaults. This one is the value the command layer
 * reads. The project has the same deliberate shadow for render, simulation, AI, vehicle, menu, view and
 * binding.
 *
 * @param startedMessage  acknowledgement sent before any file is read; blank to send none
 * @param appliedMessage  the result of a reload that was applied; placeholders {@code files},
 *                        {@code rigs}, {@code revision}, {@code summary}
 * @param rebuildMessage  extra line for an applied reload that changed the substep rate; blank to omit
 * @param refusedMessage  header of a refusal; placeholders {@code count}, {@code revision},
 *                        {@code summary}
 * @param diagnosticLine  one problem under that header; placeholders {@code diagnostic}, {@code file},
 *                        {@code key}, {@code detail}
 * @param busyMessage     what a second caller is told while a reload is running
 * @param failedMessage   what is said when the reload threw rather than being refused; placeholder
 *                        {@code error}
 * @param menuNotPlayerMessage reply sent when the menu command is used by a non-player sender
 */
public record CommandSettings(
        @NotNull String menuNotPlayerMessage,
        @NotNull String startedMessage,
        @NotNull String appliedMessage,
        @NotNull String rebuildMessage,
        @NotNull String refusedMessage,
        @NotNull String diagnosticLine,
        @NotNull String busyMessage,
        @NotNull String failedMessage
) {

    /**
     * Compatibility constructor for callers that only configure the reload command.
     *
     * <p>The menu reply was added when the player-facing command was wired; keeping this overload
     * avoids making test fixtures and downstream internal tooling provide an unrelated value.
     */
    public CommandSettings(
            @NotNull String startedMessage,
            @NotNull String appliedMessage,
            @NotNull String rebuildMessage,
            @NotNull String refusedMessage,
            @NotNull String diagnosticLine,
            @NotNull String busyMessage,
            @NotNull String failedMessage
    ) {
        this("<red>Only players can open the model menu.", startedMessage, appliedMessage, rebuildMessage,
                refusedMessage, diagnosticLine, busyMessage, failedMessage);
    }

    public CommandSettings {
        requireText(menuNotPlayerMessage, "menuNotPlayerMessage");
        requirePresent(startedMessage, "startedMessage");
        requireText(appliedMessage, "appliedMessage");
        requirePresent(rebuildMessage, "rebuildMessage");
        requireText(refusedMessage, "refusedMessage");
        requireText(diagnosticLine, "diagnosticLine");
        requireText(busyMessage, "busyMessage");
        requireText(failedMessage, "failedMessage");
    }

    /**
     * Whether a reload should acknowledge itself before it starts reading files.
     *
     * <p>Blank is how an operator turns the acknowledgement off, so this is the question the command
     * asks rather than a second key saying whether the first one applies.
     *
     * @return {@code true} when {@link #startedMessage()} should be sent
     */
    @Contract(pure = true)
    public boolean hasStartedMessage() {
        return !this.startedMessage.isBlank();
    }

    /**
     * Whether an applied reload that invalidated every simulation state should say so.
     *
     * @return {@code true} when {@link #rebuildMessage()} should be sent
     */
    @Contract(pure = true)
    public boolean hasRebuildMessage() {
        return !this.rebuildMessage.isBlank();
    }

    // ------------------------------------------------------------------ shared guards

    /**
     * Requires a line that has to say something.
     *
     * <p>The parameter is {@code @Nullable} against the components' own {@code @NotNull} on purpose: a
     * generated configuration reads a text key through {@code getString}, which answers {@code null} for
     * a key that is present with an empty value. {@code busyMessage:} on its own line is an edit an
     * operator can make, and it has to produce a sentence rather than a {@link NullPointerException}
     * from {@link String#isBlank()}.
     */
    private static void requireText(@Nullable String value, @NotNull String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    name + " must not be blank: it is the command's only answer to whoever typed it");
        }
    }

    /**
     * Requires a line to be present but allows it to be blank.
     *
     * <p>Blank is how an operator turns one line off, so the only thing left to refuse is {@code null} —
     * which, as above, is what a key present with an empty value reads back as.
     */
    private static void requirePresent(@Nullable String value, @NotNull String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null; use an empty string to omit it");
        }
    }
}
