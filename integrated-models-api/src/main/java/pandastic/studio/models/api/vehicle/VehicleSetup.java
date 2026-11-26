package pandastic.studio.models.api.vehicle;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * The three things that are decided <em>per kind of vehicle</em> by the consumer: what it says, what
 * its seats' inputs do, and the one object that owns its behaviour.
 *
 * <h2>Why these travel together and why they are not on the profile</h2>
 * A {@link VehicleProfile} is numbers — speeds, rates, a control scheme — and is validated against the
 * operator's bounds. These are behaviour the consumer owns outright: there is no server-side range
 * for "what a gunner's trigger does" and no operator opinion about it. Keeping them apart means the
 * profile stays a thing that can be refused and this stays a thing that cannot.
 *
 * <p>They travel together because they are the same decision from the consumer's side: "this is a
 * cannon", which means these messages, these controls and this controller. A plugin shipping three
 * kinds of vehicle builds three of these once, at enable, and reuses each for every vehicle of that
 * kind.
 *
 * <h2>One controller, and a map of listeners</h2>
 * {@link #controller()} is <strong>one</strong> object and not a list, which is the shape
 * {@link VehicleController} argues for: it exists to hold a kind's own state and invariants in fields,
 * and a list would put two owners on one set of invariants with no rule for which of them wins when
 * they disagree about shutting the vehicle down. The composing shape already exists and is
 * {@link #controls()}: {@link VehicleControls} is a map of per-seat listeners and two listeners on one
 * seat both run. So a consumer with several independent reactions wires several listeners, and a
 * consumer with one stateful machine supplies one controller.
 *
 * <p>Both may be supplied, and then both run. The order is fixed and documented on
 * {@link VehicleController#onPlayerInput}: for one seat, that seat's composed listener first and the
 * controller afterwards, which extends the specific-before-general rule {@link VehicleControls} already
 * resolves its own bindings by.
 *
 * @param messages   what this vehicle says to its riders. {@link VehicleMessages#defaults()} when the
 *                   consumer has no opinion
 * @param controls   what each seat's inputs do beyond the steering the rig declares.
 *                   {@link VehicleControls#NONE} when the consumer has no opinion
 * @param controller the one object that owns this kind of vehicle's behaviour.
 *                   {@link VehicleController#NONE} when the consumer has no opinion — never
 *                   {@code null}, so a vehicle's control step has nothing to check
 */
public record VehicleSetup(
        @NotNull VehicleMessages messages,
        @NotNull VehicleControls controls,
        @NotNull VehicleController controller
) {

    /**
     * This plugin's own messages and no per-seat controls. What the three-argument create uses.
     *
     * <p>Touching this constant loads {@code VehicleNotificationConfig}, and therefore pandastic-core,
     * because our defaults <em>are</em> that declaration's defaults and there is deliberately no second
     * copy of them. Inside this plugin that is free — pandastic-core travels in the shaded jar, which is
     * also the classloader a downstream plugin resolves these classes from. The one arrangement where it
     * matters is a consumer who shades this api jar into their own plugin without pandastic-core; such a
     * consumer supplies their own {@link VehicleMessages} and never reaches this field.
     */
    public static final @NotNull VehicleSetup DEFAULT =
            new VehicleSetup(VehicleMessages.defaults(), VehicleControls.NONE);

    public VehicleSetup {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(controls, "controls");
        Objects.requireNonNull(controller, "controller");
    }

    /**
     * Messages and per-seat controls, with no controller.
     *
     * <p>The shape this record had before {@link VehicleController} existed, kept as a constructor so
     * that a consumer who has nothing to say about a controller says nothing rather than naming a
     * constant. {@link VehicleController#NONE} is the identity: a vehicle built this way behaves exactly
     * as it did before the hook existed.
     *
     * @param messages what the vehicle says
     * @param controls what each seat's inputs do
     */
    public VehicleSetup(@NotNull VehicleMessages messages, @NotNull VehicleControls controls) {
        this(messages, controls, VehicleController.NONE);
    }

    /**
     * This setup with different messages.
     *
     * @param messages what the vehicle says
     * @return the setup
     */
    @Contract(value = "_ -> new", pure = true)
    public @NotNull VehicleSetup withMessages(@NotNull VehicleMessages messages) {
        return new VehicleSetup(messages, this.controls, this.controller);
    }

    /**
     * This setup with different controls.
     *
     * @param controls what each seat's inputs do
     * @return the setup
     */
    @Contract(value = "_ -> new", pure = true)
    public @NotNull VehicleSetup withControls(@NotNull VehicleControls controls) {
        return new VehicleSetup(this.messages, controls, this.controller);
    }

    /**
     * This setup with a different controller.
     *
     * <p>Replaces rather than composes, which is the whole difference between this and
     * {@link VehicleControls.Builder#onSeat}: a second call to that composes two listeners for one seat,
     * and a second call to this says "that object, not the other one". A kind of vehicle has one owner
     * of its invariants; see the class note.
     *
     * @param controller the object that owns this kind of vehicle's behaviour
     * @return the setup
     */
    @Contract(value = "_ -> new", pure = true)
    public @NotNull VehicleSetup withController(@NotNull VehicleController controller) {
        return new VehicleSetup(this.messages, this.controls, controller);
    }

    /**
     * Only messages, with no per-seat controls.
     *
     * @param messages what the vehicle says
     * @return the setup
     */
    @Contract(value = "_ -> new", pure = true)
    public static @NotNull VehicleSetup messages(@NotNull VehicleMessages messages) {
        return new VehicleSetup(messages, VehicleControls.NONE);
    }

    /**
     * Only controls, with this plugin's own messages.
     *
     * @param controls what each seat's inputs do
     * @return the setup
     */
    @Contract(value = "_ -> new", pure = true)
    public static @NotNull VehicleSetup controls(@NotNull VehicleControls controls) {
        return new VehicleSetup(VehicleMessages.defaults(), controls);
    }

    /**
     * Only a controller, with this plugin's own messages and no per-seat controls.
     *
     * <p>The form to expect for a vehicle whose behaviour is one state machine — a tank, a crane, a
     * submarine — because such a consumer has nothing to say per seat: the controller is told every
     * seat's input anyway, with {@link SeatInput#seatIndex()} to tell them apart.
     *
     * @param controller the object that owns this kind of vehicle's behaviour
     * @return the setup
     */
    @Contract(value = "_ -> new", pure = true)
    public static @NotNull VehicleSetup controller(@NotNull VehicleController controller) {
        return new VehicleSetup(VehicleMessages.defaults(), VehicleControls.NONE, controller);
    }

    /** A silent vehicle with no per-seat controls and no controller, for one used as scenery. */
    @Contract(value = "-> new", pure = true)
    public static @NotNull VehicleSetup silent() {
        return new VehicleSetup(VehicleMessages.SILENT, VehicleControls.NONE);
    }
}
