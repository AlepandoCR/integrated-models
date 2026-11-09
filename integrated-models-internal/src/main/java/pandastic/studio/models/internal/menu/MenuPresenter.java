package pandastic.studio.models.internal.menu;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

/**
 * Puts a screen in front of a player.
 *
 * <h2>Why this is a seam and not a method call</h2>
 * Because building a dialog is the one step in this layer that cannot happen without a server: the
 * factory reads the controller's annotations, invokes its providers, registers its click callbacks
 * against the server's registry and hands back a dialog. Behind this interface a test asserts
 * <em>which screen a click leads to</em> — the whole navigation graph, every re-show after an action,
 * every refusal that sends the player back where they were — and inspects the controller it would have
 * built, without building one.
 *
 * <h2>Why a supplier and not a controller</h2>
 * This is the load-bearing part. A screen has to read what the player is wearing in order to decide
 * what it shows, and on Folia that read is only legal on the thread that owns them. If this method took
 * a finished controller, the construction — and therefore the read — would have happened on whichever
 * thread asked for the screen. Taking a supplier moves construction <strong>inside</strong> the hop, so
 * a controller's constructor is the right and only place for it.
 *
 * <p>The consequence a controller may rely on: its constructor runs on the viewer's own region thread,
 * exactly once per screen, and nothing else can move that player's state while it runs.
 */
public interface MenuPresenter {

    /**
     * Shows the screen {@code controller} describes to {@code viewer}.
     *
     * <p>Callable from any thread. {@code controller} is invoked on the viewer's own region thread, and
     * may therefore read the viewer — which is what makes it, and not the caller, the place to decide
     * what the screen contains.
     *
     * @param viewer     who to show it to
     * @param controller builds the annotated dialog controller, on the viewer's thread
     */
    void show(@NotNull Player viewer, @NotNull Supplier<@NotNull Object> controller);
}
