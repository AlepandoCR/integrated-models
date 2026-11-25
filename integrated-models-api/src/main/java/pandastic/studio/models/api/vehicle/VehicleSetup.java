package pandastic.studio.models.api.vehicle;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * The two things that are decided <em>per vehicle</em> by the consumer: what it says, and what its
 * seats' inputs do.
 *
 * <h2>Why these two travel together and why they are not on the profile</h2>
 * A {@link VehicleProfile} is numbers — speeds, rates, a control scheme — and is validated against the
 * operator's bounds. These two are behaviour the consumer owns outright: there is no server-side range
 * for "what a gunner's trigger does" and no operator opinion about it. Keeping them apart means the
 * profile stays a thing that can be refused and this stays a thing that cannot.
 *
 * <p>They travel together because they are the same decision from the consumer's side: "this is a
 * cannon", which means these messages and these controls. A plugin shipping three kinds of vehicle
 * builds three of these once, at enable, and reuses each for every vehicle of that kind.
 *
 * @param messages what this vehicle says to its riders. {@link VehicleMessages#defaults()} when the
 *                 consumer has no opinion
 * @param controls what each seat's inputs do beyond the steering the rig declares.
 *                 {@link VehicleControls#NONE} when the consumer has no opinion
 */
public record VehicleSetup(@NotNull VehicleMessages messages, @NotNull VehicleControls controls) {

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
    }

    /**
     * This setup with different messages.
     *
     * @param messages what the vehicle says
     * @return the setup
     */
    @Contract(value = "_ -> new", pure = true)
    public @NotNull VehicleSetup withMessages(@NotNull VehicleMessages messages) {
        return new VehicleSetup(messages, this.controls);
    }

    /**
     * This setup with different controls.
     *
     * @param controls what each seat's inputs do
     * @return the setup
     */
    @Contract(value = "_ -> new", pure = true)
    public @NotNull VehicleSetup withControls(@NotNull VehicleControls controls) {
        return new VehicleSetup(this.messages, controls);
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

    /** A silent vehicle with no per-seat controls, for one used as scenery. */
    @Contract(value = "-> new", pure = true)
    public static @NotNull VehicleSetup silent() {
        return new VehicleSetup(VehicleMessages.SILENT, VehicleControls.NONE);
    }
}
