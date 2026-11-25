package pandastic.studio.models.api.rig;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Which of a model's <em>own</em> animation clips plays in each locomotion state.
 *
 * <p>Immutable, validated, and possibly empty — {@link #NONE} is the normal answer for a rig whose
 * model ships no animations, and for every rig written before this section existed.
 *
 * <h2>Clip names are kept exactly as authored</h2>
 * Deliberately, and in contrast to every other name in this package. A bone name is lower-cased and a
 * seat name is lower-cased because both are matched by us; a clip name is handed <em>to the renderer</em>
 * to look up a clip in a {@code .bbmodel} a rigger wrote, and the names in those files are whatever was
 * typed into Blockbench — {@code standUp} and {@code lookaround} both ship in this repository's own
 * models. Normalising would turn a working rig into a rig that names a clip the model does not have,
 * which the renderer reports as "no such animation" rather than as a rig problem.
 *
 * <p>So: the <em>role</em> is parsed leniently ({@link ClipRole#parse(String)}), the clip name is
 * trimmed and otherwise untouched.
 *
 * <h2>What this type does not do</h2>
 * It does not play anything, it does not check that the model has such a clip — it has no model to ask,
 * for the same reason a {@link RigBone} cannot check its own name — and it does not decide when a state
 * changes. It is the data the composition layer reads.
 */
public final class RigClips {

    /** A rig that names no clips: the state of every rig that does not declare the section. */
    public static final @NotNull RigClips NONE = new RigClips(Map.of());

    private final @NotNull Map<ClipRole, String> byRole;

    private RigClips(@NotNull Map<ClipRole, String> byRole) {
        this.byRole = byRole;
    }

    /**
     * The clips named by {@code clips}, copied and validated.
     *
     * @param clips clip name per role; a role the map omits is simply not bound
     * @throws IllegalArgumentException if any name is blank, which names no clip at all
     */
    @Contract(value = "_ -> new", pure = true)
    public static @NotNull RigClips of(@NotNull Map<ClipRole, String> clips) {
        if (clips.isEmpty()) {
            return NONE;
        }
        final Map<ClipRole, String> copy = new EnumMap<>(ClipRole.class);
        for (final Map.Entry<ClipRole, String> entry : clips.entrySet()) {
            final String clip = entry.getValue().trim();
            if (clip.isEmpty()) {
                throw new IllegalArgumentException(
                        "The " + entry.getKey().key() + " clip is blank, which names no clip. Leave the"
                                + " role out instead.");
            }
            copy.put(entry.getKey(), clip);
        }
        return new RigClips(Map.copyOf(copy));
    }

    /** A rig that names one clip. */
    @Contract(value = "_, _ -> new", pure = true)
    public static @NotNull RigClips of(@NotNull ClipRole role, @NotNull String clip) {
        return of(Map.of(role, clip));
    }

    /** The clip bound to {@code role}, as the rigger spelled it, or {@code null} if none is. */
    public @Nullable String clip(@NotNull ClipRole role) {
        return this.byRole.get(role);
    }

    /** The clip bound to {@code role}, for a caller that would rather not test for null. */
    public @NotNull Optional<String> findClip(@NotNull ClipRole role) {
        return Optional.ofNullable(this.byRole.get(role));
    }

    /** Whether {@code role} has a clip. */
    public boolean declares(@NotNull ClipRole role) {
        return this.byRole.containsKey(role);
    }

    /** Whether no role has a clip, i.e. whether this is {@link #NONE} in effect. */
    public boolean isEmpty() {
        return this.byRole.isEmpty();
    }

    /** How many roles have a clip. */
    public int size() {
        return this.byRole.size();
    }

    /**
     * Every binding, as an unmodifiable map.
     *
     * <p>Iteration order is unspecified; a caller that wants a deterministic order iterates
     * {@link ClipRole#values()} and asks for each.
     */
    public @NotNull Map<ClipRole, String> asMap() {
        return this.byRole;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof RigClips clips && this.byRole.equals(clips.byRole);
    }

    @Override
    public int hashCode() {
        return this.byRole.hashCode();
    }

    @Override
    public @NotNull String toString() {
        if (this.byRole.isEmpty()) {
            return "RigClips[none]";
        }
        final StringBuilder text = new StringBuilder("RigClips[");
        boolean first = true;
        for (final ClipRole role : ClipRole.values()) {
            final String clip = this.byRole.get(role);
            if (clip == null) {
                continue;
            }
            if (!first) {
                text.append(", ");
            }
            text.append(role.key()).append("=").append(clip);
            first = false;
        }
        return text.append(']').toString();
    }
}
