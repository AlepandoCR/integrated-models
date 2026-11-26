package pandastic.studio.models.nms;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.internal.nms.MovementBridge;

import java.util.Objects;

/**
 * Folia's movement-application seam: an in-place collision-aware move, and the server's own answer to
 * who is driving.
 *
 * <h2>Why this exists at all, when the caller already has a working fallback</h2>
 * Because the fallback is a teleport, and a teleport cannot climb. The vehicle layer's other path
 * relocates through the platform's asynchronous teleport and <strong>drops the vertical component on
 * purpose</strong>: a relocation applies an absolute position with no interpolation, so a rise inside
 * one snaps however carefully it was spread. That is correct for what a relocation is for — crossing a
 * region boundary — and it means a host with no in-place move is a host whose vehicles simply do not
 * climb. The arithmetic that decided they should is computed, carried through a {@code TerrainResponse},
 * and then thrown away one call before it would have mattered. Carrying Y is therefore the whole point
 * of the method below, not a detail of it.
 *
 * <p>The second reason is cost, and it is the same missing adapter: with no local move every displaced
 * step is an asynchronous teleport transaction for the root plus one per occupied seat carrier. One
 * absent member therefore accounts both for the terrain climb not rendering and for the most expensive
 * thing the vehicle layer does.
 *
 * <h2>What {@code Entity#move(MoverType.SELF, …)} actually does</h2>
 * It is the server's own movement, not a position write, and the caller is about to rely on the
 * difference — a vehicle that climbed onto a block must not then sink into it. In order:
 *
 * <ol>
 *   <li><strong>Collision resolution.</strong> The displacement is swept against every collider the
 *       entity's bounding box would touch and clamped per axis, so the entity ends up where the world
 *       let it go rather than where it was asked to. That is what gives the wall stop, the slab climb and
 *       the ride over a fence post, and it is why {@link #moveLocal} promises only that the move was
 *       <em>applied</em> and never that it was applied in full.</li>
 *   <li><strong>Automatic step-up, only sometimes.</strong> It lives inside the server's collision pass
 *       and is gated on the entity being on the ground and on {@code maxUpStep()}. A plain
 *       {@code Entity} returns {@code 0} there; only a {@code LivingEntity} reads the step-height
 *       attribute, and it raises it to at least one block while a player is its controlling passenger —
 *       which a server-authoritative vehicle deliberately never has. <strong>So for this plugin's roots
 *       the vertical component the caller supplies is the only climb there is</strong>, and a vehicle
 *       asked to rise and advance in one call gets both resolved against the same blocks.</li>
 *   <li><strong>Ground, fall and block state.</strong> The horizontal, vertical and below-vertical
 *       collision flags are updated, {@code setOnGroundWithMovement} runs, fall distance and fall damage
 *       are evaluated, blocks the entity passed through get their effects applied, and the movement
 *       emission plays the step sound. A position write skips all of that and leaves the entity lying to
 *       its own next tick about whether it is standing on anything.</li>
 *   <li><strong>Nothing to velocity, nothing to passengers.</strong> {@code move} does not alter the
 *       entity's delta movement, so the entity's own tick keeps applying gravity and drag underneath
 *       this layer's horizontal writes — which is the arrangement the vehicle layer wants, because it
 *       writes the heading and lets the server do the falling. It also does not reposition passengers;
 *       they follow on the entity's next tick, and in this plugin they are not on the root at all.</li>
 * </ol>
 *
 * <p>One exception worth knowing because it is silent: an entity with {@code noPhysics} set skips the
 * collision pass entirely and is simply placed at the displaced position. Nothing in this plugin sets
 * that flag, but a root entity another plugin has marked that way will pass through walls and report no
 * collision, and this bridge cannot tell the difference.
 *
 * <h2>Threading</h2>
 * Called from the vehicle's control step, on the thread that owns the root entity, and <strong>not
 * defended against anything else</strong>. The server covers half of it for us and loudly: {@code move}
 * opens with Moonrise's {@code TickThread.ensureTickThread}, which logs a stack trace and throws
 * {@link IllegalStateException} when the caller is not a tick thread at all — so an attempt to move a
 * vehicle from the simulation pool fails immediately and names itself. What that check does <em>not</em>
 * cover is a <em>foreign region</em> thread, which is a tick thread and is undefined rather than racy;
 * the caller's own ownership gate is the only guarantee there, and a tolerant check here would hide the
 * bug instead of surfacing it.
 *
 * <h2>Why the capability is probed rather than assumed</h2>
 * {@link #supportsLocalMove()} answers for the members this class actually calls, on the server it is
 * actually running on; see {@link MappedMethods} for why a version-exact module still needs to ask. A
 * wrong {@code true} is worse than a {@code false}: the caller's teleport path is correct and merely
 * expensive, so a false negative costs frames, while a false positive makes the fast path throw a
 * {@link NoSuchMethodError} once per vehicle per tick from inside a region's step.
 */
