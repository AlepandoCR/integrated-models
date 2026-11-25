package pandastic.studio.models.api.rig;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * A locomotion state a model's own animation clip can be bound to.
 *
 * <p>Three states, and they are not a design choice: they are the states the gait layer already
 * distinguishes — standing (no amplitude), walking, and running, which
 * {@link pandastic.studio.models.api.locomotion.GaitProfile} expresses as its walk and sprint
 * amplitudes. A fourth role would be a state nothing in the pipeline can tell it is in, so the clip
 * bound to it could never be chosen.
 *
 * <h2>Why a rig names clips at all</h2>
 * Because most models arrive with animations already in them, and this plugin's pose writer is a
 * <em>second</em> writer to the same display. On a bone a shipped clip keyframes, the renderer sends
 * {@code bind ∘ animation} and we send {@code bind ∘ physics} — two writers, one display, last packet
 * of the frame wins, and the visible result is a bone that flickers between two poses rather than
 * either of them. Naming the clips is the first half of fixing that: the layer that composes the two
 * has to know which clip is playing before it can compose against it rather than against the static
 * bind pose. {@link RigBone#animationOwned()} is the other half.
 *
 * <p>Deliberately not configurable: this enum is the set of states the code can be in, and the clip
 * <em>names</em> — the part that does vary — are per-model data in the rig file, because they are
 * whatever one rigger typed in Blockbench.
 */
public enum ClipRole {

    /** Standing still. */
    IDLE,

    /** Moving at or below walking speed. */
    WALK,

    /** Moving at sprinting speed. */
    RUN;

    /** How many roles there are, for a caller sizing an array by role. */
    public static final int COUNT = values().length;

    /**
     * Parses the spelling a rig file uses.
     *
     * <p>Lenient about case and about {@code -} and {@code _}, like every other key in the rig format.
     * Note the asymmetry with the clip <em>name</em> on the other side of the mapping, which is
     * preserved exactly as authored: the role is ours to interpret, the clip name has to match a clip in
     * a model this plugin does not own.
     *
     * @param value the authored spelling
     * @throws IllegalArgumentException if the value names no role
     */
    public static @NotNull ClipRole parse(@NotNull String value) {
        final String canonical = value.trim().toLowerCase(Locale.ROOT)
                .replace("-", "").replace("_", "");
        for (final ClipRole role : values()) {
            if (role.key().equals(canonical)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unknown clip role '" + value + "'; expected " + names());
    }

    /** Every role's authored spelling, for a message that lists the options. */
    public static @NotNull String names() {
        final StringBuilder names = new StringBuilder();
        for (final ClipRole role : values()) {
            if (!names.isEmpty()) {
                names.append(", ");
            }
            names.append(role.key());
        }
        return names.toString();
    }

    /** This role's key in a rig file, which is its name in lower case. */
    public @NotNull String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
