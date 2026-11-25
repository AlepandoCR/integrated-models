package pandastic.studio.models.internal.vehicle;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.internal.nms.MovementBridge;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * A seat carrier backed by a real entity: the second of the two classes in this package that name a
 * platform type.
 *
 * <h2>What the carrier is, and why it is spawned the way it is</h2>
 * An invisible, weightless, un-ticked, <strong>non-persistent</strong> entity whose only job is to be
 * somewhere a player can sit. Every property it is given is defensive and each one is a failure mode that
 * has happened to somebody:
 * <ul>
 *   <li><strong>Not persistent</strong>, which is the safety net under every other cleanup path in this
 *       layer. Carriers are released on teardown, on the root retiring and on plugin disable, but a
 *       release is scheduled work and a server can be killed before it runs. A non-persistent entity is
 *       never written to the region file, so the very worst case is an invisible carrier that lives until
 *       the chunk unloads and is gone for good after a restart, rather than a world that accumulates one
 *       per crash forever.</li>
 *   <li><strong>No gravity and no AI</strong>, because every position it has is written by this layer. An
 *       entity that also falls fights those writes, and the rider sees the difference.</li>
 *   <li><strong>Invulnerable, silent, not collidable</strong>: it is furniture, not a creature. A carrier
 *       that could be hit, heard or pushed would be a way to move a rider the vehicle did not sanction.
 *       </li>
 *   <li><strong>Marker, when it is an armour stand</strong> — the default and the only type this project
 *       has designed for. A marker has no bounding box at all, which is what makes the collision-aware
 *       move in {@link #moveLocal} land the seat exactly where the model says instead of resolving it
 *       against the vehicle's own geometry.</li>
 * </ul>
 * All of it is applied in the spawn consumer, before the entity is added to the world, so a carrier is
 * never visible for even one tick.
 *
 * <h2>Why the type is configuration and is nevertheless checked</h2>
 * An operator may name a different type — a different version, a resource pack, a preference about which
 * entity is cheapest to tick. What they may not do is name a type the server itself steers from its first
 * passenger: a rider on a boat-shaped carrier has a client that believes it is driving, and every
 * position written for that seat becomes a correction the client has already drawn past. So
 * {@link ClientSteeredRoots} is consulted about the configured class <em>before</em> anything is spawned,
 * which is the same refusal the root entity gets and the same reason for it.
 *
 * <h2>Threading</h2>
 * Everything is confined to the thread that owns the carrier, which is the thread that owns the vehicle's
 * root — the two entities sit within a block of each other and Folia owns nearby chunks together. The one
 * exception is {@link #release()}, which is callable from anywhere and schedules itself onto the
 * <strong>carrier's own</strong> entity scheduler. That is the whole reason the method exists: at the
 * moment cleanup matters most, the root's scheduler is the thing that has died.
 */
public final class EntitySeatCarrier implements SeatCarrier {

    /**
     * Delay of the deferred release, in ticks.
     *
     * <p><strong>A platform constraint, not a tunable.</strong> The entity scheduler treats any delay
     * below one tick as one tick, so this is the soonest deferred work can run — and the soonest is what
     * is wanted, because until it runs there is an invisible entity in the world.
     */
    private static final long DEFERRED_DELAY_TICKS = 1L;

    /**
     * Pitch written alongside every heading.
     *
     * <p><strong>Structural</strong>, for the reason {@link EntityVehicleHost} gives: the rotation call
     * takes both and this layer has no opinion about the pitch. A rider's own view direction is theirs.
     */
    private static final float LEVEL_PITCH_DEGREES = 0F;

    private final @NotNull Plugin plugin;
    private final @NotNull Entity entity;
    private final @NotNull UUID id;
    private final @NotNull MovementBridge movement;
    private final boolean localMove;

    private EntitySeatCarrier(
            @NotNull Plugin plugin,
            @NotNull Entity entity,
            @NotNull MovementBridge movement
    ) {
        this.plugin = plugin;
        this.entity = entity;
        this.movement = movement;
        // Captured once, because the teardown path needs it from a thread that may not own the entity.
        this.id = entity.getUniqueId();
        this.localMove = movement.supportsLocalMove();
    }

    /**
     * Spawns a carrier at an absolute position, or answers {@code null} with nothing left behind.
     *
     * <p>Owning thread only, and only for a position inside the chunks that region owns. Three things
     * answer {@code null}, and none of them is an exception because none of them is a mistake the calling
     * code made: a type name the platform does not recognise, a type that cannot be spawned, and a type
     * the server would let the rider's client steer. Each leaves the seat unoccupied and reported.
     *
     * @param plugin         whose scheduler owns the deferred release
     * @param root           the vehicle's root entity, which supplies the world
     * @param movement       the Mojang-mapped movement seam, shared with the root's host
     * @param entityTypeName the platform's own name for the type, from configuration
     */
    static @Nullable EntitySeatCarrier spawn(
            @NotNull Plugin plugin,
            @NotNull Entity root,
            @NotNull MovementBridge movement,
            @NotNull String entityTypeName,
            double x,
            double y,
            double z,
            float yawDegrees
    ) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(movement, "movement");
        Objects.requireNonNull(entityTypeName, "entityTypeName");

        final EntityType type = typeOf(entityTypeName);
        if (type == null || !type.isSpawnable()) {
            return null;
        }
        final Class<? extends Entity> carrierClass = type.getEntityClass();
        if (carrierClass == null || ClientSteeredRoots.isKnownClientSteered(carrierClass)) {
            // Refused before anything exists in the world, so a misconfigured type costs a log line
            // rather than an entity that has to be cleaned up.
            return null;
        }

        final Location at =
                new Location(root.getWorld(), x, y, z, yawDegrees, LEVEL_PITCH_DEGREES);
        final Entity spawned = root.getWorld().spawnEntity(
                at, type, CreatureSpawnEvent.SpawnReason.CUSTOM, EntitySeatCarrier::prepare);
        return new EntitySeatCarrier(plugin, spawned, movement);
    }

    /**
     * Makes a freshly created entity into furniture.
     *
     * <p>Runs inside the spawn call, before the entity is added to the world, which is what keeps a
     * carrier from being visible for a tick.
     */
    private static void prepare(@NotNull Entity spawned) {
        spawned.setPersistent(false);
        spawned.setSilent(true);
        spawned.setInvulnerable(true);
        spawned.setGravity(false);
        if (spawned instanceof final LivingEntity living) {
            living.setAI(false);
            living.setCollidable(false);
        }
        if (spawned instanceof final ArmorStand stand) {
            // The default and the designed-for case. Marker last because it is the one that removes the
            // bounding box, and the others read better as a description of what is left.
            stand.setVisible(false);
            stand.setBasePlate(false);
            stand.setCanTick(false);
            stand.setMarker(true);
        }
    }

    /**
     * The configured name as a type, or {@code null}.
     *
     * <p>{@code valueOf} with the exception caught rather than a registry lookup, because the exception
     * <em>is</em> the answer here and the alternative API is deprecated. Upper-cased and trimmed first, so
     * an operator writing {@code armor_stand} gets what they meant.
     */
    private static @Nullable EntityType typeOf(@NotNull String entityTypeName) {
        try {
            return EntityType.valueOf(entityTypeName.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException unknown) {
            return null;
        }
    }

    @Override
    public boolean isAlive() {
        return this.entity.isValid();
    }

    @Override
    public boolean isOwnedByCurrentThread() {
        return Bukkit.isOwnedByCurrentRegion(this.entity);
    }

    @Override
    public boolean readInto(@NotNull SeatSample sample) {
        Objects.requireNonNull(sample, "sample");
        if (!this.entity.isValid()) {
            return false;
        }
        // The component getters rather than getLocation(), which would allocate a Location per rider per
        // tick for three numbers.
        sample.set(this.entity.getX(), this.entity.getY(), this.entity.getZ());
        return true;
    }

    @Override
    public boolean moveLocal(double deltaX, double deltaY, double deltaZ, float yawDegrees) {
        // Heading first, matching the root's order: the rotation is legal on its own and the move may be
        // refused, so writing it first means a carrier that could not be moved is at least facing the
        // right way.
        this.entity.setRotation(yawDegrees, LEVEL_PITCH_DEGREES);
        return this.movement.moveLocal(this.entity, deltaX, deltaY, deltaZ);
    }

    @Override
    public boolean supportsLocalMove() {
        return this.localMove;
    }

    @Override
    public void teleport(
            double x,
            double y,
            double z,
            float yawDegrees,
            @NotNull VehicleHost.BooleanSink whenDone
    ) {
        Objects.requireNonNull(whenDone, "whenDone");
        final Location destination =
                new Location(this.entity.getWorld(), x, y, z, yawDegrees, LEVEL_PITCH_DEGREES);
        this.entity.teleportAsync(destination, PlayerTeleportEvent.TeleportCause.PLUGIN)
                // whenComplete rather than thenAccept: a relocation that failed has to clear the seat's
                // gate just as surely as one that succeeded, or the seat is never followed again.
                .whenComplete((succeeded, failure) ->
                        whenDone.accept(failure == null && Boolean.TRUE.equals(succeeded)));
    }

    @Override
    public boolean mount(@NotNull Player rider) {
        Objects.requireNonNull(rider, "rider");
        // addPassenger rather than the rider's own startRiding, because the carrier is the thing this
        // thread owns. The platform refuses a cross-region mount, which arrives here as false.
        return this.entity.addPassenger(rider);
    }

    @Override
    public @Nullable Player rider() {
        for (final Entity passenger : this.entity.getPassengers()) {
            if (passenger instanceof final Player player) {
                return player;
            }
        }
        return null;
    }

    @Override
    public boolean isClientSteered(@NotNull Player rider) {
        Objects.requireNonNull(rider, "rider");
        // The authoritative answer when an adapter is present; false when there is none, which is why
        // the type-level refusal in spawn(...) is consulted as well rather than instead.
        return this.movement.isClientSteered(this.entity, rider);
    }

    @Override
    public void release() {
        if (isOwnedByCurrentThread()) {
            removeNow();
            return;
        }
        // The carrier's own scheduler, never the root's: this is the path a teardown takes when the root
        // entity has retired, and the root's scheduler is exactly what no longer exists then. A false
        // return means the carrier is already gone, which is the outcome asked for.
        this.entity.getScheduler().execute(this.plugin, this::removeNow, null, DEFERRED_DELAY_TICKS);
    }

    private void removeNow() {
        // Ejected before removal, deliberately. Removing a vehicle with a passenger leaves the rider
        // where the carrier was with no ground under them; ejecting first puts them down the way getting
        // off would.
        this.entity.eject();
        this.entity.remove();
    }

    /**
     * This carrier in one short phrase.
     *
     * <p>Not on the seam: nothing above it names a carrier, because a seat is named by its own id and
     * that is what a diagnostic says. Kept here because the identity was captured anyway — reading it
     * off a removed entity is not guaranteed to work — and a {@code toString} that cannot say which
     * entity it is about is worth nothing in a debugger.
     */
    private @NotNull String describe() {
        return this.entity.getType() + " " + this.id;
    }

    @Override
    public @NotNull String toString() {
        return "EntitySeatCarrier[" + describe() + "]";
    }
}
