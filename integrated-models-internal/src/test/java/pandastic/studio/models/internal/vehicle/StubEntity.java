package pandastic.studio.models.internal.vehicle;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Pig;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Proxy;

/**
 * An entity reference whose only interesting property is its type.
 *
 * <h2>Why a proxy rather than a server</h2>
 * Because the binder never asks the root entity anything. It checks what kind of thing it is — which is
 * an {@code instanceof} against the Bukkit hierarchy and needs no state at all — hands it to the host
 * factory, and names its type in a failure message. A server would supply three facts the layer does not
 * use and one it does.
 *
 * <p>The type matters because the refusal that the whole authority model rests on is a statement about
 * the entity's <em>class</em>: the server hands control to a rider's client when the root's own class
 * says the first passenger is its driver, which is exactly what {@link Pig} and its relatives say. So
 * the test needs a reference that <em>is</em> one of those, and a proxy over the interface is the only
 * way to have one without a world to spawn it in.
 */
final class StubEntity {

    private StubEntity() {
    }

    /** An entity of no particular kind, which is what a legitimate vehicle root looks like. */
    static @NotNull Entity plain() {
        return of(Entity.class);
    }

    /**
     * An entity of {@code type}, for the refusal cases.
     *
     * @param type the Bukkit interface to impersonate
     */
    static @NotNull Entity of(@NotNull Class<? extends Entity> type) {
        return (Entity) Proxy.newProxyInstance(
                StubEntity.class.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, arguments) -> switch (method.getName()) {
                    // Proxy routes these to the handler, so reference semantics have to be restated.
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "StubEntity[" + type.getSimpleName() + "]";
                    default -> null;
                });
    }
}
