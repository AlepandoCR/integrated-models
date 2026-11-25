package pandastic.studio.models.internal.render;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.StringJoiner;

/**
 * What this plugin does with a bone that one of the model's own animation clips already keyframes.
 *
 * <h2>The defect this enum exists to resolve</h2>
 * A bone's display transform is <strong>absolute</strong>, and two writers send it. The renderer's own
 * animation pass sends {@code bind ∘ animation} on every frame a keyframe advances; this plugin's
 * {@code PoseSink} sends {@code bind ∘ pose} on every emission step. On a bone neither touches twice
 * there is no conflict. On a bone a clip keyframes there are two packets per frame carrying two
 * different answers, and the display shows whichever arrived last — so the bone alternates between the
 * animated pose and ours in the rhythm of the emission rate. It reads as a renderer bug and it is two
 * writers disagreeing.
 *
 * <p>{@link pandastic.studio.models.api.rig.RigBone#animationOwned()} is the rig file saying which bones
 * those are. This enum is what the render layer does about it, and it is <strong>configuration</strong>
 * rather than a constant because the three answers below are genuinely different trade-offs and which
 * one a server wants depends on its models: a pack of hand-animated creatures wants {@link #RENDERER},
 * a pack of static props with cloth on them never has an animation-owned bone at all, and somebody
 * porting a rig from before this flag existed wants {@link #OVERRIDE} to get the old look back while
 * they work out which bones their clips touch.
 *
 * <h2>Why there is no "blend" option</h2>
 * Because a blend between two absolute transforms needs a weight, and a weight is a fourth answer with
 * none of the first three's properties: at any weight but 0 or 1 the bone is in a pose neither the rigger
 * nor the solver authored, and the artefact it produces — a clip that plays at reduced amplitude — looks
 * like a broken animation rather than like a setting. {@link #COMPOSE} is the useful middle: the full
 * clip <em>and</em> the full secondary motion, because it composes them instead of averaging them.
 */
public enum AnimationOwnership {

    /**
     * The renderer owns the bone; this plugin never writes it.
     *
     * <p>The default, and the only one of the three that cannot produce an artefact. The authored clip
     * plays exactly as the rigger authored it, including its position keyframes, and there is only ever
     * one writer. What it costs is secondary motion on that bone: a rig that declares both
     * {@code physics} and {@code animation-owned} on one bone gets the physics computed and discarded,
     * which is why the rig loader warns about that combination.
     *
     * <p>Note it is unconditional rather than "while a clip is playing". A clip that is not playing
     * leaves the renderer sending the bone's rest transform, which is what our pose would have sent for
     * an undriven bone anyway, and gating on the live animation state would mean the set of bones we
     * write changed from frame to frame — so a bone would be handed back and forth between two writers
     * every time a clip ended, which is the same race in slower motion.
     */
    RENDERER,

    /**
     * Compose this plugin's pose <em>on top of</em> the bone's live animated rotation.
     *
     * <p>Both layers move the bone: the clip establishes where it is and the solver adds secondary
     * motion to that, which is what {@code CLAUDE.md} already says physics is. The plugin becomes the
     * only writer whose packet matters, because every emission carries the animation's current value as
     * well as its own, so there is nothing left to race.
     *
     * <p>Two limitations, both documented where they arise in {@link AnimatedRotations}: only the
     * <em>rotation</em> of the clip is composed, so a bone whose clip also keyframes its position sits at
     * its authored offset while it rotates; and the composition is per bone rather than down the model
     * hierarchy, so an animated bone that is <em>not</em> in the rig does not carry its rig descendants.
     */
    COMPOSE,

    /**
     * Write the bone absolutely and let the packets race, which is what this plugin did before the flag
     * existed.
     *
     * <p>Kept because it is the previous behaviour and because removing an option is not a migration
     * path: an operator who has tuned a rig against it can ask for it by name rather than discovering at
     * restart that their model moves differently. It is the wrong answer on every model in this
     * repository that ships a clip, and the javadoc says so rather than the key being silently absent.
     */
    OVERRIDE;

    /** This policy's spelling in {@code render.yml}, which is its name in lower case. */
    public @NotNull String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The policy {@code value} names, or {@code null} when it names none.
     *
     * <p>Lenient about case, surrounding space, and {@code -} and {@code _}, like every other key in this
     * project's files. Answers {@code null} rather than throwing or defaulting, because the caller is
     * the configuration mapper and its job is to refuse the whole reload with the key named — an
     * operator who wrote {@code render} meant {@link #RENDERER}, and quietly substituting the default
     * would leave them believing a policy is in force that is not.
     *
     * @param value the authored spelling, or {@code null} for a key present with an empty value
     */
    public static @Nullable AnimationOwnership parse(@Nullable String value) {
        if (value == null) {
            return null;
        }
        final String canonical = value.trim().toLowerCase(Locale.ROOT)
                .replace("-", "").replace("_", "");
        for (final AnimationOwnership candidate : values()) {
            if (candidate.key().equals(canonical)) {
                return candidate;
            }
        }
        return null;
    }

    /** Every policy's spelling, for a message that lists the options. */
    public static @NotNull String keys() {
        final StringJoiner keys = new StringJoiner(", ");
        for (final AnimationOwnership candidate : values()) {
            keys.add(candidate.key());
        }
        return keys.toString();
    }
}
