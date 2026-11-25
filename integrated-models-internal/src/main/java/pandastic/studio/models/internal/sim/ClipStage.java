package pandastic.studio.models.internal.sim;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.rig.ClipRole;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.api.rig.RigClips;

import java.util.Objects;

/**
 * Drives one model's <em>own</em> animation clips from its owner's speed: the counterpart to
 * {@link LocomotionStage}, for the models that ship something better than a synthesised gait.
 *
 * <h2>Which models this is for, and which it is not</h2>
 * Exactly the ones whose rig declares a {@code clips:} section. Every animal rig in this repository does;
 * {@code krampus}, the one model that ships no animations at all, does not and therefore gets
 * {@code null} from {@link #of} and keeps the existing path untouched. That is the whole selection rule:
 * there is no flag to set per model, because a rig naming clips is already the statement that the model
 * has them.
 *
 * <h2>The role falls back down, never up</h2>
 * A rig that declares {@code walk} but no {@code run} plays its walk when the owner sprints, and a rig
 * that declares neither plays its idle. Running down the chain {@code RUN → WALK → IDLE} is right because
 * each state is a less energetic version of the one above it; running up it would mean a standing model
 * playing a walk, which is the one failure that looks like a bug rather than like a limitation.
 *
 * <h2>Idempotent by clip name, not by role</h2>
 * The stage remembers which clip <em>name</em> is playing and does nothing when the decision resolves to
 * the same one. That is deliberately coarser than remembering the role: a rig whose {@code run} and
 * {@code walk} both name the same clip must not restart it when the owner crosses the sprint threshold,
 * and a rig that reuses one clip for two roles is a perfectly ordinary thing for a rigger to do.
 *
 * <h2>Threading</h2>
 * One stage belongs to one model and is only ever touched from that model's owning region thread — the
 * same thread that samples the anchor and the only one allowed to touch a tracker. See {@link ClipPlayer}
 * for why the decision lives there rather than in the simulation step.
 */
public final class ClipStage {

    private final @NotNull RigClips clips;
    private final @NotNull ClipSettingsSource settings;
    private final @NotNull ClipPlayer player;
    private final @NotNull ClipSelector selector = new ClipSelector();

    /** The clip currently playing, or {@code null} if none is. */
    private @Nullable String playing;

    /**
     * Whether a clip this rig names turned out not to exist in the model.
     *
     * <p>Latched per clip name so a content error is reported once rather than every sample, and so a
     * rig naming a clip the model does not have degrades into "that state has no animation" rather than
     * into a log line per tick forever.
     */
    private @Nullable String refused;

    private ClipStage(
            @NotNull RigClips clips,
            @NotNull ClipSettingsSource settings,
            @NotNull ClipPlayer player
    ) {
        this.clips = clips;
        this.settings = settings;
        this.player = player;
    }

    /**
     * A stage for {@code rig}, or {@code null} if the rig names no clips.
     *
     * <p>{@code null} rather than a no-op stage, so the caller's field is the same kind of optional
     * collaborator {@link LocomotionStage} already is and a model with no clips costs nothing per sample.
     *
     * @param settings where the thresholds and blend times are read from, once per decision
     * @param player   how the clip reaches the renderer; region work, see {@link ClipPlayer}
     */
    public static @Nullable ClipStage of(
            @NotNull Rig rig,
            @NotNull ClipSettingsSource settings,
            @NotNull ClipPlayer player
    ) {
        Objects.requireNonNull(rig, "rig");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(player, "player");
        return rig.clips().isEmpty() ? null : new ClipStage(rig.clips(), settings, player);
    }

    /**
     * Decides which clip should be playing and makes it so.
     *
     * <p>One settings read, at the top, used for the whole decision — the same discipline the simulation
     * step and the driver sample follow, and for the same reason: a reload landing between the threshold
     * test and the hold test would make one decision against two generations of configuration.
     *
     * @param speedBlocksPerTick the owner's horizontal speed, from the <em>same</em> driver sample the
     *                           simulation was handed. Reading the anchor again for it would let the two
     *                           disagree about how fast the owner is within one tick
     * @param deltaSeconds       seconds since the previous call, for the minimum-hold timer
     * @return whether the playing clip changed
     */
    public boolean advance(float speedBlocksPerTick, float deltaSeconds) {
        // ONE read. See the note above.
        final ClipSettings current = this.settings.current();
        if (!current.enabled()) {
            return stopPlaying();
        }

        final ClipRole role = this.selector.select(speedBlocksPerTick, deltaSeconds, current);
        final String wanted = resolve(role);
        if (Objects.equals(wanted, this.playing)) {
            return false;
        }
        if (wanted != null && wanted.equals(this.refused)) {
            // The model does not have it. Holding the previous clip is better than stopping: a walk that
            // keeps playing while the owner sprints is wrong, a model that freezes is broken.
            return false;
        }

        final String previous = this.playing;
        if (wanted == null) {
            return stopPlaying();
        }
        if (!this.player.play(wanted, current.blendInTicks(), current.blendOutTicks())) {
            this.refused = wanted;
            return false;
        }
        this.playing = wanted;
        // Stopped after the replacement started, so the renderer has something to blend out of. Skipped
        // when the two name the same clip, which resolve() can produce across two roles.
        if (previous != null && !previous.equals(wanted)) {
            this.player.stop(previous);
        }
        return true;
    }

    /**
     * Stops whatever is playing and returns to standing.
     *
     * <p>For a model being rebound or torn down. Note it goes through the player, so it is region work
     * like everything else here.
     *
     * @return whether anything was playing
     */
    public boolean stop() {
        final boolean stopped = stopPlaying();
        this.selector.reset();
        return stopped;
    }

    /** The state currently held, which is {@link ClipSelector#role()}. */
    public @NotNull ClipRole role() {
        return this.selector.role();
    }

    /** The clip currently playing, or {@code null} if none is. */
    public @Nullable String playing() {
        return this.playing;
    }

    /** Which clips this stage can choose between. */
    public @NotNull RigClips clips() {
        return this.clips;
    }

    /** The clip name the model turned out not to have, or {@code null} if none has been refused. */
    public @Nullable String refused() {
        return this.refused;
    }

    /**
     * The clip bound to {@code role}, falling back to the less energetic states.
     *
     * <p>{@code null} when the rig names nothing at all for this state or anything below it, which for a
     * stage that exists at all can only happen if the rig names only {@code run}.
     */
    private @Nullable String resolve(@NotNull ClipRole role) {
        return switch (role) {
            case RUN -> {
                final String run = this.clips.clip(ClipRole.RUN);
                yield run != null ? run : resolve(ClipRole.WALK);
            }
            case WALK -> {
                final String walk = this.clips.clip(ClipRole.WALK);
                yield walk != null ? walk : this.clips.clip(ClipRole.IDLE);
            }
            case IDLE -> this.clips.clip(ClipRole.IDLE);
        };
    }

    /** Stops the playing clip, if any. */
    private boolean stopPlaying() {
        final String previous = this.playing;
        if (previous == null) {
            return false;
        }
        this.playing = null;
        this.player.stop(previous);
        return true;
    }
}
