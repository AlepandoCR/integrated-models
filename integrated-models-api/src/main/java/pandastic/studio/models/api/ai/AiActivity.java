package pandastic.studio.models.api.ai;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The activity slots a plan may fill.
 *
 * <h2>Why this is a closed enum and not a key a caller invents</h2>
 * Because the server will not accept an invented one. An activity is a registry object whose
 * constructor is private and whose registry is frozen once the server has bootstrapped, so there is no
 * point in the lifecycle of a plugin at which a new activity can be created. Every one of these
 * twenty-six constants exists on the server; nothing else can.
 *
 * <p>That makes the limitation worth stating plainly rather than hiding behind a string key: a plan
 * does not get to name its own states. It picks slots out of this list, and the names it picks are
 * vanilla's names for vanilla's purposes. A creature whose states are "patrolling", "guarding" and
 * "feeding" maps them onto, say, {@link #IDLE}, {@link #FIGHT} and {@link #WORK}, and the mapping lives
 * in the plan rather than in the vocabulary. An enum constant that resolves is better than a string
 * that might not, and a caller who can see the whole list can make that mapping deliberately.
 *
 * <h2>The two slots that behave differently</h2>
 * <ul>
 *   <li>A <strong>core</strong> activity runs whenever the brain ticks, alongside whatever else is
 *       active. {@link #CORE} is vanilla's name for that role and the conventional choice, but any
 *       activity can be declared core — it is a property of the plan, not of the constant.</li>
 *   <li>The <strong>default</strong> activity is what the brain falls back to when no other activity's
 *       memory conditions are met. {@link #IDLE} is the conventional choice. It may not also be core;
 *       see {@link AiPlan.Builder#defaultActivity(AiActivity)} for why.</li>
 * </ul>
 *
 * <h2>Why the vanilla name is carried rather than derived</h2>
 * {@link #vanillaName()} is the registry path the server knows this slot by, and it is written down
 * here rather than computed from {@link #name()} by lower-casing. The two agree for all twenty-six
 * today, and a rename on either side is exactly the kind of change that should be a one-line edit in a
 * visible table instead of a silent mismatch inside a transformation.
 */
public enum AiActivity {

    /** Runs alongside whatever else is active, when declared core. Vanilla's always-on slot. */
    CORE("core"),

    /** The conventional fallback: what the creature does when nothing else applies. */
    IDLE("idle"),

    /** Vanilla's occupation slot. */
    WORK("work"),

    /** Vanilla's villager-child slot. */
    PLAY("play"),

    /** Vanilla's sleep slot. */
    REST("rest"),

    /** Vanilla's gathering slot. */
    MEET("meet"),

    /** Vanilla's flee-from-danger slot. */
    PANIC("panic"),

    /** Vanilla's active-raid slot. */
    RAID("raid"),

    /** Vanilla's raid-is-coming slot. */
    PRE_RAID("pre_raid"),

    /** Vanilla's take-cover slot. */
    HIDE("hide"),

    /** Vanilla's combat slot. */
    FIGHT("fight"),

    /** Vanilla's post-victory slot. */
    CELEBRATE("celebrate"),

    /** Vanilla's piglin gold slot. */
    ADMIRE_ITEM("admire_item"),

    /** Vanilla's keep-away-from slot, distinct from {@link #PANIC} in that it is targeted. */
    AVOID("avoid"),

    /** Vanilla's being-ridden slot. */
    RIDE("ride"),

    /** Vanilla's armadillo slot. */
    PLAY_DEAD("play_dead"),

    /** Vanilla's frog and goat leap slot. */
    LONG_JUMP("long_jump"),

    /** Vanilla's goat charge slot. */
    RAM("ram"),

    /** Vanilla's frog tongue slot. */
    TONGUE("tongue"),

    /** Vanilla's in-water slot. */
    SWIM("swim"),

    /** Vanilla's egg-laying slot. */
    LAY_SPAWN("lay_spawn"),

    /** Vanilla's sniffer search slot. */
    SNIFF("sniff"),

    /** Vanilla's warden investigate slot. */
    INVESTIGATE("investigate"),

    /** Vanilla's warden roar slot. */
    ROAR("roar"),

    /** Vanilla's warden emerge slot. */
    EMERGE("emerge"),

    /** Vanilla's warden dig slot. */
    DIG("dig");

    /** Cached because {@link #values()} allocates and a lookup by name runs at plan-build time. */
    private static final AiActivity[] VALUES = values();

    /**
     * The number of slots.
     *
     * <p><strong>Deliberately not configurable</strong>, and not really a number: it is the cardinality
     * of this enum, which is in turn the cardinality of the server's frozen activity registry. Written
     * down it would be a second source of truth for something the compiler already knows.
     */
    public static final int COUNT = VALUES.length;

    private final @NotNull String vanillaName;

    AiActivity(@NotNull String vanillaName) {
        this.vanillaName = vanillaName;
    }

    /**
     * The registry path the server knows this slot by.
     *
     * <p>What the adapter below the plugin's NMS seam resolves against. Exposed because a diagnostic
     * that says {@code long_jump} is matchable against a server's own output, while one that says
     * {@code LONG_JUMP} needs the reader to know the convention.
     *
     * @return the lower-case vanilla name, without a namespace
     */
    public @NotNull String vanillaName() {
        return this.vanillaName;
    }

    /**
     * The slot whose {@link #vanillaName()} is {@code vanillaName}, or {@code null}.
     *
     * <p>For reading a plan out of a file. Returns {@code null} rather than throwing because the
     * caller — a loader — has a file name and a line number to put in the message and this method does
     * not.
     *
     * @param vanillaName the registry path to look up, case-insensitively
     * @return the matching slot, or {@code null} if there is none
     */
    public static @Nullable AiActivity byVanillaName(@NotNull String vanillaName) {
        final String wanted = vanillaName.toLowerCase(Locale.ROOT);
        for (final AiActivity activity : VALUES) {
            if (activity.vanillaName.equals(wanted)) {
                return activity;
            }
        }
        return null;
    }

    @Override
    public @NotNull String toString() {
        return this.vanillaName;
    }
}
