package pandastic.studio.models.internal.decoration;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.internal.render.RenderSettingsSource;

/**
 * Turns a world into a {@link DecorationAnchor}.
 *
 * <p>One method, and it exists to keep {@link DecorationBinderImpl} free of the two things only an
 * anchor needs: the plugin instance its deferred work is scheduled against, and the render settings its
 * sink reads. The binder's own job — validate, hand out a handle, remember it — involves neither, and a
 * binder that held a {@code Plugin} could not be constructed in a test at all.
 *
 * <p>The same division of labour as the entity path's {@code AnchorFactory}: platform objects live
 * behind the anchor seam, and the layers above it deal in anchors.
 */
@FunctionalInterface
public interface DecorationAnchorFactory {

    /**
     * An anchor placing decorations in {@code world}.
     *
     * <p>A world's anchor is stateless and shareable, so an implementation is free to return the same
     * instance for repeated calls — the seam carries no per-decoration state for it to confuse.
     *
     * @param world the world to place in
     * @return the anchor
     */
    @NotNull DecorationAnchor anchorFor(@NotNull World world);

    /**
     * The production factory: world anchors scheduling against {@code plugin}.
     *
     * @param plugin         the plugin deferred teardown work is scheduled against
     * @param renderSettings where a bound sink reads its dirty epsilon from, once per emission
     * @return the factory
     */
    static @NotNull DecorationAnchorFactory worlds(
            @NotNull Plugin plugin,
            @NotNull RenderSettingsSource renderSettings
    ) {
        return world -> new WorldDecorationAnchor(plugin, world, renderSettings);
    }
}
