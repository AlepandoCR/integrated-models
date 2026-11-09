package pandastic.studio.models.internal.command;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.ComponentLike;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.internal.config.ConfigFile;
import pandastic.studio.models.internal.config.ConfigReloadResult;
import pandastic.studio.models.internal.config.ConfigService;
import pandastic.studio.models.internal.config.ConfigSnapshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * The settings, reload results and senders the tests in this package drive the command with.
 *
 * <h2>Why the reload results are real</h2>
 * Because {@code ConfigReloadResult} cannot be faked from here — its two factories are package-private
 * to the configuration layer, deliberately, since "applied" and "refused" are that layer's conclusions
 * to draw and not a value a caller may assert into existence. So every result below is produced by
 * really loading really-written files, which makes the diagnostics real too: they carry the file name
 * and the key an operator would have to go and edit, which is exactly the content the refusal test is
 * about.
 *
 * <h2>Why the settings come from a file</h2>
 * Same argument {@code MenuFixtures} makes. "The configured line is the one that was sent" is a claim
 * about what an operator typed, and the only path from a typed value to a live setting runs through the
 * parser and the validator. A hand-built record would assert that the command reads a field, which
 * nothing doubts.
 */
final class CommandFixtures {

    /** Deliberately unlike anything a default says, so a leftover literal cannot pass for it. */
    static final String STARTED = "QQ-started-QQ";
    static final String APPLIED = "QQ-applied-QQ files=<files> rigs=<rigs> rev=<revision>";
    static final String REBUILD = "QQ-rebuild-QQ";
    static final String REFUSED = "QQ-refused-QQ count=<count> rev=<revision>";
    static final String DIAGNOSTIC = "QQ-problem-QQ <file>|<key>|<detail>";
    static final String BUSY = "QQ-busy-QQ";
    static final String FAILED = "QQ-failed-QQ <error>";

    private CommandFixtures() {
    }

    /** The declared defaults, which is what a server starts with. */
    static @NotNull CommandSettings defaults() {
        return ConfigSnapshot.defaults().command();
    }

    /**
     * Settings whose every line is unmistakable, loaded through a real {@code command.yml}.
     *
     * @param directory a temporary directory to write the file in
     * @return the live settings
     */
    static @NotNull CommandSettings odd(@NotNull Path directory) {
        return fromFile(directory,
                "reload:",
                "  startedMessage: '" + STARTED + "'",
                "  appliedMessage: '" + APPLIED + "'",
                "  rebuildMessage: '" + REBUILD + "'",
                "  refusedMessage: '" + REFUSED + "'",
                "  diagnosticLine: '" + DIAGNOSTIC + "'",
                "  busyMessage: '" + BUSY + "'",
                "  failedMessage: '" + FAILED + "'");
    }

    /**
     * The settings a {@code command.yml} containing {@code lines} produces.
     *
     * <p>Partial on purpose: a generated configuration keeps its declared default for every key the file
     * does not mention, which is the behaviour the reload path depends on.
     *
     * @param directory a temporary directory to write the file in
     * @param lines     the YAML lines, each a complete {@code key: value} entry
     * @return the live settings
     */
    static @NotNull CommandSettings fromFile(@NotNull Path directory, String @NotNull ... lines) {
        write(directory, ConfigFile.COMMAND, lines);
        final ConfigService service = ConfigService.inDirectory(directory.toFile());
        final ConfigReloadResult result = service.reloadNow();
        if (!result.applied()) {
            throw new AssertionError("The command.yml fixture was refused: " + result.summary());
        }
        return service.command();
    }

    /** A real applied reload: a fresh directory, loaded once, so every file is at its default. */
    static @NotNull ConfigReloadResult applied(@NotNull Path directory) {
        final ConfigReloadResult result = ConfigService.inDirectory(directory.toFile()).reloadNow();
        if (!result.applied()) {
            throw new AssertionError("A load of defaults was refused: " + result.summary());
        }
        return result;
    }

    /**
     * A real applied reload that invalidated every live simulation state.
     *
     * <p>The substep rate is the one value that does it, because a physics state is sized and
     * stability-checked against a step size when it is built. Raising it is always stable — the bound is
     * an upper one on the step — so this edit is accepted and nothing else about it matters.
     */
    static @NotNull ConfigReloadResult rebuilding(@NotNull Path directory) {
        final ConfigService service = ConfigService.inDirectory(directory.toFile());
        if (!service.reloadNow().applied()) {
            throw new AssertionError("A load of defaults was refused");
        }
        write(directory, ConfigFile.PHYSICS, "substepRateHz: 120");
        final ConfigReloadResult result = service.reloadNow();
        if (!result.applied() || !result.requiresSimulationRebuild()) {
            throw new AssertionError("Expected an applied reload needing a rebuild: " + result.summary());
        }
        return result;
    }

