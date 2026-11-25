package pandastic.studio.models.api.rig;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * One place a rider sits on a {@link RigKind#VEHICLE} rig: the <strong>locator</strong> they sit on,
 * whether they drive from it, and how far they are nudged off it.
 *
 * <h2>A seat is a locator. Only ever a locator</h2>
 * {@link #locator()} names a locator element of the model — the empty, cube-less marker a rigger places
 * in Blockbench — and nothing else. It is deliberately not "an attachment point" and it is deliberately
 * not "a bone or a locator": a bone is geometry with a pivot that the animation pass writes, and seating
 * a player on one means their position is whatever the current clip happens to have done to that
 * geometry. A locator carries no geometry, is never a display, and exists for exactly this. So a model
 * that carries a bone called {@code mount} does not thereby have a seat, and naming it here is the
 * mistake this type is shaped to make reportable rather than tempting: the binding layer resolves a seat
 * against the live model, and a name that resolves to a bone is a <em>diagnostic</em> telling the rigger
 * they pointed at a bone, never a fallback that quietly works.
 *
 * <p>Nothing here resolves anything, exactly as a {@link RigBone} resolves nothing: the model lives in
 * the renderer's asset folder and may not even be loaded when a rig file is read. What this type
 * guarantees is that the seat is internally coherent before anybody tries.
 *
 * <h2>The locator name is matched the way a bone name is</h2>
 * Lower-cased, with renderer tag prefixes stripped, through {@link Rig#normalise(String)} — because a
 * locator reaches the renderer's bone tree with the same kind of identifier a bone does, tags and all,
 * and a seat keyed on the raw identifier breaks the moment a rigger adds a tag.
 *
 * <h2>The offset trims the locator; it does not replace it</h2>
 * The rigger placed the locator where the rider belongs, so {@link #offsetY()} is for the half-block of
 * sitting height a saddle needs and not for moving a rider somewhere else.
 * {@link SeatBounds#maxSeatOffsetBlocks()} is what keeps the second use out of the format; past it, the
 * honest fix is to move the locator.
 *
 * @param name     this seat's own id, for a consumer asking for a seat by name and for diagnostics.
 *                 Stored lower-cased and therefore matched case-insensitively, because unlike a locator
 *                 name it is ours to interpret
 * @param locator  the locator element of the model the rider sits on, normalised by
 *                 {@link Rig#normalise(String)}
 * @param driver   whether a rider in this seat controls the vehicle. At most one seat of a rig may be
 *                 the driver's, which {@link Rig.Builder} enforces because it is a statement about the
 *                 set rather than about one seat
 * @param offsetX  rider displacement from the locator along the model's x axis, in blocks
 * @param offsetY  rider displacement along y, in blocks — the one that is usually non-zero
 * @param offsetZ  rider displacement along z, in blocks
 */
public record RigSeat(
        @NotNull String name,
        @NotNull String locator,
        boolean driver,
        float offsetX,
        float offsetY,
        float offsetZ
) {

    public RigSeat {
        if (name.isBlank()) {
            throw new IllegalArgumentException("A seat needs a name");
        }
        if (locator.isBlank()) {
            throw new IllegalArgumentException(
                    "Seat '" + name + "' needs the name of the locator the rider sits on");
        }
        requireFinite(offsetX, "offsetX", name);
        requireFinite(offsetY, "offsetY", name);
        requireFinite(offsetZ, "offsetZ", name);

        name = name.trim().toLowerCase(Locale.ROOT);
        locator = Rig.normalise(locator.trim());
    }

    /** A seat exactly on its locator. */
    @Contract(value = "_, _, _ -> new", pure = true)
    public static @NotNull RigSeat of(
            @NotNull String name,
            @NotNull String locator,
            boolean driver
    ) {
        return new RigSeat(name, locator, driver, 0F, 0F, 0F);
    }

    /**
     * Checks this seat's offsets against the operator's range.
     *
     * <p>Separate from the constructor for the reason {@link PhysicsProfile#requireWithin(ProfileBounds)}
     * is: a seat can be constructed by anybody, and only whoever holds the configuration knows what this
     * server permits. {@link Rig.Builder} calls it for every seat it accepts, so the "a programmatic
     * caller passing a bad value gets an exception" guarantee holds end to end.
     *
     * @throws IllegalArgumentException if any offset is outside
     *                                 {@code ±}{@link SeatBounds#maxSeatOffsetBlocks()}
     */
    public void requireWithin(@NotNull SeatBounds bounds) {
        requireInRange(this.offsetX, "offsetX", bounds);
        requireInRange(this.offsetY, "offsetY", bounds);
        requireInRange(this.offsetZ, "offsetZ", bounds);
    }

    /** Whether the rider sits exactly on the locator. */
    public boolean isOnLocator() {
        return this.offsetX == 0F && this.offsetY == 0F && this.offsetZ == 0F;
    }

    private void requireInRange(float offset, @NotNull String axis, @NotNull SeatBounds bounds) {
        final float limit = bounds.maxSeatOffsetBlocks();
        if (offset < -limit || offset > limit) {
            throw new IllegalArgumentException(
                    "Seat '" + this.name + "' has " + axis + " " + offset + ", outside the permitted"
                            + " +/-" + limit + " blocks. An offset trims a locator; move the locator"
                            + " instead of reaching from it.");
        }
    }

    private static void requireFinite(float offset, @NotNull String axis, @NotNull String seat) {
        if (!Float.isFinite(offset)) {
            throw new IllegalArgumentException(
                    "Seat '" + seat + "' has a non-finite " + axis + " (" + offset + "), which names no"
                            + " position at all");
        }
    }
}
