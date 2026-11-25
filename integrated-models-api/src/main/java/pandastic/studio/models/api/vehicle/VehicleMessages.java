package pandastic.studio.models.api.vehicle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Optional;

/**
 * What a vehicle says, per vehicle.
 *
 * <h2>Two methods wide, so that a consumer is never forced through our config</h2>
 * This is the seam the vehicle layer actually calls; {@link VehicleNotifications} is one way to satisfy
 * it and not the only one. A plugin with its own language system implements
 * {@link #render(VehicleNotice, VehicleNoticeContext)} over that instead, and never touches
 * pandastic-core. A plugin that wants a file gets one for free from
 * {@link #of(VehicleNotifications)}. A plugin that wants nothing to be said uses {@link #SILENT}.
 *
 * <h2>Rendered per call, not cached</h2>
 * Deliberate, and it is what makes a consumer's own reload work with no re-binding: the renderer holds
 * the {@link VehicleNotifications} instance and reads it each time, so a consumer who calls
 * {@code load()} on their config after an operator edited the file sees the new text on the next
 * message. Caching parsed components would make the vehicle hold text from before the reload, which is
 * the configuration-generation hazard this project is careful about everywhere else.
 *
 * <p>A notice is rendered at most a handful of times per rider per boarding, so parsing per call costs
 * nothing worth the staleness. Nothing in here is ever reached from a control step's hot path.
 *
 * <h2>Every substitution is unparsed</h2>
 * A player's name, a seat's name and a vehicle's name are not the message author's text. A player
 * called {@code <red>} must not be able to re-colour the rest of a line, and a rig called
 * {@code <click:run_command:...>} must not be able to put a command in one. {@link Placeholder#unparsed}
 * makes every value literal text — the same rule the menu layer applies to a model id read off disk.
 *
 * @see VehicleNotice
 * @see VehicleNotifications
 */
@FunctionalInterface
public interface VehicleMessages {

    /** A vehicle that says nothing at all. */
    @NotNull VehicleMessages SILENT = (notice, context) -> Optional.empty();

    /**
     * Renders what to tell the player, or nothing.
     *
     * <p>Empty means "say nothing", and it is a normal answer rather than a failure: the whole set can
     * be switched off, and any single message can be silenced by blanking it.
     *
     * @param notice  what happened
     * @param context the values its placeholders substitute
     * @return the message, or empty to stay silent
     */
    @NotNull Optional<Component> render(
            @NotNull VehicleNotice notice,
            @NotNull VehicleNoticeContext context
    );

    /**
     * The messages {@code source} describes, re-read on every render.
     *
     * @param source the configuration to read, typically a {@code VehicleNotificationConfig} the
     *               consumer has pointed at a file of their own
     * @return the renderer
     */
    @Contract(value = "_ -> new", pure = true)
    static @NotNull VehicleMessages of(@NotNull VehicleNotifications source) {
        Objects.requireNonNull(source, "source");
        return (notice, context) -> MiniMessageRenderer.render(source, notice, context);
    }

    /**
     * This plugin's own defaults, for a consumer that supplied nothing.
     *
     * <p>The defaults are the ones {@link VehicleNotifications}' {@code @ConfigValue} methods declare —
     * one source of truth, no second copy in a constant somewhere. The instance backing this is created
     * once, lazily, and is never bound to a file: there is no file for it, because these are the
     * compiled-in values a consumer has chosen not to override.
     *
     * <p>Note what this means for the operator: these particular strings are <em>not</em> in any
     * {@code .yml} on the server. They are not meant to be — the consumer plugin is the party that owns
     * a vehicle's wording, and the way to make it editable is for that plugin to ship a file. Saying so
     * here is the honest version of "nothing tunable is hardcoded": the value is tunable, and the knob
     * is in the consumer's hands rather than hidden.
     *
     * @return the default renderer
     */
    @Contract(pure = true)
    static @NotNull VehicleMessages defaults() {
        return Defaults.INSTANCE;
    }

    /**
     * The lazy holder for the default renderer.
     *
     * <p>A holder class rather than a field on the interface, because touching the generated
     * configuration class loads pandastic-core — and a consumer who implements
     * {@link VehicleMessages} themselves should never need it on their runtime classpath. Class
     * initialisation here is what defers that to the first caller who actually asks for our defaults.
     */
    final class Defaults {

        private static final @NotNull VehicleMessages INSTANCE =
                VehicleMessages.of(new VehicleNotificationConfig());

        private Defaults() {
        }
    }

    /**
     * The MiniMessage renderer behind {@link VehicleMessages#of(VehicleNotifications)}.
     *
     * <p>A class rather than a lambda body so that the placeholder set is written once and the
     * notice-to-key mapping is one {@code switch} a reader can check against
     * {@link VehicleNotifications} by eye.
     */
    final class MiniMessageRenderer {

        /**
         * The serialiser. Stateless and thread-safe by contract, so one instance serves every vehicle
         * on every thread; the parser is the expensive part of a build and one per message would be the
         * only measurable cost in this layer.
         */
        private static final @NotNull MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

        private MiniMessageRenderer() {
        }

        private static @NotNull Optional<Component> render(
                @NotNull VehicleNotifications source,
                @NotNull VehicleNotice notice,
                @NotNull VehicleNoticeContext context
        ) {
            Objects.requireNonNull(notice, "notice");
            Objects.requireNonNull(context, "context");
            if (!source.enabled()) {
                return Optional.empty();
            }
            final String template = templateOf(source, notice);
            if (template.isBlank()) {
                // Blank silences one message, which is this project's convention wherever a line is
                // optional. Checked before the prefix so that a prefix cannot resurrect a line the
                // author emptied on purpose.
                return Optional.empty();
            }
            final TagResolver[] resolvers = resolvers(context);
            final Component body = MINI_MESSAGE.deserialize(template, resolvers);
            final String prefix = source.prefix();
            return Optional.of(prefix.isBlank()
                    ? body
                    : MINI_MESSAGE.deserialize(prefix, resolvers).append(body));
        }

        private static @NotNull String templateOf(
                @NotNull VehicleNotifications source,
                @NotNull VehicleNotice notice
        ) {
            return switch (notice) {
                case VEHICLE_FULL -> source.vehicleFull();
                case SEAT_TAKEN -> source.seatTaken();
                case NOW_DRIVER -> source.nowDriver();
                case NOW_PASSENGER -> source.nowPassenger();
                case DISMOUNTED -> source.dismounted();
                case CANNOT_BOARD -> source.cannotBoard();
                case NO_SEAT_RESOLVED -> source.noSeatResolved();
            };
        }

        private static @NotNull TagResolver @NotNull [] resolvers(
                @NotNull VehicleNoticeContext context
        ) {
            return new TagResolver[]{
                    Placeholder.unparsed("player", context.player()),
                    Placeholder.unparsed("vehicle", context.vehicle()),
                    Placeholder.unparsed("seat", context.seat()),
                    Placeholder.unparsed("seat_index", context.seatIndexText()),
                    Placeholder.unparsed("seat_number", context.seatNumber()),
                    Placeholder.unparsed("seats", Integer.toString(context.seats())),
                    Placeholder.unparsed("occupied", Integer.toString(context.occupied())),
                    Placeholder.unparsed("free", Integer.toString(context.free())),
                    Placeholder.unparsed("driver", context.driver()),
            };
        }
    }
}
