package pandastic.studio.models.internal.vehicle;

import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Strider;
import org.jetbrains.annotations.NotNull;

/**
 * The entity types whose own class hands a rider's client control of them, as far as plain Bukkit can
 * tell.
 *
 * <h2>Why this exists when there is a seam that answers the question properly</h2>
 * Because the seam can be absent. {@link pandastic.studio.models.internal.nms.MovementBridge} answers
 * authoritatively — it reads the server's own controlling-passenger decision — but there is no adapter on
 * a Minecraft version nobody has shipped one for, and the vehicle layer still has to decide whether to
 * refuse a mount. The alternatives to this list are both worse: refusing every vehicle when the bridge
 * is missing turns one absent adapter into a dead feature, and allowing every vehicle produces the single
 * failure this whole design exists to avoid — a vehicle the client simulates, which the server corrects,
 * forever, visibly.
 *
 * <h2>Why classes and not entity types</h2>
 * A list of {@code EntityType} constants goes stale the first time a new wood gets a boat: the new
 * constant is a boat in every behavioural sense and is absent from the list. The Bukkit interfaces, by
 * contrast, are the same hierarchy the server's own classes form, so a new boat variant is a
 * {@link Boat} and a new horse-like is an {@link AbstractHorse} without anybody editing this file.
 *
 * <h2>It is allowed to be wrong in one direction only</h2>
 * Over-inclusion costs a refusal for an entity that would have worked, which is a sentence in a log and
 * a different root entity. Under-inclusion costs rubber-banding that looks like a network fault. So when
 * in doubt an entry goes in, and the authoritative check — when a bridge is present — is consulted
 * <em>as well</em> rather than instead.
 *
 * <p><strong>This is structure, not calibration.</strong> It is a statement about which vanilla classes
 * override the server's controlling-passenger method, which is a fact about the game rather than a value
 * an operator could have an opinion about. Making it configurable would be offering to turn the
 * authority model off.
 */
public final class ClientSteeredRoots {

    private ClientSteeredRoots() {
    }

    /**
     * Whether {@code entity} is a type the server is known to let a rider's client steer.
     *
     * <p>Deliberately asked about the entity rather than about a rider: the server's real answer depends
     * on the rider and on saddles and on which passenger is first, so this is the <em>weaker</em>
     * question — "could this ever be client-steered" — which is the right one to refuse on, because a
     * vehicle that becomes client-steered only once somebody saddles it is a vehicle that breaks later.
     *
     * @param entity the prospective root
     * @return whether it must not be used as a server-authoritative vehicle's root
     */
    public static boolean isKnownClientSteered(@NotNull Entity entity) {
        // Delegated to the class form so there is one list rather than two. An entity's own class
        // implements the Bukkit interfaces, so this is the same question instanceof asked.
        return isKnownClientSteered(entity.getClass());
    }

    /**
     * Whether {@code entityClass} is a type the server is known to let a rider's client steer.
     *
     * <p>The same question as {@link #isKnownClientSteered(Entity)}, asked before anything exists in the
     * world. Which is what a seat carrier needs: its type comes from configuration, and an operator who
     * named a boat must be refused <em>without</em> a boat having been spawned and removed again. The
     * argument may be a Bukkit interface, as {@code EntityType#getEntityClass()} returns, or a server's
     * own implementation class; assignability answers for both.
     *
     * @param entityClass the prospective type
     * @return whether it must not be ridden under this plugin's authority model
     */
    public static boolean isKnownClientSteered(@NotNull Class<?> entityClass) {
        // Horses, donkeys, mules, skeleton and zombie horses, llamas and camels all reach the server's
        // AbstractHorse, which hands control to its first passenger once the conditions its class
        // imposes are met.
        return AbstractHorse.class.isAssignableFrom(entityClass)
                // Every boat and raft, including the chest variants, which are boats in Bukkit's
                // hierarchy as well as the server's.
                || Boat.class.isAssignableFrom(entityClass)
                // Saddled, and the saddle can be added after the vehicle was created.
                || Pig.class.isAssignableFrom(entityClass)
                || Strider.class.isAssignableFrom(entityClass)
                // Rideable by several players at once and steered by the one in the driving seat, which
                // makes it the worst possible root for a server-authoritative vehicle.
                || HappyGhast.class.isAssignableFrom(entityClass);
    }
}