    /**
     * A real refused reload, with a problem in each of two files.
     *
     * <p>Two files rather than two keys in one, so that a test asserting "every diagnostic" is asserting
     * something a single line could not have satisfied by accident.
     */
    static @NotNull ConfigReloadResult refused(@NotNull Path directory) {
        final ConfigService service = ConfigService.inDirectory(directory.toFile());
        if (!service.reloadNow().applied()) {
            throw new AssertionError("A load of defaults was refused");
        }
        // Above the client tick rate, which the renderer refuses because the extra packets cannot be
        // seen; and below one tick, which the binding layer refuses because the platform would silently
        // round it up.
        write(directory, ConfigFile.RENDER, "emissionHz: 99");
        write(directory, ConfigFile.BINDING, "samplePeriodTicks: 0");
        final ConfigReloadResult result = service.reloadNow();
        if (result.applied()) {
            throw new AssertionError("Expected a refusal");
        }
        return result;
    }

    /**
     * A real refused reload whose complaint is about a file rather than a key.
     *
     * <p>A tab after a mapping key is a hard YAML syntax error rather than a type mismatch, so the
     * loader cannot name a key — which is the case the {@code key} placeholder has to have an answer
     * for.
     */
    static @NotNull ConfigReloadResult corrupted(@NotNull Path directory) {
        final ConfigService service = ConfigService.inDirectory(directory.toFile());
        if (!service.reloadNow().applied()) {
            throw new AssertionError("A load of defaults was refused");
        }
        write(directory, ConfigFile.SIMULATION, "loopRateHz: 60", "\tnot: yaml", "  also: [unclosed");
        final ConfigReloadResult result = service.reloadNow();
        if (result.applied() || result.diagnostics().stream().noneMatch(d -> d.key() == null)) {
            throw new AssertionError("Expected a whole-file refusal: " + result.summary());
        }
        return result;
    }

    /** An operation that answers {@code report} and counts nothing. */
    static @NotNull ReloadOperation returning(@NotNull ReloadReport report) {
        return () -> report;
    }

    /** An operation that fails the way a reload fails when it is not merely refused. */
    static @NotNull ReloadOperation throwing(@NotNull RuntimeException failure) {
        return () -> {
            throw failure;
        };
    }

    /**
     * A sender that knows which permissions it holds and forwards what it is sent.
     *
     * <h2>Why a proxy</h2>
     * Because {@link CommandSender} cannot be constructed off a server, while the two things this layer
     * asks it are whether it holds a permission and that it be sent a component. A dynamic proxy answers
     * those and throws for everything else, which is both the smallest possible double and a working
     * assertion: a command that started asking a sender for its world would fail here rather than
     * quietly passing and then needing a region thread.
     *
     * @param replies  where every line sent to this sender is recorded
     * @param granted  the permission nodes it holds
     * @return the double
     */
    static @NotNull CommandSender sender(
            @NotNull RecordingAudience replies,
            String @NotNull ... granted
    ) {
        final Set<String> permissions = Set.of(granted);
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[]{CommandSender.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "hasPermission" -> permissions.contains(String.valueOf(arguments[0]));
                    case "sendMessage" -> {
                        if (arguments.length == 1 && arguments[0] instanceof ComponentLike line) {
                            replies.sendMessage(line.asComponent());
                        }
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    case "toString" -> "CommandSender" + permissions;
                    default -> throw new UnsupportedOperationException(
                            "The command asked a test sender for " + method.getName()
                                    + ", which nothing in this layer is allowed to need");
                });
    }

    /**
     * A command source over {@code sender}.
     *
     * <p>Only {@link CommandSourceStack#getSender()} is answered, which is the assertion: the command
     * replies to whoever typed it rather than to the executor, so it has no business reading the
     * location or the executing entity.
     *
     * @param sender the sender the stack reports
     * @return the double
     */
    static @NotNull CommandSourceStack source(@NotNull CommandSender sender) {
        return (CommandSourceStack) Proxy.newProxyInstance(
                CommandSourceStack.class.getClassLoader(),
                new Class<?>[]{CommandSourceStack.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getSender" -> sender;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    case "toString" -> "CommandSourceStack(" + sender + ")";
                    default -> throw new UnsupportedOperationException(
                            "The command asked a test source for " + method.getName()
                                    + ", which nothing in this layer is allowed to need");
                });
    }

    /**
     * Writes {@code lines} as the entire contents of one configuration file.
     *
     * <p>Hand-written YAML for the reason the configuration layer's own fixtures are: a generated
     * configuration has accessors and no setters, so there is no programmatic path from "I want this key
     * to say that" to a file saying so.
     */
    private static void write(
            @NotNull Path directory,
            @NotNull ConfigFile file,
            String @NotNull ... lines
    ) {
        final StringBuilder content = new StringBuilder();
        for (final String line : lines) {
            content.append(line).append(System.lineSeparator());
        }
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(file.fileName()), content.toString());
        } catch (final IOException failure) {
            throw new UncheckedIOException("Could not write the " + file.fileName() + " fixture",
                    failure);
        }
    }
}
