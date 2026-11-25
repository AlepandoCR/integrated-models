package pandastic.studio.models.api.rig;

/**
 * The limits a {@link RigSeat} is accepted within, and the ceiling on how many a vehicle rig may
 * declare.
 *
 * <p>Configurable for the reason {@link RigBounds} is: neither figure is a fact about seating. How many
 * riders a server is willing to put on one model is a budget question — every seated player is an
 * entity the vehicle has to carry through every position write — and how far a rider may be nudged from
 * their locator depends on the scale this server's models are authored at, which is the same reason
 * {@link pandastic.studio.models.api.locomotion.BindingBounds} exists.
 *
 * <p>Note what the offset bound is <em>not</em> for. A seat is a locator the rigger placed, so the
 * locator is the seat position and the offset only trims it — a rider who needs to be two blocks from
 * the locator is a rider whose locator is in the wrong place, and the fix belongs in Blockbench. The
 * bound is what keeps that mistake from being expressible in a rig file, where it would read as a
 * player sitting in mid-air next to the model.
 *
 * @param maxSeats            ceiling on the seats one rig may declare, so a malformed file cannot ask
 *                            the vehicle layer to carry an unbounded passenger list. At least one,
 *                            because a ceiling of zero would make every vehicle rig unrideable rather
 *                            than merely limited
 * @param maxSeatOffsetBlocks furthest a rider may be offset from their locator, per axis, in blocks.
 *                            Symmetric: an offset is a displacement, so its sign carries direction
 *                            rather than magnitude. Zero is legal and means "the locator, exactly"
 */
public record SeatBounds(int maxSeats, float maxSeatOffsetBlocks) {

    public SeatBounds {
        if (maxSeats < 1) {
            throw new IllegalArgumentException("maxSeats must be at least 1 but was " + maxSeats);
        }
        if (!Float.isFinite(maxSeatOffsetBlocks) || maxSeatOffsetBlocks < 0F) {
            throw new IllegalArgumentException(
                    "maxSeatOffsetBlocks must be finite and at least 0 but was " + maxSeatOffsetBlocks);
        }
    }
}
