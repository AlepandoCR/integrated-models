package pandastic.studio.models.api.input;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Every input a player can give, as one vocabulary.
 *
 * <h2>Why one enum over two families</h2>
 * A consumer composing a combination wants to write "sneak and use" without caring that one of those
 * two arrives in a bitfield the server retains and the other on its own packet. So the vocabulary is
 * flat and the difference is carried as {@link #family()} and {@link #kind()}, which is what lets a
 * registration be <em>checked</em> — a combination naming an action key on a server whose capture
 * source cannot see actions is a condition {@link PlayerInputController} reports at bind time rather
 * than a handler that silently never fires.
 *
 * <h2>The bit layout</h2>
 * Each key owns one bit of the {@code int} that {@link InputState} stores, and the first seven are the
 * client's own: the movement packet carries forward, backward, left, right, jump, sneak and sprint as a
 * seven-bit field in that order, which is also the order the platform's accessors appear in. Those
 * seven positions are <strong>protocol structure</strong> rather than a choice, which is why they are
 * literals here in the same sense an array stride is, and why they agree bit for bit with
 * {@link pandastic.studio.models.api.vehicle.RiderIntent}'s masks — a caller that has one can pack the
 * other without a table.
 *
 * <p>The five action bits above them are this plugin's own numbering. Nothing on the wire assigns them
 * a position, because they do not travel in a bitfield at all; they are structure in the sense that
 * they are the layout of our own {@code int}, and the only thing that may rely on them is this package.
 */
public enum InputKey {

    /** The forward key. Bit 0, the client's own position. */
    FORWARD(InputFamily.MOVEMENT, InputKind.HELD, 0),

    /** The backward key. Bit 1, the client's own position. */
    BACKWARD(InputFamily.MOVEMENT, InputKind.HELD, 1),

    /** The left key. Bit 2, the client's own position. */
    LEFT(InputFamily.MOVEMENT, InputKind.HELD, 2),

    /** The right key. Bit 3, the client's own position. */
    RIGHT(InputFamily.MOVEMENT, InputKind.HELD, 3),

    /** The jump key. Bit 4, the client's own position. */
    JUMP(InputFamily.MOVEMENT, InputKind.HELD, 4),

    /** The sneak key. Bit 5, the client's own position. */
    SNEAK(InputFamily.MOVEMENT, InputKind.HELD, 5),

    /** The sprint key. Bit 6, the client's own position. */
    SPRINT(InputFamily.MOVEMENT, InputKind.HELD, 6),

    /**
     * The attack button, left click: a swing at air, at a block or at an entity.
     *
     * <p>A pulse, not a state, even though a player can hold it down — see {@link InputKind#PULSE}.
     */
    ATTACK(InputFamily.ACTION, InputKind.PULSE, 7),

    /**
     * The use button, right click: using an item, a block or an entity.
     *
     * <p>A pulse for the same reason {@link #ATTACK} is.
     */
    USE(InputFamily.ACTION, InputKind.PULSE, 8),

    /** The drop key. */
    DROP_ITEM(InputFamily.ACTION, InputKind.PULSE, 9),

    /** The swap-offhand key. */
    SWAP_HANDS(InputFamily.ACTION, InputKind.PULSE, 10),

    /**
     * The selected hotbar slot changed.
     *
     * <p>The only input that carries a value as well as a moment, and the value is on the snapshot
     * rather than here: {@link InputState#hotbarSlot()} says <em>which</em> slot, this key says
     * <em>that</em> it moved. Keeping the two apart is what lets a combination talk about the change
     * without enumerating nine keys.
     */
    SELECT_HOTBAR_SLOT(InputFamily.ACTION, InputKind.PULSE, 11);

    /**
     * How many keys there are.
     *
     * <p>Structure: an enum cardinality, used to size the per-key arrays a snapshot carries. Reading
     * {@code values().length} on every allocation would clone the array each time.
     */
    public static final int COUNT = 12;

    /** Every bit the layout defines, so {@link InputState} can mask an eighth rather than keep it. */
    public static final int ALL_BITS = (1 << COUNT) - 1;

    /** The bits of the seven {@link InputFamily#MOVEMENT} keys. */
    public static final int MOVEMENT_BITS =
            (1 << 7) - 1;

    /** The bits of the five {@link InputFamily#ACTION} keys. */
    public static final int ACTION_BITS = ALL_BITS & ~MOVEMENT_BITS;

    /**
     * Keys by bit index, so {@link #ofBit(int)} is an array read.
     *
     * <p>Built in a static initialiser from {@code values()} rather than written out, so a key added
     * with a colliding or out-of-range bit fails at class load with a message naming it instead of
     * silently sharing a bit with another key.
     */
    private static final InputKey[] BY_BIT = byBit();

    private final @NotNull InputFamily family;
    private final @NotNull InputKind kind;
    private final int bit;

    InputKey(@NotNull InputFamily family, @NotNull InputKind kind, int bit) {
        this.family = family;
        this.kind = kind;
        this.bit = bit;
    }

    private static @NotNull InputKey[] byBit() {
        final InputKey[] table = new InputKey[COUNT];
        for (final InputKey key : values()) {
            if (key.bit < 0 || key.bit >= COUNT) {
                throw new ExceptionInInitializerError(
                        key + " declares bit " + key.bit + ", which is outside 0.." + (COUNT - 1)
                                + "; COUNT and the declared bits have to agree");
            }
            if (table[key.bit] != null) {
                throw new ExceptionInInitializerError(
                        key + " and " + table[key.bit] + " both declare bit " + key.bit);
            }
            table[key.bit] = key;
        }
        return table;
    }

    /** Which family this key belongs to, and therefore what can see and suppress it. */
    public @NotNull InputFamily family() {
        return this.family;
    }

    /** Whether this key has a duration or only a moment. */
    public @NotNull InputKind kind() {
        return this.kind;
    }

    /** This key's bit index, {@code 0 .. COUNT - 1}. Also its index into a per-key array. */
    public int bit() {
        return this.bit;
    }

    /** This key's mask, {@code 1 << bit()}. */
    public int mask() {
        return 1 << this.bit;
    }

    /**
     * The key at {@code bit}, or {@code null} when the index names no key.
     *
     * @param bit the bit index
     * @return the key, or {@code null} if {@code bit} is outside the layout
     */
    @Contract(pure = true)
    public static @Nullable InputKey ofBit(int bit) {
        return bit < 0 || bit >= COUNT ? null : BY_BIT[bit];
    }

    /**
     * Every bit of the keys in {@code family}.
     *
     * @param family the family
     * @return the union of that family's masks
     */
    @Contract(pure = true)
    public static int bitsOf(@NotNull InputFamily family) {
        return family == InputFamily.MOVEMENT ? MOVEMENT_BITS : ACTION_BITS;
    }
}
