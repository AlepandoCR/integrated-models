package pandastic.studio.models.api.ai;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What a behaviour has decided the creature should be doing, as one immutable value.
 *
 * <h2>Why a behaviour publishes instead of acting</h2>
 * A behaviour body runs inside the creature's tick, on the region thread that owns it. The simulation
 * that poses the model runs off the tick, on its own thread, at its own rate. Those two must not write
 * the same state: the pose pipeline's whole region-safety argument is that the only thing crossing
 * between the region thread and the simulation thread is an immutable value stored into a volatile
 * field, so that a reader which sees the new reference sees every component of it and sees them from
 * the same decision.
 *
 * <p>An intent is that value for the AI layer, and it is the same shape the movement sampler already
 * uses for position and yaw. A behaviour that reached into the simulation directly would be the one
 * place in this plugin where a region thread writes state another thread is integrating, and the
 * failure would be a bone wound up over seconds rather than anything that looks like a race.
 *
 * <h2>Rotation-only is not a rule here</h2>
 * It is a rule for a <em>pose</em>, which may never move a bone off its pivot. An intent is the layer
 * above that: it says where the creature should go and what it should look at, and something else turns
 * that into entity movement. Nothing in this record touches a bone.
 *
 * <h2>Nothing here is tunable</h2>
 * {@link #NONE} is an identity value, and {@link #moveSpeed()} is only required to be finite and
 * non-negative. The ceiling on how fast a creature may be asked to move belongs to whatever applies
 * the intent, because it is a property of the creature's attributes rather than of the decision — and
 * clamping here would silently rewrite what a behaviour asked for.
 *
 * @param movePoint where the creature should walk to, or {@code null} for "stop deciding where to go".
 *                  Distinct from publishing the creature's current position, which means "walk to where
 *                  you already are" and keeps the navigation busy
 * @param moveSpeed a multiplier on the creature's own movement speed, {@code 0} when there is no move
 *                  target. Finite and non-negative; {@code 1} is the creature's normal speed
 * @param lookPoint where the creature should look, or {@code null} to leave its head alone. Independent
 *                  of {@link #movePoint()} on purpose: a creature backing away from something it is
 *                  watching has a move target and a look target that point in opposite directions
 */
public record AiIntent(
        @Nullable AiPoint movePoint,
        double moveSpeed,
        @Nullable AiPoint lookPoint
) {

    /**
     * The creature has decided nothing.
     *
     * <p>An identity value, not a default: it is what a controller reports before any behaviour has run
     * and what a behaviour publishes to hand control back. Compared by identity nowhere — a fresh
     * all-null intent is equal to this one and means the same thing.
     */
    public static final AiIntent NONE = new AiIntent(null, 0.0D, null);

    /**
     * Refuses a speed that is not a number, negative, or attached to no destination.
     *
     * <p>The last of those is the one worth a check. A speed with no move target is a value nothing
     * will ever read, which means a behaviour that set the speed and forgot the target looks exactly
     * like one that deliberately asked for nothing — and the symptom is a creature that stands still
     * with no diagnostic anywhere.
     */
    public AiIntent {
        if (!Double.isFinite(moveSpeed) || moveSpeed < 0.0D) {
            throw new IllegalArgumentException(
                    "moveSpeed must be finite and non-negative but was " + moveSpeed);
        }
        if (movePoint == null && moveSpeed != 0.0D) {
            throw new IllegalArgumentException("moveSpeed is " + moveSpeed
                    + " but there is no movePoint, so nothing will read it. Publish a movePoint, or"
                    + " publish AiIntent.NONE to hand movement back");
        }
    }

    /**
     * An intent that only asks the creature to walk somewhere.
     *
     * @param destination where to walk to
     * @param speed       a multiplier on the creature's movement speed
     * @return the intent
     */
    @Contract(value = "_, _ -> new", pure = true)
    public static @NotNull AiIntent moveTo(@NotNull AiPoint destination, double speed) {
        return new AiIntent(destination, speed, null);
    }

    /**
     * An intent that only asks the creature to look somewhere.
     *
     * @param target where to look
     * @return the intent
     */
    @Contract(value = "_ -> new", pure = true)
    public static @NotNull AiIntent lookAt(@NotNull AiPoint target) {
        return new AiIntent(null, 0.0D, target);
    }

    /** Whether this intent asks the creature to go anywhere. */
    public boolean hasMoveTarget() {
        return this.movePoint != null;
    }

    /** Whether this intent asks the creature to look anywhere. */
    public boolean hasLookTarget() {
        return this.lookPoint != null;
    }

    /** Whether this intent asks for nothing at all. */
    public boolean isIdle() {
        return this.movePoint == null && this.lookPoint == null;
    }

    /**
     * This intent with {@code target} as its look target.
     *
     * <p>So the two halves can be decided by two behaviours in one activity without either of them
     * having to know what the other asked for.
     *
     * @param target where to look, or {@code null} to drop the look target
     * @return a copy with the look target replaced
     */
    @Contract(value = "_ -> new", pure = true)
    public @NotNull AiIntent withLookPoint(@Nullable AiPoint target) {
        return new AiIntent(this.movePoint, this.moveSpeed, target);
    }
}
