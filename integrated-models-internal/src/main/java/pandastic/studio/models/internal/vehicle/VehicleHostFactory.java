package pandastic.studio.models.internal.vehicle;

import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.internal.nms.MovementBridge;
import pandastic.studio.models.internal.nms.RiderInputBridge;

/**
 * Turns an entity into a {@link VehicleHost}.
 *
 * <p>One method, and it exists to keep {@link VehicleBinderImpl} free of the three things only a host
 * needs: the plugin instance that owns its scheduled tasks, the Mojang-mapped movement seam, and the
 * input seam. The binder's own job — validate, hand out a handle, remember it, tear everything down at
 * shutdown — involves none of them, and a binder that held a {@code Plugin} could not be constructed in
 * a test at all.
 *
 * <p>That is not a testing convenience; it is the same division of labour
 * {@link pandastic.studio.models.internal.binding.AnchorFactory} follows. Platform objects live behind
 * the host seam, and the layers above it deal in hosts.
 */
@FunctionalInterface
public interface VehicleHostFactory {

    /** A host driving {@code root}. */
    @NotNull VehicleHost hostFor(@NotNull Entity root);

    /**
     * The production factory: entity hosts scheduled against {@code plugin}.
     *
     * @param plugin   whose scheduler owns every control loop
     * @param movement the movement seam. A server with no adapter passes one whose methods decline, which
     *                 routes every move through the platform's asynchronous teleport rather than failing
     * @param input    where a rider's held keys are read from
     */
    static @NotNull VehicleHostFactory entities(
            @NotNull Plugin plugin,
            @NotNull MovementBridge movement,
            @NotNull RiderInputBridge input
    ) {
        return root -> new EntityVehicleHost(plugin, root, movement, input);
    }
}
