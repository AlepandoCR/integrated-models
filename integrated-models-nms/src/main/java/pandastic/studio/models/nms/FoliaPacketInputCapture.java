package pandastic.studio.models.nms;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.internal.nms.InputCaptureBridge;
import pandastic.studio.models.internal.nms.PacketInputListener;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Folia's packet-level input capture: a handler in the player's own pipeline, in front of the server's.
 *
 * <h2>Why this reads the packet by shape rather than by name</h2>
 * Every member this class needs is either protected, private, or a record accessor whose name is part
 * of the mapping set — which makes the obvious implementation, typed Mojang-mapped field reads, the one
 * that breaks on a version bump with a {@link NoSuchFieldError} inside a player's connection. So
 * nothing here names a server member at all:
 *
 * <ul>
 *   <li><strong>The connection.</strong> Found by walking the entity handle's fields for the first
 *       {@link Channel}, breadth first and depth-limited. The real path is three fields deep and all
 *       three are inaccessible from outside their own classes; what the walk relies on is that there
 *       is exactly one netty channel reachable from a player, which is a structural fact about a
 *       connection rather than a name.</li>
 *   <li><strong>The seven flags.</strong> The movement packet is a record with one component, and that
 *       component's type is a record of exactly seven booleans — the client's own field order, which is
 *       also {@link pandastic.studio.models.api.input.InputKey}'s bit order. Reading them by
 *       {@link RecordComponent} position needs no name and cannot be renamed out from under us.</li>
 * </ul>
 *
 * <p>The packet <em>class</em> is still named, as a string, and it is the one thing that genuinely has
 * to be: there is no other way to say which packet this is. It is resolved once, at class load, and a
 * failure to resolve it — a renamed class, a server fork that moved it — becomes
 * {@link #isAvailable()} returning {@code false} and a sentence an operator reads at start-up, rather
 * than a capability that silently never works.
 *
 * <h2>Shape verification is the whole safety argument</h2>
 * A name-free read is only safe if the shape is checked, so it is checked completely and up front: the
 * class must be a record, exactly one component must be a record of exactly seven booleans, and every
 * accessor must be reachable. Anything else and this bridge declares itself unavailable. The failure
 * mode that is being designed away is a packet whose eighth flag we would read as the seventh.
 *
 * <h2>Threading</h2>
 * {@link #attach} is called from the thread that owns the player; the pipeline mutation is handed to
 * the channel's own event loop, because a netty pipeline may only be modified there. The interceptor
 * itself runs on that event loop — a network thread — which is exactly what the
 * {@link PacketInputListener} contract is written for. Nothing in this class touches the world.
 *
 * <h2>Our own failure never drops a packet</h2>
 * If reading the flags throws, the packet is passed on unchanged. A bug here must degrade to "input
 * capture missed one" and never to "the player stopped being able to move", which is what dropping on
 * failure would mean.
 */
final class FoliaPacketInputCapture implements InputCaptureBridge {

    /**
     * The client's movement input packet.
     *
     * <p>The one server name in this file, and a string rather than a typed reference so that a
     * renamed or relocated class is a start-up sentence instead of a link error. Protocol structure: it
     * names which packet carries the seven movement flags, and there is no other way to say that.
     */
    private static final @NotNull String INPUT_PACKET_CLASS =
            "net.minecraft.network.protocol.game.ServerboundPlayerInputPacket";

    /**
     * How many booleans the movement input record carries.
     *
     * <p>Protocol structure: the client's movement field is seven bits wide — forward, backward, left,
     * right, jump, sneak, sprint, in that order. The count is what the shape check verifies, and the
     * order is what makes reading by component position correct.
     */
    private static final int MOVEMENT_FLAG_COUNT = 7;

    /** Our handler's name in the pipeline. Must be unique within it, and is what {@code detach} removes. */
    private static final @NotNull String HANDLER_NAME = "integrated-models-input";

    /**
     * The server's own packet handler, which ours goes in front of.
     *
     * <p>Structure, and the one platform convention this file relies on rather than verifies: the name
     * has been this for the whole of the netty era. Absence is handled — the handler is appended
     * instead — because appending still sees the packet, it just sees it after the server did.
     */
    private static final @NotNull String PIPELINE_ANCHOR = "packet_handler";

    /**
     * How deep the field walk looks for a channel.
     *
     * <p>Structure: the real path is handle to game listener to connection to channel, which is three
     * field reads. The bound exists so that a server whose connection does not look like that fails
     * fast rather than walking an object graph of unknown size on a region thread.
     */
    private static final int MAX_FIELD_DEPTH = 3;

    /** Resolved once at class load. Never {@code null}; carries its own failure. */
    private static final @NotNull Resolution RESOLUTION = resolve();

    /**
     * The handler installed per player, so {@link #detach} can find one it installed and no other.
     *
     * <p>Keyed by identity on the channel rather than by the player: a player who relogs is a new
     * {@link Player} object on a new channel, and a channel is what the handler is actually on. An
     * {@link IdentityHashMap} behind a monitor rather than a concurrent map, because the whole map is
     * touched twice per player per session and a server's join rate is not a contended path.
     */
    private final @NotNull Map<Channel, ChannelInboundHandlerAdapter> installed =
            new IdentityHashMap<>();

    FoliaPacketInputCapture() {
        // Intentionally empty; everything is resolved statically or per player.
    }

    @Override
    public boolean isAvailable() {
        return RESOLUTION.failure() == null;
    }

    @Override
    public @NotNull String describe() {
        final String failure = RESOLUTION.failure();
        return failure == null
                ? "packet-level input capture is installed"
                : "packet-level input capture could not be set up (" + failure + "), so a movement"
                        + " input cannot be suppressed and cannot be observed before the server"
                        + " applies it";
    }

    @Override
    public boolean attach(@NotNull Player player, @NotNull PacketInputListener listener) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(listener, "listener");
        if (!isAvailable()) {
            return false;
        }

        final Channel channel = channelOf(player);
        if (channel == null) {
            return false;
        }

        final ChannelInboundHandlerAdapter handler = new Interceptor(player, listener);
        synchronized (this.installed) {
            if (this.installed.containsKey(channel)) {
                // Idempotent: a plugin reload and a relog can both reach this for a live channel, and
                // two interceptors would publish every input twice.
                return true;
            }
            this.installed.put(channel, handler);
        }

        // A pipeline may only be mutated on its own event loop. Handing it over rather than doing it
        // here is also what keeps this call cheap on the region thread that owns the player.
        channel.eventLoop().execute(() -> install(channel, handler));

        // Optimistic by necessity: the mutation is asynchronous, so the only honest alternative would
        // be to block a region thread on a network event loop. A channel that dies before the task
        // runs takes the task with it, and detach() still finds and forgets the entry.
        return true;
    }

    @Override
    public void detach(@NotNull Player player) {
        Objects.requireNonNull(player, "player");
        final Channel channel = channelOf(player);
        if (channel == null) {
            return;
        }

        final ChannelInboundHandlerAdapter handler;
        synchronized (this.installed) {
            handler = this.installed.remove(channel);
        }
        if (handler == null) {
            return;
        }

        if (channel.isOpen()) {
            channel.eventLoop().execute(() -> remove(channel, handler));
        }
        // A closed channel's pipeline is being torn down anyway, and scheduling on a dead event loop
        // would be rejected. Forgetting the entry is the whole of what is left to do.
    }

    private static void install(
            @NotNull Channel channel,
            @NotNull ChannelInboundHandlerAdapter handler
    ) {
        try {
            final ChannelPipeline pipeline = channel.pipeline();
            if (pipeline.get(HANDLER_NAME) != null) {
                return;
            }
            if (pipeline.get(PIPELINE_ANCHOR) != null) {
                pipeline.addBefore(PIPELINE_ANCHOR, HANDLER_NAME, handler);
            } else {
                pipeline.addLast(HANDLER_NAME, handler);
            }
        } catch (final RuntimeException ignored) {
            // The channel closed between the check and the mutation, which netty reports by throwing
            // from the pipeline. There is nothing to recover and nobody to tell: the player whose
            // connection it was is gone.
        }
    }

    private static void remove(
            @NotNull Channel channel,
            @NotNull ChannelInboundHandlerAdapter handler
    ) {
        try {
            channel.pipeline().remove(handler);
        } catch (final RuntimeException ignored) {
            // Already removed, or the pipeline is gone. Both are the state we wanted.
        }
    }

    /**
     * The netty channel of {@code player}'s connection, or {@code null}.
     *
     * <p>Breadth first, so the shallowest channel wins, and depth-limited. The walk only follows fields
     * whose declaring class is the server's own, which is what keeps it from wandering into the JDK and
     * tripping over a module boundary.
     */
    private static @Nullable Channel channelOf(@NotNull Player player) {
        try {
            final Object handle = player.getClass().getMethod("getHandle").invoke(player);
            return handle == null ? null : search(handle);
        } catch (final ReflectiveOperationException | RuntimeException unreachable) {
            return null;
        }
    }

    private static @Nullable Channel search(@NotNull Object root) {
        final Deque<Object> frontier = new ArrayDeque<>();
        final Deque<Object> next = new ArrayDeque<>();
        frontier.add(root);

        for (int depth = 0; depth < MAX_FIELD_DEPTH && !frontier.isEmpty(); depth++) {
            while (!frontier.isEmpty()) {
                final Object owner = frontier.removeFirst();
                for (final Field field : fieldsOf(owner.getClass())) {
                    final Object value = read(field, owner);
                    if (value instanceof Channel channel) {
                        return channel;
                    }
                    if (value != null && isServerType(value.getClass())) {
                        next.addLast(value);
                    }
                }
            }
            frontier.addAll(next);
            next.clear();
        }
        return null;
    }

    /** Every instance field declared by {@code type} or any supertype below {@link Object}. */
    private static @NotNull List<Field> fieldsOf(@NotNull Class<?> type) {
        final List<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (final Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.getType().isPrimitive()) {
                    fields.add(field);
                }
            }
        }
        return fields;
    }

    private static @Nullable Object read(@NotNull Field field, @NotNull Object owner) {
        try {
            field.setAccessible(true);
            return field.get(owner);
        } catch (final RuntimeException | ReflectiveOperationException inaccessible) {
            // A field in a module that is not open to us, or one whose read throws. Neither is on the
            // path to a channel, so skipping is the whole response.
            return null;
        }
    }

    /**
     * Whether a value is worth walking into.
     *
     * <p>Only the server's own classes. Following anything else means following a collection, a string
     * or a JDK internal, which cannot own a connection and can own an enormous object graph.
     */
    private static boolean isServerType(@NotNull Class<?> type) {
        final String name = type.getName();
        return name.startsWith("net.minecraft.") || name.startsWith("org.bukkit.craftbukkit.");
    }

    /**
     * Resolves the packet class and verifies its shape.
     *
     * <p>Every refusal names what was wrong, because the sentence is the only thing an operator on an
     * unsupported build can act on.
     */
    private static @NotNull Resolution resolve() {
        final Class<?> packetType;
        try {
            packetType = Class.forName(INPUT_PACKET_CLASS);
        } catch (final ClassNotFoundException | LinkageError missing) {
            return Resolution.failed("the movement input packet class " + INPUT_PACKET_CLASS
                    + " is not on this server (" + missing + ")");
        }

        if (!packetType.isRecord()) {
            return Resolution.failed(INPUT_PACKET_CLASS + " is no longer a record, so its components"
                    + " cannot be read by position");
        }

        final MethodHandles.Lookup lookup = MethodHandles.lookup();
        Class<?> flagsType = null;
        MethodHandle flagsAccessor = null;
        for (final RecordComponent component : packetType.getRecordComponents()) {
            final Class<?> candidate = component.getType();
            if (!candidate.isRecord() || !isSevenBooleans(candidate)) {
                continue;
            }
            if (flagsType != null) {
                return Resolution.failed(INPUT_PACKET_CLASS + " has more than one component that"
                        + " looks like the movement flags, so which one carries them is a guess");
            }
            try {
                flagsAccessor = lookup.unreflect(component.getAccessor());
                flagsType = candidate;
            } catch (final IllegalAccessException | RuntimeException unreachable) {
                return Resolution.failed("the movement flags of " + INPUT_PACKET_CLASS
                        + " cannot be read (" + unreachable + ")");
            }
        }

        if (flagsType == null) {
            return Resolution.failed(INPUT_PACKET_CLASS + " carries no record of "
                    + MOVEMENT_FLAG_COUNT + " booleans, so the movement flags are not where this"
                    + " adapter expects them");
        }

        final MethodHandle[] flags = new MethodHandle[MOVEMENT_FLAG_COUNT];
        final RecordComponent[] components = flagsType.getRecordComponents();
        for (int index = 0; index < MOVEMENT_FLAG_COUNT; index++) {
            try {
                flags[index] = lookup.unreflect(components[index].getAccessor());
            } catch (final IllegalAccessException | RuntimeException unreachable) {
                return Resolution.failed("flag " + index + " of " + flagsType.getName()
                        + " cannot be read (" + unreachable + ")");
            }
        }
        return new Resolution(packetType, flagsAccessor, flags, null);
    }

    private static boolean isSevenBooleans(@NotNull Class<?> type) {
        final RecordComponent[] components = type.getRecordComponents();
        if (components.length != MOVEMENT_FLAG_COUNT) {
            return false;
        }
        for (final RecordComponent component : components) {
            if (component.getType() != boolean.class) {
                return false;
            }
        }
        return true;
    }

    /**
     * What {@link #resolve()} found, or the sentence saying it found nothing usable.
     *
     * <p>Immutable and resolved once, so the interceptor's hot path is three array reads and two method
     * handle invocations with no checks of its own.
     */
    private record Resolution(
            @Nullable Class<?> packetType,
            @Nullable MethodHandle flagsAccessor,
            @Nullable MethodHandle[] flagAccessors,
            @Nullable String failure
    ) {

        static @NotNull Resolution failed(@NotNull String failure) {
            return new Resolution(null, null, null, failure);
        }

        /**
         * The seven flags of {@code packet}, in {@link pandastic.studio.models.api.input.InputKey}'s
         * bit order — which is the record's component order, which is the client's own.
         *
         * @throws Throwable whatever a method handle invocation throws; the caller passes the packet on
         *                   unchanged rather than dropping it
         */
        int bitsOf(@NotNull Object packet) throws Throwable {
            final Object flags = Objects.requireNonNull(this.flagsAccessor).invoke(packet);
            final MethodHandle[] accessors = Objects.requireNonNull(this.flagAccessors);
            int bits = 0;
            for (int index = 0; index < accessors.length; index++) {
                if ((boolean) accessors[index].invoke(flags)) {
                    bits |= 1 << index;
                }
            }
            return bits;
        }
    }

    /**
     * The per-player handler.
     *
     * <p>One instance per channel, because a netty handler that is not {@code Sharable} may only be in
     * one pipeline — and because it carries the player it belongs to, which is exactly the state a
     * shared handler could not have.
     */
    private static final class Interceptor extends ChannelInboundHandlerAdapter {

        private final @NotNull Player player;
        private final @NotNull PacketInputListener listener;

        private Interceptor(@NotNull Player player, @NotNull PacketInputListener listener) {
            this.player = player;
            this.listener = listener;
        }

        @Override
        public void channelRead(@NotNull ChannelHandlerContext context, @NotNull Object message)
                throws Exception {
            if (!Objects.requireNonNull(RESOLUTION.packetType()).isInstance(message)) {
                super.channelRead(context, message);
                return;
            }

            boolean drop = false;
            try {
                drop = this.listener.onMovementInput(this.player, RESOLUTION.bitsOf(message));
            } catch (final Throwable failure) {
                // Throwable, and not a narrower multi-catch: a method handle invocation declares it,
                // so there is no narrower type that covers the call. The rule from the class note
                // applies to all of it — our own failure must cost input capture and never cost the
                // player their ability to move, so `drop` stays false and the packet goes on.
                drop = false;
            }

            if (!drop) {
                super.channelRead(context, message);
            }
        }
    }
}
