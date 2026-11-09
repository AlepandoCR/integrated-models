package pandastic.studio.models.internal.vehicle;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Proxy;

/**
 * A {@link Player} reference that exists and does nothing.
 *
 * <h2>Why a proxy rather than a server</h2>
 * Because the vehicle layer never asks a player anything. It mounts a reference, compares it against the
 * one in the seat on the next step, hands it to the input seam, and puts the name in a failure message.
 * All four of those need a value with an identity; none of them needs a world, a connection or a tick.
 * Standing a server up to supply one would make the thing under test depend on the thing the whole
 * {@link VehicleHost} seam exists to keep out.
 *
 * <p>The three methods implemented are the three the layer actually calls. Everything else returns the
 * zero value for its type, which is the right behaviour for a stub: a test that reaches a fourth method
 * is a test whose subject has grown a dependency somebody should notice.
 */
final class StubPlayer {

    private StubPlayer() {
    }

    /**
     * A player called {@code name}, distinct from every other stub.
     *
     * <p>Identity is reference identity, as it is for a real player: the layer compares the rider it
     * mounted against the one the host reports, and a value-equal substitute would make that comparison
     * pass for two different people.
     */
    static @NotNull Player named(@NotNull String name) {
        return (Player) Proxy.newProxyInstance(
                StubPlayer.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getName" -> name;
                    // Proxy routes these to the handler, so reference semantics have to be restated.
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "StubPlayer[" + name + "]";
                    default -> null;
                });
    }
}
