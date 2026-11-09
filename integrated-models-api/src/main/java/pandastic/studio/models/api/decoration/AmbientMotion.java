package pandastic.studio.models.api.decoration;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * How exposed one decoration is to the ambient field — the per-decoration half of "a banner moves in the
 * wind".
 *
 * <h2>Why a strength and not the wind itself</h2>
 * Wind is a property of the world, not of a banner: its speed, its direction, how it gusts and how far
 * apart two gusts are belong in configuration, where an operator can tune the whole server's weather
 * once. What belongs to an individual decoration is only <em>how much of that field reaches it</em> — a
 * banner on a tower catches all of it, one in a stairwell catches none. So this carries one number, and
 * every coefficient it scales lives in {@code simulation.yml}.
 *
 * <p>That split is what keeps three hundred decorations describable without three hundred wind
 * configurations, and it is also what makes {@link #NONE} meaningful: a decoration at zero strength is
 * not "a decoration with the wind turned down", it is one the ambient driver never publishes anything
 * for. See {@link Decoration#setAmbientMotion}.
 *
 * <h2>Still by default, deliberately</h2>
 * Motion costs packets, every second, for as long as it lasts: a gusting decoration can never reach the
 * at-rest state that makes a decoration free. Three hundred decorations breathing by accident is the
 * exact failure this whole layer exists to avoid, so {@link DecorationBinder#bind(
 * pandastic.studio.models.api.rig.Rig, org.bukkit.Location)} places a decoration at {@link #NONE} and
 * ambient motion is something a plugin asks for per decoration.
 *
 * @param strength how much of the ambient field this decoration feels. Zero is sheltered, one is fully
 *                 exposed, and above one is legal — a flag on a mast reasonably catches more wind than
 *                 the calibration's nominal exposure, and clamping it here would mean inventing a
 *                 ceiling that no file states
 */
public record AmbientMotion(float strength) {

    /**
     * A decoration the ambient field does not reach.
     *
     * <p>Not a tunable: zero is the additive identity of a velocity, so this is "no wind" rather than
     * "a little wind somebody picked". It is also the only value for which a decoration is provably
     * silent once settled.
     */
    public static final @NotNull AmbientMotion NONE = new AmbientMotion(0F);

    /**
     * Full exposure to the configured field.
     *
     * <p>The neutral multiplier, so the field an operator calibrated is the field this decoration feels
     * — which is what makes the configuration's defaults describe something observable.
     */
    public static final @NotNull AmbientMotion EXPOSED = new AmbientMotion(1F);

    public AmbientMotion {
        if (!Float.isFinite(strength) || strength < 0F) {
            throw new IllegalArgumentException(
                    "An ambient strength must be finite and not negative but was " + strength
                            + "; use AmbientMotion.NONE for a sheltered decoration");
        }
    }

    /**
     * An exposure of {@code strength}.
     *
     * @param strength how much of the ambient field reaches this decoration
     * @return the exposure, or {@link #NONE} for zero so that a sheltered decoration compares equal to
     *         the constant the rest of the layer tests against
     */
    @Contract(pure = true)
    public static @NotNull AmbientMotion of(float strength) {
        return strength == 0F ? NONE : new AmbientMotion(strength);
    }

    /**
     * Whether the ambient field reaches this decoration at all.
     *
     * <p>The cheap test the driver loop makes per decoration per pass, and the condition under which a
     * decoration can settle and go silent.
     *
     * @return {@code true} when nothing ambient drives this decoration
     */
    public boolean isStill() {
        return this.strength == 0F;
    }
}
