package pandastic.studio.models.internal.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.internal.menu.MenuService;
import pandastic.studio.models.internal.menu.MenuText;

import java.util.List;
import java.util.Objects;

/**
 * Opens the player-facing model wardrobe.
 *
 * <p>The menu service performs the entity-scheduler hop, so this command remains a thin dispatcher and
 * can be invoked safely from a region thread.
 */
public final class MenuCommand implements BasicCommand {

    public static final String NAME = "immodels";
    public static final List<String> ALIASES = List.of("im-models");
    public static final String DESCRIPTION = "Open the Integrated Models wardrobe";

    private final @NotNull MenuService menu;
    private final @NotNull CommandSettingsSource settings;

    public MenuCommand(
            @NotNull MenuService menu,
            @NotNull CommandSettingsSource settings
    ) {
        this.menu = Objects.requireNonNull(menu, "menu");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String @NotNull [] arguments) {
        final Audience sender = source.getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(MenuText.of(this.settings.current().menuNotPlayerMessage()));
            return;
        }
        this.menu.openPicker(player);
    }
}
