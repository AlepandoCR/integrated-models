package pandastic.studio.models.api.input;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * What a player is asking for right now, as one immutable value.
 *
 * <h2>Why a bitfield and not twelve booleans</h2>
 * Because this value is published on every input change for every player and read once per control
 * step by whatever is being driven, so it wants to be one word. It is also what makes
 * {@link #equals(Object)} exact and cheap, which matters more than it looks: the capture layer
 * publishes a new snapshot only when the state actually moved, and "actually moved" is this record's
 * equality. A representation with twelve fields would answer the same question with twelve
 * comparisons and would make a test enumerating combinations an exercise in constructor arguments.
 *
 * <h2>Canonical by construction</h2>
 * Bits outside {@link InputKey#ALL_BITS} are masked off rather than refused. Nothing on the wire can
 * set them — the movement field is seven bits wide and the server masks it — so one arriving here is a
 * packing mistake in a caller, and masking keeps two states that mean the same thing equal. Refusing
 * would turn a cosmetic error into a controller that stops answering.
 *
 * <p>The hotbar slot is the one component that is not a flag, and it is validated rather than masked:
 * an out-of-range slot is not a bit someone forgot to clear, it is a number that means nothing.
 *
 * @param bits       the packed flags, already masked to {@link InputKey#ALL_BITS}
 * @param hotbarSlot the selected hotbar slot, {@code 0 .. 8}, or {@link #UNKNOWN_HOTBAR_SLOT} before
 *                   anything has been observed for this player
 */
public record InputState(int bits, int hotbarSlot) {

    /**
     * The slot of a player whose selection has never been observed.
     *
     * <p>Structure: a {@code -1} sentinel, the same one {@code RigBone.NO_PARENT} is. It exists because
     * "no input has arrived yet" is a real state — a controller answers before the first packet — and
     * answering {@code 0} would be a lie that reads as "slot one is selected".
     */
    public static final int UNKNOWN_HOTBAR_SLOT = -1;

    /**
     * How many hotbar slots there are.
     *
     * <p>A protocol fact: the client's hotbar is nine slots and the slot-change packet carries an index
     * into exactly those. Configurable it would stop being a bound and become a way to make a valid
     * packet look invalid.
     */
    public static final int HOTBAR_SLOTS = 9;

    /** Nothing held, nothing known. What a controller answers for a player it has never seen. */
    public static final @NotNull InputState NONE = new InputState(0, UNKNOWN_HOTBAR_SLOT);

    public InputState {
        bits &= InputKey.ALL_BITS;
        if (hotbarSlot != UNKNOWN_HOTBAR_SLOT && (hotbarSlot < 0 || hotbarSlot >= HOTBAR_SLOTS)) {
            throw new IllegalArgumentException("hotbarSlot must be 0.." + (HOTBAR_SLOTS - 1) + " or "
                    + UNKNOWN_HOTBAR_SLOT + " but was " + hotbarSlot);
        }
    }

    /**
     * The state {@code bits} encodes, with no slot known.
     *
     * @param bits the packed flags
     * @return the state
     */
    @Contract(value = "_ -> new", pure = true)
    public static @NotNull InputState ofBits(int bits) {
        return new InputState(bits, UNKNOWN_HOTBAR_SLOT);
    }

    /**
     * The state with exactly {@code keys} set and no slot known.
     *
     * <p>The readable form, and the one tests use: {@code of(SPRINT, JUMP)} says what it means where
     * {@code ofBits(0b1010000)} does not.
     *
     * @param keys the keys to set
     * @return the state
     */
    @Contract(value = "_ -> new", pure = true)
    public static @NotNull InputState of(@NotNull InputKey @NotNull ... keys) {
        Objects.requireNonNull(keys, "keys");
        int bits = 0;
        for (final InputKey key : keys) {
            bits |= Objects.requireNonNull(key, "key").mask();
        }
        return new InputState(bits, UNKNOWN_HOTBAR_SLOT);
    }

    /**
     * Whether {@code key} is set.
     *
     * <p>For a {@link InputKind#PULSE} key this is the same question as
     * {@link InputTrace#justPressed(InputKey)}, because a pulse is set in exactly one published state.
     *
     * @param key the key to test
     * @return whether its bit is set
     */
    @Contract(pure = true)
    public boolean isDown(@NotNull InputKey key) {
        return (this.bits & Objects.requireNonNull(key, "key").mask()) != 0;
    }

    /**
     * This state with {@code key} set or cleared.
     *
     * @param key  the key to change
     * @param down whether it should be set
     * @return the resulting state, or this one when nothing changed
     */
    @Contract(pure = true)
    public @NotNull InputState with(@NotNull InputKey key, boolean down) {
        final int mask = Objects.requireNonNull(key, "key").mask();
        final int updated = down ? this.bits | mask : this.bits & ~mask;
        return updated == this.bits ? this : new InputState(updated, this.hotbarSlot);
    }

    /**
     * This state with a different selected slot.
     *
     * @param slot the slot, {@code 0 .. 8} or {@link #UNKNOWN_HOTBAR_SLOT}
     * @return the resulting state, or this one when nothing changed
     */
    @Contract(pure = true)
    public @NotNull InputState withHotbarSlot(int slot) {
        return slot == this.hotbarSlot ? this : new InputState(this.bits, slot);
    }

    /**
     * This state with every {@link InputKind#PULSE} bit cleared.
     *
     * <p>What the capture layer applies between two publications: a pulse is true for one snapshot by
     * definition, so carrying it into the next would make a single click look like a held button.
     *
     * @return the resulting state, or this one when no pulse was set
     */
    @Contract(pure = true)
    public @NotNull InputState withoutPulses() {
        final int held = this.bits & ~InputKey.ACTION_BITS;
        return held == this.bits ? this : new InputState(held, this.hotbarSlot);
    }

    /** The seven movement bits alone, which is what a vehicle's steering reads. */
    @Contract(pure = true)
    public int movementBits() {
        return this.bits & InputKey.MOVEMENT_BITS;
    }

    /** The five action bits alone. */
    @Contract(pure = true)
    public int actionBits() {
        return this.bits & InputKey.ACTION_BITS;
    }

    /**
     * Whether any key of {@code family} is set.
     *
     * @param family the family to test
     * @return whether at least one of its keys is down
     */
    @Contract(pure = true)
    public boolean hasAny(@NotNull InputFamily family) {
        return (this.bits & InputKey.bitsOf(Objects.requireNonNull(family, "family"))) != 0;
    }

    /**
     * Whether nothing at all is set.
     *
     * <p>Not the same question as "the thing being driven is at rest": a vehicle coasting with nothing
     * held is still moving. This answers only whether there is an input to act on.
     *
     * @return whether no bit is set
     */
    @Contract(pure = true)
    public boolean isIdle() {
        return this.bits == 0;
    }

    /**
     * The keys that are set.
     *
     * <p>Allocates, so it is for diagnostics, messages and tests rather than for a control step — a
     * step asks {@link #isDown(InputKey)} or reads {@link #movementBits()}.
     *
     * @return an unmodifiable set, empty when nothing is held
     */
    @Contract(pure = true)
    public @NotNull Set<InputKey> keys() {
        final EnumSet<InputKey> set = EnumSet.noneOf(InputKey.class);
        for (final InputKey key : InputKey.values()) {
            if (isDown(key)) {
                set.add(key);
            }
        }
        return Collections.unmodifiableSet(set);
    }

    /**
     * The keys that are set, joined with {@code +}, or {@code "idle"}.
     *
     * <p>Separate from {@link #toString()} because this is what a diagnostic puts in a sentence, while
     * the record's own form is what a test failure should show.
     *
     * @return a readable description
     */
    @Contract(pure = true)
    public @NotNull String describe() {
        if (this.bits == 0) {
            return "idle";
        }
        final StringBuilder text = new StringBuilder();
        for (final InputKey key : InputKey.values()) {
            if (!isDown(key)) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append('+');
            }
            text.append(key.name().toLowerCase(Locale.ROOT));
        }
        return text.toString();
    }
}
