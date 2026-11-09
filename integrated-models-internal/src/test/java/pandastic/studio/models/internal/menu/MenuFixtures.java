package pandastic.studio.models.internal.menu;

import org.bukkit.entity.Player;
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
import java.util.UUID;

/**
 * The players and settings the tests in this package build their screens from.
 *
 * <h2>Why the player is a proxy</h2>
 * Because the menus take a {@link Player} and a {@link Player} cannot be constructed without a server,
 * while the <em>two</em> things the menus ask it are its identity and its name. A dynamic proxy answers
 * those and throws for everything else, which is both the smallest possible double and a working
 * assertion: a screen that tried to read a player's position would fail here rather than quietly
 * passing a test and failing on Folia, where that read is undefined and silent.
 *
 * <p>Identity matters too. {@code MenuScreen.isViewer} compares the clicking audience by reference, so
 * the same proxy instance has to come back from a fake click — which it does, because a test holds the
 * instance it built the screen with.
 *
 * <h2>Why the settings come from a file</h2>
 * "The configuration is honoured" is a claim about what an operator typed, and the only path from a
 * typed value to a live setting runs through the parser and the validator. A hand-built record would
 * assert that a screen reads a field, which nothing doubts; going through a file asserts that a line
 * reaches a slider, which is the part with a key name, a parser and a bound in it.
 */
final class MenuFixtures {

    private MenuFixtures() {
    }

    /**
     * A player that knows who it is and nothing else.
     *
     * @param name the player's name, used only in messages
     * @return the double
     */
    static @NotNull Player player(@NotNull String name) {
        return player(name, UUID.randomUUID());
    }

    private static @NotNull Player player(@NotNull String name, @NotNull UUID id) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "getName" -> name;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    case "toString" -> "Player(" + name + ")";
                    default -> throw new UnsupportedOperationException(
                            "A menu asked a test player for " + method.getName()
                                    + ", which nothing in this layer is allowed to need");
                });
    }

    /**
     * The declared menu defaults, which is what a server starts with.
     *
     * <p>Taken from the declared configuration rather than hand-written, so a test that asserts a label
     * asserts the label an operator will actually see — and so a default that stops being valid fails
     * here as well as in the mapper's own tests.
     *
     * @return the defaults
     */
    static @NotNull MenuSettings defaults() {
        return ConfigSnapshot.defaults().menu();
    }

    /**
     * The settings a {@code menu.yml} containing {@code lines} produces.
     *
     * <p>Partial on purpose: a generated configuration keeps its declared default for every key the file
     * does not mention, which is the behaviour the reload path depends on.
     *
     * @param directory a temporary directory to write the file in
     * @param lines     the YAML lines, each a complete {@code key: value} entry
     * @return the live menu settings
     */
    static @NotNull MenuSettings fromFile(@NotNull Path directory, @NotNull String... lines) {
        final ConfigService service = serviceFor(directory, lines);
        final ConfigReloadResult result = service.reloadNow();
        if (!result.applied()) {
            throw new AssertionError("The menu.yml fixture was refused: " + result.summary());
        }
        return service.menu();
    }

    private static @NotNull ConfigService serviceFor(
            @NotNull Path directory,
            @NotNull String... lines
    ) {
        final StringBuilder content = new StringBuilder();
        for (final String line : lines) {
            content.append(line).append(System.lineSeparator());
        }
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(ConfigFile.MENU.fileName()), content.toString());
        } catch (final IOException failure) {
            throw new UncheckedIOException("Could not write the menu.yml fixture", failure);
        }
        return ConfigService.inDirectory(directory.toFile());
    }
}