final class FoliaMovementBridge implements MovementBridge {

    /**
     * What this server lets this bridge do, resolved once at class load.
     *
     * <p>At class load and not per call, because the answer is a property of the server rather than of
     * the entity, and because the construction site — plugin enable — is the only place a failure can
     * be reported before something depends on it. A failure never throws: an adapter that cannot move an
     * entity in place still carries the brain layer, and taking the whole bridge down with it would turn
     * a lost optimisation into a lost feature set.
     */
    private static final @NotNull Resolution RESOLUTION = resolve();

    /**
     * Package-private: a bridge is reached through {@link FoliaNmsBridgeProvider}, so that the version
     * check in the lookup cannot be bypassed by constructing one directly.
     */
    FoliaMovementBridge() {
        // Intentionally empty. Everything this class needs is resolved statically, and it holds no
        // per-entity state for the reason FoliaNmsBridge gives: one instance serves every vehicle on the
        // server, across every region thread.
    }

    @Override
    public boolean supportsLocalMove() {
        return RESOLUTION.selfMover() != null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Carries the vertical component, which is the one thing the teleport fallback cannot. See the
     * class documentation for exactly what the server's own move does with it.
     *
     * <p>A zero displacement is passed through rather than short-circuited: the collision pass is also
     * what refreshes the ground and fall state, and a bridge that quietly decided a step was not worth
     * making would be deciding something the caller is responsible for.
     *
     * @return {@code false} when the mapping did not resolve, when the entity is gone, and when it is
     *         not an entity this server owns — all three before anything is touched
     */
    @Override
    public boolean moveLocal(
            @NotNull Entity entity,
            double deltaX,
            double deltaY,
            double deltaZ
    ) {
        Objects.requireNonNull(entity, "entity");

        final MoverType mover = RESOLUTION.selfMover();
        if (mover == null) {
            return false;
        }
        // The platform's own predicate rather than the handle's isRemoved(), deliberately: it needs no
        // mapping, and it is the same predicate the vehicle layer's isAlive() uses — so the bridge and
        // its caller cannot disagree about what "gone" means. The check is this bridge's and not the
        // server's: move() does not refuse a removed entity, it runs a collision pass for something that
        // is no longer in the world's entity list.
        if (!entity.isValid() || !(entity instanceof CraftEntity craft)) {
            return false;
        }

        // SELF, and not PLAYER or PISTON. SELF is what the server uses for an entity moving under its own
        // simulation, which is exactly what a server-authoritative vehicle is doing; PLAYER additionally
        // means "driven by a client's input", and PISTON routes through the piston displacement limiter.
        // The choice also makes the server's own debug assertion ours: with SELF it checks that this side
        // is allowed to simulate the entity's movement, which is false precisely when the entity is
        // client-authoritative — the state isClientSteered(...) exists to keep a vehicle out of.
        craft.getHandle().move(mover, new Vec3(deltaX, deltaY, deltaZ));
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The authoritative half, and it is the server's own predicate rather than a reconstruction of
     * it. {@code Entity#isClientAuthoritative()} is what the server asks itself, and it reads
     * {@code getControllingPassenger()} and asks <em>that</em> entity — where a {@code Player} answers an
     * unconditional yes. Combined with an identity check on the rider, this answers the rider-specific
     * question the seam declares: not "is this type ever steerable" but "would this player's client be
     * simulating this entity, now that they are aboard it".
     *
     * <p>It is also the condition the packet handler uses. A client's vehicle-move packet is accepted
     * when the sender's root vehicle reports the sender as its controlling passenger, so an entity that
     * answers {@code true} here is one whose positions the server will take from a client and whose
     * server-side writes are therefore corrections.
     *
     * <p>Two conditions of that handler are deliberately not reproduced: that the rider's <em>root</em>
     * vehicle is this entity, and that it matches the connection's own last-known vehicle, which is
     * private to the connection and unreadable from here. Both can only make the server stricter than
     * this answer, so the divergence is in the safe direction — the same direction
     * {@code ClientSteeredRoots} is deliberately wrong in. An over-answer costs a refused mount and a
     * message naming the entity type; an under-answer costs permanent rubber-banding that reads as a
     * network fault.
     *
     * <p>Unlike the type check it supplements, this must be asked <em>after</em> the mount: before it
     * there is no controlling passenger to read, so the honest answer would be {@code false} for every
     * vehicle. The vehicle layer already asks it there, about both the root and the seat carrier the
     * rider is really sitting on, and that contract is unchanged by this implementation — only the
     * authoritative half of it has become real.
     */
    @Override
    public boolean isClientSteered(@NotNull Entity vehicle, @NotNull Player rider) {
        Objects.requireNonNull(vehicle, "vehicle");
        Objects.requireNonNull(rider, "rider");

        if (RESOLUTION.steerFailure() != null) {
            return false;
        }
        if (!(vehicle instanceof CraftEntity craftVehicle) || !(rider instanceof CraftEntity craftRider)) {
            return false;
        }

        final net.minecraft.world.entity.Entity handle = craftVehicle.getHandle();
        final LivingEntity controlling = handle.getControllingPassenger();
        // Identity, because the server's own check is identity: a second entity that happens to be equal
        // is not the one the connection is sending positions for.
        return controlling == craftRider.getHandle() && handle.isClientAuthoritative();
    }

    /**
     * Resolves both capabilities, independently, and never throws.
     *
     * <p>Independently because they fail for different reasons and cost different things: a server that
     * renamed the move call still answers the authority question correctly, and refusing both because
     * one is gone would turn a performance regression into a vehicle that cannot refuse a bad root.
     *
     * <p>The {@code MoverType.SELF} read is part of the probe rather than incidental to it. An enum
     * constant is a {@code getstatic} resolved on first execution, so a renamed one is a
     * {@link NoSuchFieldError} at the call site — the same lazy failure the method probe exists to
     * prevent. Reading it here, inside the guarded block, and carrying the value forward makes the thing
     * that was probed literally the thing that gets invoked.
     */
    private static @NotNull Resolution resolve() {
        try {
            final Class<?> entityType = net.minecraft.world.entity.Entity.class;

            final String moveFailure = MappedMethods.missing(
                    entityType, "move", void.class, MoverType.class, Vec3.class);

            String steerFailure = MappedMethods.missing(
                    entityType, "getControllingPassenger", LivingEntity.class);
            if (steerFailure == null) {
                steerFailure = MappedMethods.missing(
                        entityType, "isClientAuthoritative", boolean.class);
            }

            // Read after the method probe, so the enum's own resolution failure is reported as the move
            // capability being unavailable rather than as a failure of the whole bridge.
            final MoverType mover = moveFailure == null ? MoverType.SELF : null;
            return new Resolution(mover, moveFailure, steerFailure);
        } catch (final RuntimeException | LinkageError unmapped) {
            // The classes themselves, or MoverType.SELF. A Resolution with both halves refused is the
            // complete answer: the caller's teleport path and the type-level steering check are both
            // correct without us.
            final String failure = "this server's own entity, mover or vector types are not the ones "
                    + NmsTarget.MINECRAFT_VERSION + " was compiled against (" + unmapped + ")";
            return new Resolution(null, failure, failure);
        }
    }

    /**
     * What this server lets this bridge do, and the sentence for each half it does not.
     *
     * <p>The sentences are kept rather than discarded for {@link #toString()}, which is the only place
     * they can be read: {@link MovementBridge} has no reporting method, and adding one with no consumer
     * would be another capability that exists and cannot be reached. A bridge printed into a diagnostic
     * or an exception message carries its own reason with it.
     *
     * @param selfMover        the mover kind to pass to the server's move, non-{@code null} exactly when
     *                         the move capability resolved. Carrying the value is what makes
     *                         {@link #moveLocal} null-safe without a second flag to keep in step
     * @param localMoveFailure why an in-place move is unavailable, or {@code null}
     * @param steerFailure     why the authority question cannot be answered, or {@code null}
     */
    private record Resolution(
            @Nullable MoverType selfMover,
            @Nullable String localMoveFailure,
            @Nullable String steerFailure
    ) {
    }

    @Override
    public @NotNull String toString() {
        final String move = RESOLUTION.localMoveFailure();
        final String steer = RESOLUTION.steerFailure();
        return "FoliaMovementBridge[localMove="
                + (move == null ? "available" : "unavailable: " + move)
                + ", clientSteerQuery="
                + (steer == null ? "available" : "unavailable: " + steer)
                + "]";
    }
}
