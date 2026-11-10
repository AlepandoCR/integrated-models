package pandastic.studio.models.internal.rig;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.rig.Rig;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Answers "give me a rig for this model", so that putting a model on an entity does not require writing
 * a rig file first.
 *
 * <h2>The gap this closes</h2>
 * {@code ModelBinder.bind(Rig, Entity)} needs a {@link Rig}, and until now the only way to get one was
 * {@link RigLoader} — a YAML file naming bones, which means opening the model in Blockbench to find out
 * what they are called. For a consumer that wants secondary motion that is the right price. For one that
 * only wants the model to appear on an entity, it is the whole cost of the feature, paid for nothing: a
 * rig with no physics drives nothing, and the solver returns immediately on a rig with no simulated
 * bones.
 *
 * <h2>Precedence: an author who wrote a rig meant it</h2>
 * <ol>
 *   <li>If an authored rig names the requested model, that rig is the answer. Matched
 *       case-insensitively through {@link Rig#normalise}, like every other name comparison in this
 *       layer.</li>
 *   <li>Otherwise a <strong>pass-through</strong> rig is derived from the model itself.</li>
 * </ol>
 *
 * <p>Never the other way round, and never a merge. A file is the only place anybody can express intent
 * about a model's physics, so a derivation that could shadow one would make a rig file's effect depend
 * on whether some other code path asked for the model first.
 *
 * <p>Two authored rigs may name one model, which no part of the loader checks: rigs are keyed by their
 * own name, and two files are free to point at the same {@code model}. This is the only type that cares,
 * so it is the one that reports it — with a warning naming both, and a deterministic choice (the
 * lexicographically first rig name) rather than whatever a concurrent map's iteration order happens to
 * be. An arbitrary choice between two tunings is the one outcome worse than either of them.
 *
 * <h2>A derived rig names exactly one bone</h2>
 * Not every bone, and the argument is not economy — though a pass-through rig naming 200 bones costs a
 * resolution, a reconstructed bind transform, a cached display transformer, three floats of pose and a
 * dirty-tracking slot per bone, at every bind, to move nothing.
 *
 * <p>The argument is that <strong>every named bone is another way for the bind to fail</strong>.
 * {@code TrackerPoseSink.bind} refuses a rig if any bone is missing, if any bone name matches two model
 * bones, or if any bone has no display — and a group with no cubes in it, which is what a pivot or an
 * empty root is, has no display. Both conditions are ordinary in hand-made models. A whole-model
 * derivation would therefore turn "render this model, no physics" into a hard failure for models the
 * renderer itself renders perfectly, and it would do it for the models most likely to be rigged by
 * somebody who knows what they are doing. One bone, chosen <em>because</em> it resolves unambiguously and
 * has a display, cannot fail that way — the rig is known bindable before anything spawns.
 *
 * <p>The bone is the first one in hierarchy order that qualifies, so it is the most root-ward drivable
 * bone the model has. Which bone it is does not matter: with no physics its pose stays at zero, the sink
 * writes it its own rest transform once and the dirty tracker never admits it again.
 *
 * <h2>What that forecloses, deliberately</h2>
 * A derived rig cannot be extended with physics later. The solver composes a bone's ancestors out of the
 * same pose in a single pass, so physics on a bone requires both the bone and its ancestor chain to be in
 * the rig — and a one-bone rig has nowhere to put a simulated bone except its single root, which rotates
 * the whole model rather than a part of it. So this is a terminal product, not a starting point: the
 * moment physics is wanted, the answer is a rig file, and that is the trade this class makes on purpose.
 *
 * <h2>An unknown model is reported, not thrown</h2>
 * {@link RigLoadResult} and a {@link RigDiagnostic}, exactly as a rig file's problems are. The project's
 * rule is that a programmatic caller's bad argument throws while a human's content problem is reported,
 * and a model id is <em>content</em>: which models exist is decided by what an operator dropped into
 * BetterModel's folder, and no consumer can know that at compile time. A plugin asking for a model this
 * server does not have is in the same position as a rig file naming a bone a rigger renamed — it needs a
 * message, not a stack trace. What does throw is a blank model id, which is a bug in the caller.
 *
 * <h2>Derivations are cached; authored lookups are not</h2>
 * A derivation reads a blueprint, and a disguise menu asks for the same model on every click. So a
 * successful derivation is kept, keyed by the model id <strong>exactly</strong> as the renderer keys it —
 * normalising the key would let a request for {@code wolf} be answered with a rig pointing at
 * {@code Wolf}, which is an id the renderer's own lookup may not resolve. Failures are not cached: they
 * cost one lookup to reproduce, and a model installed later must not be invisible forever because
 * something asked for it too early.
 *
 * <p>Authored lookups scan the rig collection every time, which is a handful of comparisons and is what
 * makes a reloaded rig set visible immediately. The only thing that can invalidate a cached derivation is
 * BetterModel reloading its own models, which is not an event this layer can see — so
 * {@link #invalidateAll()} exists and whoever owns the reload path calls it. Left uncalled, a stale entry
 * surfaces as a refused bind naming the bone that disappeared, never as a wrong render.
 */
public final class RigFactory {

    /**
     * Prefix of a derived rig's own name, so a log line says where the rig came from.
     *
     * <p>Not a tunable: it is the identity of the derivation in every message the binding layer prints
     * ("Rig 'passthrough:wolf' cannot bind to model 'wolf'"), and a configurable one would make a log
     * line from one server unsearchable against another.
     */
    private static final @NotNull String DERIVED_NAME_PREFIX = "passthrough:";

    private final @NotNull Supplier<Collection<Rig>> authoredRigs;
    private final @NotNull ModelBoneSource models;
    private final @NotNull Supplier<RigLoadSettings> settings;

    /**
     * Derived rigs, keyed by the model id exactly as asked for. Only successes are ever put here, which
     * is what makes a cache hit lossless: a derivation that succeeds produces no diagnostics, so there
     * is nothing a hit could fail to replay.
     */
    private final @NotNull Map<String, Rig> derived = new ConcurrentHashMap<>();

    private RigFactory(
            @NotNull Supplier<Collection<Rig>> authoredRigs,
            @NotNull ModelBoneSource models,
            @NotNull Supplier<RigLoadSettings> settings
    ) {
        this.authoredRigs = authoredRigs;
        this.models = models;
        this.settings = settings;
    }

    /**
     * A factory over the rigs and the models this server has.
     *
     * @param authoredRigs every rig loaded from {@code rigs/}, read on each request so that a reloaded
     *                     rig set takes effect without anybody telling this class
     * @param models       where a model's bones are read from; {@link ModelBoneSource#renderer()} in
     *                     production
     * @param settings     the rig-layer configuration snapshot, read once per derivation. Only
     *                     {@link RigLoadSettings#rigBounds()} and
     *                     {@link RigLoadSettings#profileBounds()} are used, because those are what
     *                     {@link Rig.Builder} validates against — the solver, the defaults and the
     *                     clamps describe coefficients, and a pass-through bone has none. It is this
     *                     whole snapshot rather than the two bounds separately so that one derivation
     *                     cannot read half its configuration from one generation and half from the next,
     *                     which is the same reason {@link RigLoader} takes one
     */
    public static @NotNull RigFactory of(
            @NotNull Supplier<Collection<Rig>> authoredRigs,
            @NotNull ModelBoneSource models,
            @NotNull Supplier<RigLoadSettings> settings
    ) {
        return new RigFactory(
                Objects.requireNonNull(authoredRigs, "authoredRigs"),
                Objects.requireNonNull(models, "models"),
                Objects.requireNonNull(settings, "settings"));
    }

    /**
     * The rig for {@code modelId}: the authored one if a file names this model, otherwise a derived
     * pass-through rig.
     *
     * <p>The diagnostics belong to this request rather than to the server's state, so a caller logging
     * them on a per-bind path will see a duplicate-rig warning once per bind. That is the caller's
     * decision to make — the alternative, remembering what has already been reported, would make a
     * configuration problem invisible to the operator who reloads to fix it.
     *
     * @param modelId the id the renderer knows the model by, matched exactly when deriving and
     *                case-insensitively when looking for an authored rig
     * @return the rig, or the reason there is none
     * @throws IllegalArgumentException if {@code modelId} is blank, which is a caller defect rather than
     *                                  a missing model
     */
    public @NotNull RigLoadResult rigFor(@NotNull String modelId) {
        Objects.requireNonNull(modelId, "modelId");
        if (modelId.isBlank()) {
            throw new IllegalArgumentException(
                    "A rig request needs a model id: there is no model to look for or derive from");
        }

        final List<RigDiagnostic> diagnostics = new ArrayList<>();
        final Rig authored = authoredRigFor(modelId, diagnostics);
        if (authored != null) {
            return RigLoadResult.success(authored, diagnostics);
        }

        final Rig cached = this.derived.get(modelId);
        if (cached != null) {
            return RigLoadResult.success(cached, diagnostics);
        }

        final RigLoadResult result = derive(modelId);
        // putIfAbsent, not computeIfAbsent: two callers arriving together both derive, the loser keeps
        // its own equal rig for that one bind and reads the winner's on the next. Nothing compares a rig
        // by identity - unlike a solver, whose identity a live PhysicsState depends on - so the worst
        // outcome is one extra walk of a blueprint, and the alternative would hold a map bin's lock for
        // the length of that walk.
        result.rig().ifPresent(rig -> this.derived.putIfAbsent(modelId, rig));
        return result;
    }

    /**
     * Forgets the derivation cached for {@code modelId}, keyed exactly as {@link #rigFor(String)} keyed
     * it.
     *
     * @return whether there was one
     */
    public boolean invalidate(@NotNull String modelId) {
        Objects.requireNonNull(modelId, "modelId");
        return this.derived.remove(modelId) != null;
    }

    /**
     * Forgets every derivation, which is what BetterModel reloading its models requires.
     *
     * <p>Safe at any time: a rig already handed out is immutable and keeps working, since a bound model
     * holds its own resolution of the bones it was bound against. This only decides what the next request
     * gets.
     *
     * @return how many derivations were forgotten
     */
    public int invalidateAll() {
        final int forgotten = this.derived.size();
        this.derived.clear();
        return forgotten;
    }

    /** How many derivations are currently cached. */
    public int cachedCount() {
        return this.derived.size();
    }

    /**
     * The authored rig naming {@code modelId}, or {@code null} if no file does.
     *
     * <p>Ties are broken on the normalised rig name so the answer is the same on every server start. The
     * collection arrives with no promised order — in production it is the values of a concurrent map — so
     * "the first one" would be a different rig from one restart to the next, and the two rigs are two
     * different tunings for one model, which is exactly the case where an arbitrary answer is worse than
     * either.
     */
    private @Nullable Rig authoredRigFor(
            @NotNull String modelId,
            @NotNull List<RigDiagnostic> diagnostics
    ) {
        final String wanted = Rig.normalise(modelId);
        Rig chosen = null;
        final List<String> alternatives = new ArrayList<>();
        for (final Rig rig : this.authoredRigs.get()) {
            if (!Rig.normalise(rig.getModelId()).equals(wanted)) {
                continue;
            }
            if (chosen == null) {
                chosen = rig;
                continue;
            }
            final Rig loser;
            if (Rig.normalise(rig.getName()).compareTo(Rig.normalise(chosen.getName())) < 0) {
                loser = chosen;
                chosen = rig;
            } else {
                loser = rig;
            }
            alternatives.add(loser.getName());
        }

        if (chosen != null && !alternatives.isEmpty()) {
            alternatives.sort(Comparator.naturalOrder());
            diagnostics.add(RigDiagnostic.warning(source(modelId), null,
                    "is driven by more than one rig: using '" + chosen.getName() + "' and ignoring "
                            + alternatives + ". Two rigs for one model are two different tunings, and"
                            + " nothing can know which was meant - point one of them at another model,"
                            + " or merge them into one file."));
        }
        return chosen;
    }

    /** Builds the pass-through rig for {@code modelId}, or explains why the model cannot carry one. */
    private @NotNull RigLoadResult derive(@NotNull String modelId) {
        final Optional<List<ModelBoneLink>> read = this.models.bonesOf(modelId);
        if (read.isEmpty()) {
            return failure(modelId, null,
                    "is not a model the renderer has loaded, so there is no rig to derive and no rig"
                            + " file naming it either. Check the spelling, and that the model's file is"
                            + " in the renderer's model folder.");
        }

        final List<ModelBoneLink> bones = read.get();
        requireTopological(modelId, bones);
        if (bones.isEmpty()) {
            return failure(modelId, null,
                    "has no bones at all, and a rig has to name at least one bone to exist.");
        }

        // Computed once and handed to both: the count a bone's name occurs with decides whether it can
        // be chosen and, if nothing can be, is half of the explanation why.
        final Map<String, Integer> occurrences = occurrences(bones);
        final ModelBoneLink chosen = pickDrivableBone(bones, occurrences);
        if (chosen == null) {
            return failure(modelId, null, nothingDrivable(bones, occurrences));
        }

        final RigLoadSettings current = this.settings.get();
        try {
            final Rig rig = Rig.builder(
                            DERIVED_NAME_PREFIX + modelId, modelId,
                            current.rigBounds(), current.profileBounds())
                    // Normalised here rather than left to the builder, which normalises anyway, because
                    // the loader does the same and the two should read alike: what a rig stores is the
                    // key it matches on, and that is worth being explicit about at every call site.
                    .root(Rig.normalise(chosen.name()))
                    .build();
            return RigLoadResult.success(rig, List.of());
        } catch (final IllegalArgumentException refused) {
            // Backstop, like RigFileReader's. The bone ceiling is at least one, the name is non-blank by
            // ModelBoneLink's own constructor and there is no physics to validate, so the builder has
            // nothing left to refuse. If it does, the two disagree about what a legal rig is.
            return failure(modelId, chosen.name(),
                    "produced a rig the builder refused: " + refused.getMessage()
                            + ". This is a derivation defect, not a model one.");
        }
    }

    /**
     * The first bone a rig can actually drive, or {@code null} if the model has none.
     *
     * <p>First in hierarchy order, so the chosen bone is the most root-ward drivable one — which is the
     * bone least likely to be removed by a rigger later, and the one whose name reads best in a
     * diagnostic. Eligibility is exactly what the binding layer checks: the bone has a display, and its
     * bare name belongs to no other bone.
     */
    private static @Nullable ModelBoneLink pickDrivableBone(
            @NotNull List<ModelBoneLink> bones,
            @NotNull Map<String, Integer> occurrences
    ) {
        for (final ModelBoneLink bone : bones) {
            if (bone.hasDisplay() && occurrences.get(Rig.normalise(bone.name())) == 1) {
                return bone;
            }
        }
        return null;
    }

    /** How many bones share each normalised bone name, which is what ambiguity means here. */
    private static @NotNull Map<String, Integer> occurrences(@NotNull List<ModelBoneLink> bones) {
        final Map<String, Integer> occurrences = HashMap.newHashMap(bones.size());
        for (final ModelBoneLink bone : bones) {
            occurrences.merge(Rig.normalise(bone.name()), 1, Integer::sum);
        }
        return occurrences;
    }

    /** Why a model with bones still has nothing a rig can name, counted so it can be acted on. */
    private static @NotNull String nothingDrivable(
            @NotNull List<ModelBoneLink> bones,
            @NotNull Map<String, Integer> occurrences
    ) {
        int withoutDisplay = 0;
        int sharedName = 0;
        for (final ModelBoneLink bone : bones) {
            if (!bone.hasDisplay()) {
                withoutDisplay++;
            } else if (occurrences.get(Rig.normalise(bone.name())) > 1) {
                sharedName++;
            }
        }
        return "has " + bones.size() + " bone(s) and not one a rig can drive: " + withoutDisplay
                + " have no display, which is what a group with no cubes in it gets, and " + sharedName
                + " share a bare name with another bone, so a rig could not say which it meant. This is"
                + " a property of the model rather than of the request, and authoring a rig file would"
                + " hit the same two walls: put a cube in one of its groups, or give two"
                + " identically-named groups different names.";
    }

    /**
     * Checks the one thing a {@link ModelBoneSource} promises and this class relies on.
     *
     * <p>Throws rather than reports, because it is the seam's contract and not the model's: a source
     * handing out a child before its parent is a defect in the source, and the symptom it would otherwise
     * produce — a rig derived against a bone deeper in the model than the one intended — is invisible.
     * The production source satisfies it by construction, so this can only fire on a new one.
     */
    private static void requireTopological(
            @NotNull String modelId,
            @NotNull List<ModelBoneLink> bones
    ) {
        for (int index = 0; index < bones.size(); index++) {
            final ModelBoneLink bone = bones.get(index);
            if (bone.parentIndex() >= index) {
                throw new IllegalStateException(
                        "The bone source for model '" + modelId + "' put bone " + index + " ('"
                                + bone.name() + "') before its parent " + bone.parentIndex()
                                + "; a parent has to precede its children.");
            }
        }
    }

    private static @NotNull RigLoadResult failure(
            @NotNull String modelId,
            @Nullable String bone,
            @NotNull String message
    ) {
        return RigLoadResult.failure(List.of(RigDiagnostic.error(source(modelId), bone, message)));
    }

    /**
     * What a diagnostic from a derivation names instead of a file.
     *
     * <p>There is no file, and the model is the only thing anybody can go and look at.
     */
    private static @NotNull String source(@NotNull String modelId) {
        return "model '" + modelId + "'";
    }

    @Override
    public @NotNull String toString() {
        return "RigFactory[" + this.derived.size() + " derived rig(s) cached]";
    }
}
