package pandastic.studio.models.api.vehicle;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * What the rider is asking for, as seven held keys.
 *
 * <h2>Held keys, not key presses</h2>
 * The client sends its movement intent once per client tick and the server retains the latest value, so
 * what a control scheme needs is the <em>state</em> of each key rather than the moment it changed.
 * Reading the retained value is the only way to see "W is down"; an edge-triggered notification can only
 * say "W went down", and reconstructing held state from edges means missing one and driving forever.
 *
 * <p>Nothing in this type is platform-shaped on purpose. The platform exposes the same seven flags
 * behind an interface whose implementation belongs to the server, and a steering scheme written against
 * that interface cannot be exercised without one. Translating once, at the edge, leaves every decision
 * above it a function of this record.
 *
 * <h2>The bitfield</h2>
 * The client's input packet carries these seven flags as a bitfield, and {@link #ofBits(int)} /
 * {@link #toBits()} reproduce that encoding. The bit order is the order the packet declares them, which
 * is also the order the platform's own accessors appear in — so a caller that has the platform value can
 * pack it without consulting a table, and a test can enumerate all 128 combinations as integers.
 *
 * <p>This plugin never reads the wire, so the encoding is not load-bearing against the protocol; it is
 * load-bearing against <em>itself</em>, because the round trip is what a test asserts.
 *
 * @param forward  the forward key is down
 * @param backward the backward key is down
 * @param left     the left key is down
 * @param right    the right key is down
 * @param jump     the jump key is down
 * @param sneak    the sneak key is down
 * @param sprint   the sprint key is down
 */
public record RiderIntent(
        boolean forward,
        boolean backward,
        boolean left,
        boolean right,
        boolean jump,
        boolean sneak,
        boolean sprint
) {

    /** Nothing held. What a vehicle with no rider is steered by. */
    public static final @NotNull RiderIntent NONE =
            new RiderIntent(false, false, false, false, false, false, false);

    /**
     * Bit of the forward key.
     *
     * <p><strong>Protocol structure.</strong> The seven masks below are the positions the client's input
     * packet assigns, not a choice this plugin made, so they are literals in the same sense an array
     * stride is.
     */
    public static final int FORWARD_BIT = 1;

    /** Bit of the backward key. See {@link #FORWARD_BIT}. */
    public static final int BACKWARD_BIT = 1 << 1;

    /** Bit of the left key. See {@link #FORWARD_BIT}. */
    public static final int LEFT_BIT = 1 << 2;

    /** Bit of the right key. See {@link #FORWARD_BIT}. */
    public static final int RIGHT_BIT = 1 << 3;

    /** Bit of the jump key. See {@link #FORWARD_BIT}. */
    public static final int JUMP_BIT = 1 << 4;

    /** Bit of the sneak key. See {@link #FORWARD_BIT}. */
    public static final int SNEAK_BIT = 1 << 5;

    /** Bit of the sprint key. See {@link #FORWARD_BIT}. */
    public static final int SPRINT_BIT = 1 << 6;

    /**
     * Every bit the encoding defines.
     *
     * <p>Structure rather than a tunable: it is the union of the seven masks above, and it exists so
     * {@link #ofBits(int)} can say which bits it ignored instead of silently accepting an eighth.
     */
    public static final int ALL_BITS =
            FORWARD_BIT | BACKWARD_BIT | LEFT_BIT | RIGHT_BIT | JUMP_BIT | SNEAK_BIT | SPRINT_BIT;

    /**
     * The intent {@code bits} encodes.
     *
     * <p>Bits outside {@link #ALL_BITS} are ignored rather than refused. The packet's field is seven bits
     * wide and the server masks it, so a higher bit cannot arrive from a client; one arriving from a
     * caller is a packing mistake on its side, and refusing it would turn a cosmetic error into a vehicle
     * that stops responding.
     *
     * @param bits the packed flags
     * @return the intent
     */
    @Contract(value = "_ -> new", pure = true)
    public static @NotNull RiderIntent ofBits(int bits) {
        return new RiderIntent(
                (bits & FORWARD_BIT) != 0,
                (bits & BACKWARD_BIT) != 0,
                (bits & LEFT_BIT) != 0,
                (bits & RIGHT_BIT) != 0,
                (bits & JUMP_BIT) != 0,
                (bits & SNEAK_BIT) != 0,
                (bits & SPRINT_BIT) != 0);
    }

    /**
     * This intent as the bitfield {@link #ofBits(int)} reads.
     *
     * @return the packed flags, within {@link #ALL_BITS}
     */
    @Contract(pure = true)
    public int toBits() {
        int bits = 0;
        if (this.forward) {
            bits |= FORWARD_BIT;
        }
        if (this.backward) {
            bits |= BACKWARD_BIT;
        }
        if (this.left) {
            bits |= LEFT_BIT;
        }
        if (this.right) {
            bits |= RIGHT_BIT;
        }
        if (this.jump) {
            bits |= JUMP_BIT;
        }
        if (this.sneak) {
            bits |= SNEAK_BIT;
        }
        if (this.sprint) {
            bits |= SPRINT_BIT;
        }
        return bits;
    }

    /**
     * Whether any key at all is held.
     *
     * <p>Not the same question as "is the vehicle at rest": a vehicle coasting with nothing held is still
     * moving, which is why {@link VehicleState} answers that one and this answers only whether there is
     * an input to act on.
     *
     * @return whether at least one key is down
     */
    @Contract(pure = true)
    public boolean isIdle() {
        return toBits() == 0;
    }
}
