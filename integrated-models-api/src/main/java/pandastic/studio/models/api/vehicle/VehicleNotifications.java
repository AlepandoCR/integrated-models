package pandastic.studio.models.api.vehicle;

import pandastic.studio.core.service.api.config.ConfigFormat;
import pandastic.studio.core.service.api.config.ConfigValue;
import pandastic.studio.core.service.api.config.Configurable;

/**
 * What one kind of vehicle says to its riders, as a file the <strong>consumer</strong> owns.
 *
 * <h2>This is the one configuration declaration that lives in the published api, on purpose</h2>
 * Every other tunable in this project belongs to the server operator and is declared in the internal
 * module, because the operator has one of each file and the plugin reads it. These messages are not like
 * that: a plugin shipping a horse, a mech and a hot-air balloon wants three different texts for "this
 * is full", and the only party that knows how many kinds of vehicle exist is the plugin that created
 * them. So the declaration is published, the implementation the annotation processor generates —
 * {@code VehicleNotificationConfig} — is published with it, and a downstream plugin points one instance
 * at one file per vehicle kind:
 *
 * <pre>{@code
 * VehicleNotificationConfig horseText =
 *         VehicleNotificationConfig.create(new File(getDataFolder(), "horse-messages.yml"));
 * horseText.loadOrCreate();                       // writes the defaults on first run
 * VehicleSetup setup = VehicleSetup.messages(VehicleMessages.of(horseText));
 * Vehicle horse = binder.create(rig, profile, root, setup);
 * }</pre>
 *
 * A consumer that supplies nothing gets this plugin's own defaults — the values written below — so the
 * messages are never blank and never English-by-accident in a server that configured everything else.
 *
 * <h2>Compiling against this needs pandastic-core on YOUR classpath</h2>
 * Stated plainly because it is the one thing about this type that can surprise a consumer at build
 * time. {@code integrated-models-api} declares pandastic-core as {@code compileOnly} and as an
 * {@code annotationProcessor}; <strong>neither scope is published</strong>, so our POM carries no
 * dependencies at all and nothing arrives transitively from our coordinate. That is deliberate: a
 * published api that dragged a runtime library behind it would put a second, possibly different copy of
 * pandastic-core on every consumer's classpath.
 *
 * <p>The assumption on the other side is equally plain: a plugin using this is assumed to already have
 * pandastic-core, which is the house configuration library. If it does not, the failure is an ordinary
 * missing-class compile error on {@code pandastic.studio.core.service.api.config.*} in <em>their</em>
 * build — not a defect in ours, and not something a reflective fallback here could paper over. A
 * consumer who does not want the dependency does not have to: {@link VehicleMessages} is a two-method
 * interface they can implement over any text source they like, and
 * {@link VehicleMessages#defaults()} covers the case where they want ours.
 *
 * <h2>Every value is MiniMessage, and every placeholder is optional</h2>
 * Messages are Adventure components, parsed from MiniMessage source so that colour, hover and click are
 * the author's. Placeholders are written {@code <like_this>} and resolved per message; one that a
 * particular notice has no value for resolves to a dash rather than being left as literal text.
 *
 * <p>Available everywhere: {@code player}, {@code vehicle}, {@code seats} (how many seats resolved),
 * {@code occupied}, {@code free}, {@code driver}. Available when the message is about one seat:
 * {@code seat}, {@code seat_index} (zero-based, what addresses it) and {@code seat_number} (counted from
 * one, what a player should read).
 *
 * <p>A <strong>blank</strong> value silences that one message, which is the project's convention
 * everywhere a line is optional. {@link #enabled()} silences all of them at once.
 *
 * @see VehicleNotice
 * @see VehicleMessages
 */
@Configurable(name = "VehicleNotificationConfig", format = ConfigFormat.YAML)
public interface VehicleNotifications {

    /**
     * Whether this vehicle says anything at all.
     *
     * <p>Off is a real choice rather than a debugging switch: a vehicle used as scenery, or one whose
     * plugin writes its own messages from {@link Vehicle#board}'s result, should be silent rather than
     * doubling up. Silencing one line at a time is what a blank value does.
     *
     * @return whether to send notifications
     */
    @ConfigValue(comment = "Whether this vehicle tells its riders anything at all. Off makes every message below silent in one switch, which is what a vehicle used as scenery - or one whose plugin writes its own messages from the boarding result - wants. To silence one message only, leave that message blank.")
    default boolean enabled() {
        return true;
    }

