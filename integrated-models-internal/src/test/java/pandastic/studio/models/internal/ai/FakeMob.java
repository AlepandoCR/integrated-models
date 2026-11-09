package pandastic.studio.models.internal.ai;

import org.bukkit.entity.Mob;

import java.lang.reflect.Proxy;
import java.util.UUID;

/**
 * A {@code Mob} reference that exists without a server and refuses to behave like one.
 *
 * <h2>Why a proxy rather than a mock or a stub</h2>
 * Because the point is that nothing in this layer calls anything on it. A creature is reached through
 * {@link AiHost}; the api takes a {@code Mob} because that is what a caller has, and the layer passes it
 * straight through to the NMS seam. So what a test needs is a reference that is non-null and
 * nothing else.
 *
 * <p>A proxy whose handler throws gives exactly that, and it gives it as an assertion: if some future
 * change starts reading the creature above the seam, the test that exercises it fails with a message
 * naming the method rather than quietly passing against a stub that answered zero. A mocking framework
 * would have made the same point less directly and added a dependency this project does not have.
 *
 * <p>{@code equals}, {@code hashCode} and {@code toString} are answered, because a reference that cannot
 * be put in a collection or printed in a failure message is not usable as a reference.
 */
final class FakeMob {

    private FakeMob() {
    }

    /**
     * A {@code Mob} that throws on every call except identity and printing.
     *
     * @return the proxy
     */
    static Mob create() {
        final UUID identity = UUID.randomUUID();
        return (Mob) Proxy.newProxyInstance(
                FakeMob.class.getClassLoader(),
                new Class<?>[]{Mob.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> identity.hashCode();
                    case "toString" -> "FakeMob " + identity;
                    default -> throw new AssertionError("Nothing above the NMS seam may call "
                            + method.getName() + " on a creature: the AI layer reaches one through"
                            + " AiHost, which is the seam a test substitutes. If this call is"
                            + " legitimate, it belongs on AiHost so that the thread rules around it are"
                            + " expressible.");
                });
    }
}
