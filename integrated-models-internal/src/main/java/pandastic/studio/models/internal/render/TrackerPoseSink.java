package pandastic.studio.models.internal.render;

import kr.toxicity.model.api.bone.RenderedBone;
import kr.toxicity.model.api.data.renderer.ModelRenderer;
import kr.toxicity.model.api.entity.BaseEntity;
import kr.toxicity.model.api.nms.AnimationBundler;
import kr.toxicity.model.api.nms.DisplayTransformer;
import kr.toxicity.model.api.nms.ModelDisplay;
import kr.toxicity.model.api.tracker.EntityTracker;
import kr.toxicity.model.api.tracker.Tracker;
import kr.toxicity.model.api.util.function.BonePredicate;
import org.jetbrains.annotations.NotNull;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import pandastic.studio.models.api.pose.Pose;
import pandastic.studio.models.api.render.PoseSink;
import pandastic.studio.models.api.rig.Rig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Writes a {@link Pose} onto a live BetterModel tracker: the renderer half of the {@link PoseSink} seam.
 *
 * <h2>Where the packets come from</h2>
 * A bone's display transform is written through a {@code DisplayTransformer}, the same call BetterModel
 * makes at the end of its own pipeline. It takes an <strong>absolute</strong> transform and composes
 * with nothing, which is exactly why {@link RigBindPose} has to do the hierarchy walk before we get
 * here.
 *
 * <p>The transforms go into the bundler of a {@code Tracker#frame} handler, which the renderer runs
 * every 25 ms on its own worker pool and flushes at the end of the same callback. That is the only place
 * a valid bundler exists, and a pose can arrive from anywhere, so {@link #accept} stages the computed
 * transforms and the frame handler drains them. Two consequences worth knowing:
 *
 * <ul>
 *   <li>Emission timing is quantised to the renderer's 25 ms frame, so a 20 Hz cadence lands with up to
 *       half a client tick of jitter. {@code EmissionCadence#withJitterTolerance()} exists for exactly
 *       this and is worth turning on here.</li>
 *   <li>Several poses arriving between two frames collapse into one emission. That is correct rather
 *       than lossy: a display consumes its pending-update flags once per client tick, so the discarded
 *       intermediate poses were never going to be rendered.</li>
 * </ul>
 *
 * <h2>Four things that look like the right API and are not</h2>
 * <ol>
 *   <li><strong>{@code RenderedBone#addPositionModifier} and {@code add*RotModifier} are not usable
 *       here, and this is not an oversight.</strong> They are additive and unremovable — each call grows
 *       a {@code Function#andThen} chain with no clear or set — they are only invoked while an animation
 *       is actively advancing keyframes, and they are sampled at the keyframe rate, roughly 6.7 Hz with
 *       the default {@code lerp-frame-time}, rather than at the tick rate. A pose written through them
 *       would be stuttery, would double every time the model was re-posed and would stop entirely when
 *       the model stopped animating. Do not "fix" this class into using them.</li>
 *   <li><strong>Bone lookup goes through a predicate on purpose.</strong> {@code Tracker#bone(BoneName)}
 *       matches the <em>raw</em> identifier, case-sensitively, so it breaks the moment a rigger adds a
 *       behavioural tag prefix in Blockbench. {@code BonePredicate#name} compares the tag-stripped bare
 *       name with {@code equalsIgnoreCase}, which is precisely the matching contract {@link Rig}
 *       documents. Passing the predicate explicitly also removes the overload-resolution trap: a
 *       {@code BonePredicate.Builder} is itself a {@code Predicate<RenderedBone>}, so a bare string and
 *       a built predicate reach different overloads with different semantics.</li>
 *   <li><strong>{@code createTransformer()} is called once per bone, at bind time.</strong> Each call
 *       allocates fresh dirty-tracking state, so calling it per frame both leaks and defeats the
 *       deduplication the transformer exists to provide.</li>
 *   <li><strong>Hitboxes do not follow a pose written this way.</strong> BetterModel derives hitbox
 *       position from the interpolated {@code BoneMovement} pair that only its own animation pass
 *       writes, and that state is not reachable from the api jar. A posed bone moves visually and its
 *       hitbox stays where the animation left it. This is a known limitation of the binding, not
 *       something to work around from out here — secondary motion on decorative bones is the use case,
 *       and decorative bones do not carry hitboxes.</li>
 * </ol>
 *
 * <h2>It shares the display with the renderer's animation pass</h2>
 * Our transformer is a second writer to the same display, with its own dirty-tracking state that knows
 * nothing about what BetterModel's own transformer last sent. For a bone the renderer is not animating
 * — the only kind a physics rig should name — we are the sole writer and this is exact. For a bone an
 * animation is also driving, the two writers race and the last one in the frame wins.
 *
 * <h2>Threading</h2>
 * {@link #accept} may run on any thread and the frame handler runs on a renderer worker, so the staging
 * buffer is guarded by a lock. It is held for the length of one drain, which touches only moving bones;
 * the only contender is this model's own simulation. Nothing here reads an entity, a world or a block,
 * so none of it is region-bound.
 *
 * <p>{@code accept} itself has a <strong>single owner</strong>, inherited from the pose it reads: a
 * {@link Pose} belongs to one simulation and is not thread-safe, so two threads posing the same model
 * would already be a defect upstream. The lock exists to separate the simulation from the renderer's
 * worker, not to serialise concurrent callers.
 *
 * <h2>Settings are read once per emission, never per value</h2>
 * {@link #accept} takes exactly one {@link RenderSettings} snapshot from its
 * {@link RenderSettingsSource} and uses it for the whole pass. Reading the source again inside the
 * per-bone loop would let a reload land between two bones of one chain and judge a parent and its child
 * against different thresholds, which can admit the parent and reject the child — the one outcome the
 * ancestor propagation exists to make impossible. A changed epsilon rebuilds the dirty tracker before
 * the pass rather than during it; see {@link #retune}.
 */
public final class TrackerPoseSink implements PoseSink {

    /**
     * Components of a quaternion.
     *
     * <p><strong>A structural fact about the maths, not a tunable.</strong> A rotation in three
     * dimensions is four numbers — {@code (x, y, z, w)} — and the staging buffer is strided by it so a
     * bone's rotation occupies one contiguous run. Changing it would not configure anything; it would
     * mean this class was no longer handling quaternions.
     */
    private static final int QUATERNION_COMPONENTS = 4;

    /**
     * Components of a 3-vector.
     *
     * <p><strong>Structural, for the same reason as {@link #QUATERNION_COMPONENTS}.</strong> Positions
     * and scales are {@code (x, y, z)} because the world is three-dimensional, and the stride exists to
     * index that layout rather than to express a preference.
     */
    private static final int VECTOR_COMPONENTS = 3;

    private final @NotNull Rig rig;
    private final @NotNull RigBindPose bindPose;
    private final @NotNull RenderSettingsSource settings;
    private final DisplayTransformer[] transformers;
    /** BetterModel applies this after the model transform; keep the same player passenger offset. */
    private final @NotNull Vector3f defaultPosition;

    /**
     * Guarded by {@link #lock}, and the only mutable collaborator here.
     *
     * <p>Not final because a configuration reload that moves the dirty epsilon has to be answered with
     * a tracker built for the new threshold: the epsilon is fixed per tracker precisely so one pass
     * cannot mix two of them. Replaced under the lock, between passes, never inside one.
     */
    private @NotNull BoneDirtyTracker dirty;

    /** Display scales, constant after binding, so the drain never has to touch the bind pose. */
    private final float[] scales;

    private final @NotNull ReentrantLock lock = new ReentrantLock();
    private final float[] stagedRotation;
    private final float[] stagedPosition;
    private final boolean[] staged;
    private boolean hasStaged;
    private int stagedDuration;
    private long emittedTransforms;

    /** Scratch for {@link #accept}, reached only under the lock. */
    private final Quaternionf rotationIn = new Quaternionf();
    private final Vector3f positionIn = new Vector3f();

    /**
     * Scratch for the drain.
     *
     * <p>Reusing these across bones is safe because the renderer's own call site proves the transformer
     * copies: BetterModel passes its per-bone {@code positionCache}, {@code scaleCache} and the live
     * {@code BoneMovement#rotation} into the same method and mutates all three the next frame. The
     * {@code DisplayTransformer} implementations themselves live in the version-specific NMS modules and
     * are not in the api jar, so that inference is the strongest evidence available at compile time.
     */
    private final Quaternionf rotationOut = new Quaternionf();
    private final Vector3f positionOut = new Vector3f();
    private final Vector3f scaleOut = new Vector3f();

    private volatile boolean active = true;

    private TrackerPoseSink(
            @NotNull Rig rig,
            @NotNull RigBindPose bindPose,
            DisplayTransformer[] transformers,
            @NotNull RenderSettingsSource settings,
            @NotNull RenderSettings initial,
            @NotNull Vector3f defaultPosition,
            @NotNull BaseEntity sourceEntity,
            int headBone
    ) {
        final int bones = rig.boneCount();
        this.rig = rig;
        this.bindPose = bindPose;
        this.settings = settings;
        this.defaultPosition = new Vector3f(defaultPosition);
        this.dirty = BoneDirtyTracker.of(rig, initial);
        this.transformers = transformers;
        this.scales = new float[bones * VECTOR_COMPONENTS];
        this.stagedRotation = new float[bones * QUATERNION_COMPONENTS];
        this.stagedPosition = new float[bones * VECTOR_COMPONENTS];
        this.staged = new boolean[bones];

        final Vector3f scale = new Vector3f();
        for (int bone = 0; bone < bones; bone++) {
            bindPose.scale(bone, scale);
            this.scales[bone * VECTOR_COMPONENTS] = scale.x;
            this.scales[bone * VECTOR_COMPONENTS + 1] = scale.y;
            this.scales[bone * VECTOR_COMPONENTS + 2] = scale.z;
        }
    }

    /**
     * Resolves every bone of {@code rig} against {@code tracker} and starts emitting into its frames.
     *
     * <p>Fails with the complete list of problems rather than the first one: a rigger fixing a renamed
     * bone wants to see every name at once, not to restart the server per bone.
     *
     * <p>Takes a <em>source</em> rather than a snapshot because a sink outlives any one configuration:
     * it is registered with the renderer's frame handler for the tracker's whole lifetime, and
     * BetterModel offers no way to remove a frame handler, so rebinding a model to pick up a reloaded
     * epsilon would mean leaking the old handler. One snapshot is taken here, for the bind-time
     * diagnostics; every emission takes its own.
     *
     * @param settings where the sink reads the dirty epsilon from, once per emission
     * @throws IllegalStateException if the tracker renders a different model than the rig names, or any
     *                               bone is missing, ambiguous, or a simulated bone has no display
     */
    public static @NotNull TrackerPoseSink bind(
            @NotNull Rig rig,
            @NotNull Tracker tracker,
            @NotNull RenderSettingsSource settings
    ) {
        final RenderSettings initial = settings.current();
        final ModelRenderer renderer = tracker.getPipeline().getParent();
        if (!renderer.name().equals(rig.getModelId())) {
            throw new IllegalStateException(
                    "Rig '" + rig.getName() + "' is written against model '" + rig.getModelId()
                            + "' but this tracker renders '" + renderer.name()
                            + "'. Binding it would drive whichever bones happen to share a name.");
        }

        // Resolve the geometry first: its failure message carries the model's bone list, which is what
        // a rigger actually needs when a name stopped matching.
        final RigBindPose bindPose =
                ModelSkeleton.of(renderer).bindPose(rig, modelScaleOf(tracker, rig), initial);

        final int bones = rig.boneCount();
        final DisplayTransformer[] transformers = new DisplayTransformer[bones];
        final List<String> unresolved = new ArrayList<>();
        final List<String> withoutDisplay = new ArrayList<>();

        for (int bone = 0; bone < bones; bone++) {
            final String name = rig.bone(bone).name();
            final RenderedBone rendered = tracker.bone(BonePredicate.name(name));
            if (rendered == null) {
                unresolved.add(name);
                continue;
            }
            final ModelDisplay display = rendered.getDisplay();
            if (display == null) {
                if (rig.bone(bone).hasPhysics()) {
                    withoutDisplay.add(name);
                }
                continue;
            }
            // Once per bone, for the lifetime of the binding. See trap 3 in the class javadoc.
            transformers[bone] = display.createTransformer();
        }

        if (!unresolved.isEmpty() || !withoutDisplay.isEmpty()) {
            throw new IllegalStateException(
                    "Rig '" + rig.getName() + "' cannot bind to a live '" + rig.getModelId()
                            + "' tracker: " + unresolved.size() + " bone(s) did not resolve "
                            + unresolved + " and " + withoutDisplay.size()
                            + " bone(s) have no display to drive " + withoutDisplay
                            + ".");
        }

        if (!(tracker instanceof EntityTracker entityTracker)) {
            throw new IllegalArgumentException("A rig pose sink requires an entity tracker");
        }
        final TrackerPoseSink sink =
                new TrackerPoseSink(
                        rig, bindPose, transformers, settings, initial,
                        entityTracker.sourceEntity().passengerPosition(new Vector3f()).mul(-1F),
                        entityTracker.sourceEntity(), rig.indexOf("head").orElse(-1));
        tracker.frame((liveTracker, bundlerSet) -> {
            // BetterModel's automatic walk animation writes the same limb rotations as the gait stage.
            // Stop it immediately before packet assembly so the rig remains the sole limb writer.
            liveTracker.stopAnimation("walk");
            sink.drain(liveTracker, bundlerSet);
        });
        return sink;
    }

    /**
     * The tracker's model scale multiplier, read off a resolved bone.
     *
     * <p>{@code hitBoxScale()} is misleadingly named: it returns the bone's scale supplier, which is the
     * same {@code mul} the renderer applies to a display's position and scale. It is read once, so a
     * model rescaled after binding needs a rebind — the alternative is reading an entity attribute on
     * the emission path, which is the one thing a sink must not do.
     */
    private static float modelScaleOf(@NotNull Tracker tracker, @NotNull Rig rig) {
        for (int bone = 0; bone < rig.boneCount(); bone++) {
            final RenderedBone rendered = tracker.bone(BonePredicate.name(rig.bone(bone).name()));
            if (rendered != null) {
                final float scale = rendered.hitBoxScale();
                return scale > 0F && Float.isFinite(scale) ? scale : 1F;
            }
        }
        return 1F;
    }

    @Override
    public void accept(@NotNull Rig rig, @NotNull Pose pose, int interpolationTicks) {
        if (rig != this.rig) {
            throw new IllegalArgumentException(
                    "This sink is bound to rig '" + this.rig.getName() + "' but was handed '"
                            + rig.getName() + "'; its bind data is derived from the rig it was bound to");
        }
        if (!this.active) {
            return;
        }

        // One read, before the pass. Everything below judges every bone against this snapshot.
        final RenderSettings settings = this.settings.current();

        this.bindPose.compose(pose);

        this.lock.lock();
        try {
            retune(settings);
            for (int bone = 0; bone < this.staged.length; bone++) {
                // Must be called for every bone, in order: the tracker resolves "my parent moved, so my
                // absolute transform changed even though my own angles did not" in this one pass.
                if (!this.dirty.admit(bone, pose.pitch(bone), pose.yaw(bone), pose.roll(bone))) {
                    continue;
                }

                this.bindPose.rotation(bone, this.rotationIn);
                this.bindPose.position(bone, this.positionIn);

                final int quaternionSlot = bone * QUATERNION_COMPONENTS;
                this.stagedRotation[quaternionSlot] = this.rotationIn.x;
                this.stagedRotation[quaternionSlot + 1] = this.rotationIn.y;
                this.stagedRotation[quaternionSlot + 2] = this.rotationIn.z;
                this.stagedRotation[quaternionSlot + 3] = this.rotationIn.w;

                final int vectorSlot = bone * VECTOR_COMPONENTS;
                this.stagedPosition[vectorSlot] = this.positionIn.x;
                this.stagedPosition[vectorSlot + 1] = this.positionIn.y;
                this.stagedPosition[vectorSlot + 2] = this.positionIn.z;

                this.staged[bone] = true;
                this.hasStaged = true;
            }
            // The window belongs to the newest pose: an older one's transforms have not been sent yet,
            // so they will tween over the interval the newest emission actually spans.
            this.stagedDuration = interpolationTicks;
        } finally {
            this.lock.unlock();
        }
    }

    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360F;
        if (wrapped >= 180F) {
            wrapped -= 360F;
        } else if (wrapped < -180F) {
            wrapped += 360F;
        }
        return wrapped;
    }

    /**
     * Rebuilds the dirty tracker if {@code settings} moved the epsilon. Called under {@link #lock},
     * before a pass, never inside one.
     *
     * <p>Rebuilding rather than mutating costs the rig one full re-emission, and that is the behaviour
     * worth having: every baseline in the old tracker records a decision made against the old
     * threshold, so keeping them would leave bones that the new, tighter threshold would have emitted
     * sitting at a stale transform until they happened to move again. One extra emission per reload is
     * nothing; a chain frozen a fraction short of where the physics put it is the kind of thing nobody
     * can reproduce.
     */
    private void retune(@NotNull RenderSettings settings) {
        if (settings.dirtyEpsilonRadians() == this.dirty.epsilonRadians()) {
            return;
        }
        this.dirty = BoneDirtyTracker.of(this.rig, settings);
    }

    /**
     * Stops emitting.
     *
     * <p>BetterModel offers no way to remove a frame handler once registered, so the handler outlives
     * this call and gates on a flag instead. Cheap, and the tracker's own lifetime bounds it.
     */
    public void detach() {
        this.active = false;
    }

    /** Whether this sink is still emitting. */
    public boolean isActive() {
        return this.active;
    }

    /**
     * How many bone transforms have gone on the wire since binding.
     *
     * <p>Worth having: against the number of emissions times the bone count it is a direct readout of
     * what the dirty tracking is saving, and a rig that never settles shows up as a number that climbs
     * at full bone count forever.
     */
    public long emittedTransforms() {
        this.lock.lock();
        try {
            return this.emittedTransforms;
        } finally {
            this.lock.unlock();
        }
    }

    /** The {@code Tracker#frame} handler: turns whatever is staged into display transforms. */
    private void drain(@NotNull Tracker tracker, @NotNull Tracker.BundlerSet bundlerSet) {
        if (!this.active) {
            return;
        }
        this.lock.lock();
        try {
            if (!this.hasStaged) {
                return;
            }
            // Fetched inside the callback, never cached: the set replaces its bundler after every flush,
            // so a retained reference would build packets into something already sent.
            final AnimationBundler bundler = bundlerSet.getViewBundler();
            final int duration = this.stagedDuration;

            for (int bone = 0; bone < this.staged.length; bone++) {
                if (!this.staged[bone]) {
                    continue;
                }
                this.staged[bone] = false;
                if (this.transformers[bone] == null) {
                    continue;
                }

                final int quaternionSlot = bone * QUATERNION_COMPONENTS;
                this.rotationOut.set(
                        this.stagedRotation[quaternionSlot],
                        this.stagedRotation[quaternionSlot + 1],
                        this.stagedRotation[quaternionSlot + 2],
                        this.stagedRotation[quaternionSlot + 3]);

                final int vectorSlot = bone * VECTOR_COMPONENTS;
                this.positionOut.set(
                        this.stagedPosition[vectorSlot],
                        this.stagedPosition[vectorSlot + 1],
                        this.stagedPosition[vectorSlot + 2]);
                this.positionOut.add(this.defaultPosition);
                this.scaleOut.set(
                        this.scales[vectorSlot],
                        this.scales[vectorSlot + 1],
                        this.scales[vectorSlot + 2]);

                this.transformers[bone].transform(
                        duration, this.positionOut, this.scaleOut, this.rotationOut, bundler);
                this.emittedTransforms++;
            }
            this.hasStaged = false;
        } finally {
            this.lock.unlock();
        }
    }
}
