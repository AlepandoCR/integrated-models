package pandastic.studio.models.internal.menu;

import io.papermc.paper.dialog.Dialog;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.dialog.api.factory.DialogFactory;

import java.util.function.Supplier;

/**
 * The real presenter: builds the dialog and shows it on the viewer's own region thread.
 *
 * <h2>Why the entity scheduler</h2>
 * Because it is the only dispatcher that follows a player. A region scheduler is keyed on a location
 * the player has already left by the time the task runs, and the global region scheduler is a different
 * thread from the one that owns them; {@code Bukkit.getScheduler()} does not exist on Folia at all. The
 * entity scheduler tracks the entity across regions and runs only while it exists, and its retired
 * callback is how a player who logged out between the click and the show stops being a problem.
 *
 * <p>Sending a dialog packet is in itself region-independent — it goes to a connection. The hop is here
 * for everything around it: the factory invokes the controller's providers, and those read what the
 * player is wearing through the wardrobe, which is entity state. One hop covers the whole build rather
 * than leaving each provider to wonder.
 *
 * <h2>Nothing is retained</h2>
 * {@code run} schedules a single task, not a repeating one, so there is no {@code ScheduledTask} to hold
 * and cancel on disable — the case the rest of this project has to be careful about. A screen in flight
 * when the plugin disables is a screen that either showed or silently did not.
 *
 * <h2>Why the build is inside the task</h2>
 * Not for thread safety alone. The factory registers a click callback per button per build, and a
 * dialog built for a player who has since disconnected would leave those registrations behind for
 * nothing. Building where the show happens means the two either both occur or neither does.
 */
public final class EntitySchedulerMenuPresenter implements MenuPresenter {

    private final @NotNull Plugin plugin;
    private final @NotNull ComponentLogger logger;

    /**
     * @param plugin the plugin the task is scheduled under
     */
    public EntitySchedulerMenuPresenter(@NotNull Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getComponentLogger();
    }

    @Override
    public void show(@NotNull Player viewer, @NotNull Supplier<@NotNull Object> controller) {
        viewer.getScheduler().run(this.plugin, task -> present(viewer, controller), null);
    }

    /**
     * Builds the controller, builds the dialog and shows it, reporting a failure rather than letting it
     * escape.
     *
     * <p>An escaped exception here would surface as a scheduler error naming a lambda, with no sign of
     * which screen produced it. The screen the player asked for is lost either way; what this buys is a
     * line saying which controller could not be built, and a player who is not left staring at nothing
     * while a stack trace accumulates somewhere else.
     */
    private void present(@NotNull Player viewer, @NotNull Supplier<@NotNull Object> controller) {
        Object screen = null;
        try {
            screen = controller.get();
            final Dialog dialog = DialogFactory.create(screen);
            viewer.showDialog(dialog);
        } catch (final RuntimeException failure) {
            final String name = screen == null ? "a menu" : screen.getClass().getSimpleName();
            this.logger.error(
                    Component.text("Could not show " + name + " to " + viewer.getName(),
                            NamedTextColor.RED),
                    failure);
        }
    }
}