    /**
     * Prepended to every message below.
     *
     * <p>Separate from the messages so that re-branding is one edit rather than seven, and blank by
     * default because a prefix is a decision about a server's chat rather than about a vehicle.
     *
     * @return the MiniMessage source, or blank for none
     */
    @ConfigValue(comment = "Prepended to every message below. Blank for none. Separate so that re-branding is one edit rather than seven. MiniMessage.")
    default String prefix() {
        return "";
    }

    /**
     * Every usable seat is taken.
     *
     * @return the MiniMessage source
     */
    @ConfigValue(comment = "Sent when every usable seat is taken. Placeholders: player, vehicle, seats, occupied, free, driver. Blank silences it. MiniMessage.")
    default String vehicleFull() {
        return "<red><vehicle> is full.</red> <gray>(<occupied>/<seats> seats)</gray>";
    }

    /**
     * The seat they asked for is occupied, or there is no such seat.
     *
     * @return the MiniMessage source
     */
    @ConfigValue(comment = "Sent when a rider asks for a particular seat and it is occupied, or there is no seat of that index or name. Only an addressed request can reach this: boarding without naming a seat takes the lowest free one. Placeholders: all, including seat, seat_index and seat_number. Blank silences it. MiniMessage.")
    default String seatTaken() {
        return "<red>Seat <seat_number> of <vehicle> is not available.</red>";
    }

    /**
     * They are now driving.
     *
     * @return the MiniMessage source
     */
    @ConfigValue(comment = "Sent when a rider is seated in the driver's seat, which the first player to board takes. Worth saying out loud: a seat is an invisible carrier on a locator, so nothing on screen distinguishes driving from being carried. Placeholders: all. Blank silences it. MiniMessage.")
    default String nowDriver() {
        return "<green>You are driving <vehicle>.</green> "
                + "<gray>Seat <seat_number> · <seat></gray>";
    }

    /**
     * They are now a passenger.
     *
     * @return the MiniMessage source
     */
    @ConfigValue(comment = "Sent when a rider is seated anywhere but the driver's seat. Distinguished from the driver's message because the vehicle will not answer their keys, and a player who is not told that concludes it is broken. Placeholders: all. Blank silences it. MiniMessage.")
    default String nowPassenger() {
        return "<yellow>You are riding <vehicle>.</yellow> "
                + "<gray>Seat <seat_number> · <seat> · <driver> is driving</gray>";
    }

    /**
     * They are no longer aboard.
     *
     * @return the MiniMessage source
     */
    @ConfigValue(comment = "Sent when a rider's seat empties for any reason this plugin observes: they got up, they were put down, their carrier was removed, the model stopped rendering. Not sent when the whole vehicle is torn down at shutdown. Placeholders: all. Blank silences it. MiniMessage.")
    default String dismounted() {
        return "<gray>You are no longer riding <vehicle>.</gray>";
    }

    /**
     * They cannot board, and not because it is full.
     *
     * @return the MiniMessage source
     */
    @ConfigValue(comment = "Sent when boarding is refused for a reason other than the vehicle being full: the server would make this player's client the authority over what they are riding, no carrier could be spawned, or the vehicle is no longer running. Placeholders: all. Blank silences it. MiniMessage.")
    default String cannotBoard() {
        return "<red>You cannot board <vehicle> right now.</red>";
    }

    /**
     * This vehicle has no seat anybody can sit on.
     *
     * @return the MiniMessage source
     */
    @ConfigValue(comment = "Sent when the rig declares seats and not one of them resolved to a locator on the live model. A seat is a locator, never a bone, so a rig naming bones has no seats at all - which is the expected state of every model shipped with this plugin until a rigger adds locators. The operator's half of this is the seat chart in the console. Placeholders: player, vehicle, seats. Blank silences it. MiniMessage.")
    default String noSeatResolved() {
        return "<red><vehicle> has no seats you can sit on.</red> "
                + "<gray>Its rig names no locator a rider can use.</gray>";
    }
}
