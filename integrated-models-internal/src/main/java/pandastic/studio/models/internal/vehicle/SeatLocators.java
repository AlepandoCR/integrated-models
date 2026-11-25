package pandastic.studio.models.internal.vehicle;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The locators of one live model, and the only question the seating layer asks a renderer.
 *
 * <h2>A seat is a locator. Only ever a locator</h2>
 * A locator is the empty, cube-less marker a rigger places in Blockbench: it carries no geometry, is
 * never a display, and exists to name a point on a model. A bone is the opposite — geometry with a pivot
 * that the animation pass writes — so seating a rider on one would put them wherever the clip currently
 * running happens to have left that geometry, which is a different position on every frame and a
 * different position per animation.
 *
 * <p>So this seam answers with a {@link Kind} rather than with an anchor or nothing, and the reason is
 * the mistake it exists to catch. A model in this repository may well carry a bone called {@code mount};
 * that bone is not a seat and must not become one by accident. A seat naming it resolves to
 * {@link Kind#BONE}, which is a <strong>diagnostic</strong> telling the rigger they pointed at a bone —
 * never a fallback that quietly works, because a fallback that works is how the policy would be lost.
 *
 * <h2>Nothing here is tunable and nothing here decides anything</h2>
 * A backend resolves a name and reads a position. Which seats exist is the rig's, whether a seat is
 * occupied is {@link VehicleSeats}', and what any of it is worth saying out loud is
 * {@link SeatChartReport}'s. This interface is the one place a renderer's own notion of a locator is
 * named, and it is deliberately two methods wide.
 *
 * <h2>Threading</h2>
 * {@link #resolve(String)} and {@link Anchor#readInto(SeatSample)} read the live model, so both are
 * confined to the thread that owns the entity the model stands on — in practice the body of a task the
 * vehicle's {@link VehicleHost} dispatched. Everything about a value read from anywhere else is
 * undefined; the platform will not say so.
 */
public interface SeatLocators {

    /**
     * What {@code locatorName} turned out to be on the live model.
     *
     * <p>Matched the way a rig matches a bone: on the <strong>bare</strong> name, case-insensitively,
     * with the renderer's behavioural tag prefixes already stripped. A rig stores its seat's locator name
     * through {@code Rig.normalise}, so both sides of the comparison have had the same thing done to them
     * and a rigger adding a tag in Blockbench does not break a seat.
     *
     * @param locatorName the normalised locator name the rig's seat names
     * @return what was found, with an anchor only when it was genuinely a locator
     */
    @NotNull Resolution resolve(@NotNull String locatorName);

    /**
     * A model with no locators at all.
     *
     * <p>The production answer for a vehicle that carries no rig, and the normal one today: no model in
     * this repository contains a locator element yet, so every seat a rig declares resolves to
     * {@link Kind#UNRESOLVED} until a rigger adds one. That is a reported outcome rather than a failure —
     * see {@link SeatChartReport} — because a format built for locators riggers will add later has to be
     * buildable before they add them.
     */
    @NotNull SeatLocators NONE = locatorName -> Resolution.UNRESOLVED;

    /** What a seat's name turned out to name. */
    enum Kind {

        /** A locator: the only thing a rider may sit on. */
        LOCATOR,

        /**
         * An ordinary bone, which is the rigger's mistake this layer exists to report.
         *
         * <p>Not an error and not a fallback. The vehicle binds with the seats it has and this one is
         * named, loudly, in the seat chart.
         */
        BONE,

        /** Nothing of that name on the model: a typo, a renamed element, or a locator not added yet. */
        UNRESOLVED;

        /** Whether a rider may be seated here. True for exactly one of the three. */
        public boolean isUsable() {
            return this == LOCATOR;
        }
    }

    /**
     * One seat's name, resolved.
     *
     * @param kind   what the name turned out to name
     * @param anchor where the locator is, present if and only if {@code kind} is {@link Kind#LOCATOR}
     */
    record Resolution(@NotNull Kind kind, @Nullable Anchor anchor) {

        /** The answer for a name the model does not carry. */
        public static final @NotNull Resolution UNRESOLVED = new Resolution(Kind.UNRESOLVED, null);

        /** The answer for a name that turned out to be an ordinary bone. */
        public static final @NotNull Resolution BONE = new Resolution(Kind.BONE, null);

        public Resolution {
            if ((kind == Kind.LOCATOR) != (anchor != null)) {
                // Stated as an invariant rather than left to each backend, because the seating layer
                // reads the anchor on the strength of the kind and a mismatch would be either a seat
                // nobody can sit on with an anchor nobody reads, or a null dereference per step.
                throw new IllegalArgumentException(
                        "A " + kind + " resolution " + (anchor == null ? "needs" : "must not carry")
                                + " an anchor: an anchor exists exactly when the name was a locator");
            }
        }

        /** The answer for a genuine locator, which is the only one carrying a position. */
        public static @NotNull Resolution locator(@NotNull Anchor anchor) {
            return new Resolution(Kind.LOCATOR, anchor);
        }

        /** The locator's anchor, for a caller that has already checked the kind. */
        public @NotNull Anchor requireAnchor() {
            final Anchor found = this.anchor;
            if (found == null) {
                throw new IllegalStateException("A " + this.kind + " resolution has no anchor");
            }
            return found;
        }
    }

    /**
     * Where one locator is, right now.
     *
     * <p>Bound to one locator of one live model, resolved once, and read once per control step for as
     * long as somebody is sitting on it. Held rather than looked up again per read because a lookup is a
     * scan of the model's bone tree and the position is wanted every tick.
     */
    @FunctionalInterface
    interface Anchor {

        /**
         * Fills {@code sample} with the locator's current offset from the model's origin, in world axes.
         *
         * <p>Owning thread only. The renderer's own convention is resolved on the backend's side of this
         * seam: what arrives here is blocks along the world's axes, with the model's rotation already
         * applied, measured from where the model stands. See {@link SeatSample} for why it is an offset
         * rather than an absolute position — a renderer caches the position of what it is drawing, and an
         * absolute answer would leave every seat a tick behind the vehicle it belongs to.
         *
         * @param sample a buffer the caller owns and reuses; untouched if this returns {@code false}
         * @return {@code false} if the model is gone, in which case the seat holding this anchor can no
         *         longer be followed and its rider is put down
         */
        boolean readInto(@NotNull SeatSample sample);
    }
}
