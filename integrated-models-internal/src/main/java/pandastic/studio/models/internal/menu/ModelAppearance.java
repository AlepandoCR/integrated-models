package pandastic.studio.models.internal.menu;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * What the appearance screen edits: one model's visual state, as a value.
 *
 * <h2>Why a value rather than a handle on the model</h2>
 * Because the screen is a form and a form is not applied until it is submitted. Between opening the
 * screen and pressing Apply there exists a state that is <em>the player's intention</em> and not yet the
 * model's, and nothing else in the menu layer can represent it. Holding a handle instead would mean
 * every slider movement reaching the model, which is not what the client sends and not what the player
 * asked for.
 *
 * <p>It is also the half of the menu layer that can be tested exhaustively without a dialog, a player or
 * a server: six numbers and a clamp.
 *
 * <h2>Channels are integers</h2>
 * A colour channel is one byte on the wire, so the slider's {@code float} is rounded here rather than
 * carried further. Rounding at the boundary means the hex readout and the colour the model is tinted
 * with are derived from the same number — carry the float and they can disagree in the last digit, which
 * presents as a hex code that does not match the swatch beside it.
 *
 * @param glow            whether the model is outlined
 * @param red             red channel, 0-255
 * @param green           green channel, 0-255
 * @param blue            blue channel, 0-255
 * @param scale           uniform scale multiplier
 * @param physics         whether secondary motion runs
 * @param firstPersonArms whether the wearer sees the model's arms in first person
 */
public record ModelAppearance(
        boolean glow,
        int red,
        int green,
        int blue,
        float scale,
        boolean physics,
        boolean firstPersonArms
) {

    /**
     * Largest value a colour channel can hold.
     *
     * <p>A protocol fact rather than a tunable: a channel is one byte in the packet that carries it, so
     * 255 is not a policy about how bright a model may be.
     */
    public static final int CHANNEL_CEILING = 255;

    /** Smallest value a colour channel can hold. The other end of the same byte. */
    public static final int CHANNEL_FLOOR = 0;

    public ModelAppearance {
        if (red < CHANNEL_FLOOR || red > CHANNEL_CEILING
                || green < CHANNEL_FLOOR || green > CHANNEL_CEILING
                || blue < CHANNEL_FLOOR || blue > CHANNEL_CEILING) {
            throw new IllegalArgumentException("Colour channels must be within [" + CHANNEL_FLOOR + ", "
                    + CHANNEL_CEILING + "], but were [" + red + ", " + green + ", " + blue + "]");
        }
        if (!Float.isFinite(scale) || scale <= 0F) {
            throw new IllegalArgumentException(
                    "scale must be finite and positive, but was " + scale
                            + ": a zero-scaled model is invisible rather than small");
        }
    }

    /**
     * An appearance built from numbers that came off the wire.
     *
     * <h2>Why this is the only way to build one from a click</h2>
     * The constructor refuses an illegal colour, which is right for a value the plugin assembles and
     * wrong for one a client sent: a client is free to say a channel is 4000 or that the scale is a
     * thousand, because the slider bounds it was handed are a hint to its interface and not a constraint
     * on the packet. Refusing those would turn a hostile client into an exception in a click handler;
     * clamping them turns it into a model that looks ordinary.
     *
     * <p>So the clamp happens here, before the constructor can object, and the configured range is
     * re-imposed on the server — which is the only place that decides what a model may look like.
     *
     * <p>Channels are rounded rather than truncated, which also disposes of NaN: {@link Math#round}
     * answers zero for it, and zero is then pulled up to the configured floor. A caller that wanted to
     * distinguish "the client sent nonsense" from "the client sent the floor" has to test the input
     * before calling this, and no caller does — a slider that submits NaN is a broken client, not a
     * player with an intention.
     *
     * @param bounds          the configured slider ranges
     * @param glow            whether the model is outlined
     * @param red             submitted red channel
     * @param green           submitted green channel
     * @param blue            submitted blue channel
     * @param scale           submitted scale
     * @param physics         whether secondary motion runs
     * @param firstPersonArms whether the wearer sees the model's arms in first person
     * @return an appearance inside {@code bounds}
     */
    @Contract(value = "_, _, _, _, _, _, _, _ -> new", pure = true)
    public static @NotNull ModelAppearance clamped(
            @NotNull MenuSettings.Appearance bounds,
            boolean glow,
            float red,
            float green,
            float blue,
            float scale,
            boolean physics,
            boolean firstPersonArms
    ) {
        return new ModelAppearance(
                glow,
                bounds.clampChannel(Math.round(red)),
                bounds.clampChannel(Math.round(green)),
                bounds.clampChannel(Math.round(blue)),
                bounds.clampScale(scale),
                physics,
                firstPersonArms);
    }

    /**
     * The colour as a lower-case {@code #rrggbb} string.
     *
     * <p>Formatted here rather than by the caller so the readout and the swatch cannot disagree: both
     * read this, and this reads the same three integers the model is tinted from.
     *
     * @return the hex colour, always seven characters
     */
    @Contract(pure = true)
    public @NotNull String hex() {
        return String.format("#%02x%02x%02x", this.red, this.green, this.blue);
    }

    /**
     * The colour packed into the low three bytes of an {@code int}, red highest.
     *
     * <p>The shape every renderer in this ecosystem wants, and one place to get it wrong instead of
     * several.
     *
     * @return the packed RGB value
     */
    @Contract(pure = true)
    public int packedRgb() {
        return (this.red << 16) | (this.green << 8) | this.blue;
    }
}
