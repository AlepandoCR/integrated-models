package pandastic.studio.models.internal.render;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.rig.Rig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Matches a {@link Rig}'s bone names against the bones a model actually has, and reports everything
 * wrong at once.
 *
 * <h2>All of them, not the first one</h2>
 * A rig is written by hand against a model somebody else exported. Renaming a group in Blockbench
 * breaks every rig that names it, and the person fixing it is working from an error message. Failing on
 * the first mismatch turns that into one edit-and-restart cycle per bone; listing all of them turns it
 * into one. Which is the whole reason this is a value type with a {@link #missing()} list rather than a
 * method that throws as soon as it finds a problem.
 *
 * <h2>Ambiguity is a harder failure than absence</h2>
 * Bone identity here is the <em>bare</em> name, matched case-insensitively, because renderer bone
 * identifiers carry behavioural tag prefixes and a rig keyed on the raw identifier breaks silently the
 * moment a rigger adds a tag. The cost of that forgiveness is that two differently-tagged groups can
 * collapse onto one rig name — and the renderer's lookup returns whichever it reaches first, so the rig
 * would drive an arbitrary one of them with no error anywhere. A missing bone does nothing; an ambiguous
 * bone does something wrong. Both are refused, and {@link #requireBindable()} says which is which.
 *
 * <h2>No renderer types</h2>
 * The model side enters as a plain list of names, so this runs identically against a live tracker's
 * bones, against a model definition at load time, and against a literal list in a test.
 *
 * <h2>The report's length is configuration, so it arrives with the request for one</h2>
 * How many names a failure may print is an operator's call rather than this class's — see
 * {@link RenderSettings#diagnosticBoneNameLimit()} — so {@link #requireBindable(RenderSettings)} takes
 * it. Resolution itself needs no settings at all: matching is exact, and only the message formatting has
 * a tunable in it.
 */
public final class RigBoneResolution {

    private final @NotNull String rigName;
    private final @NotNull String modelId;
    private final int[] modelIndices;
    private final @NotNull List<String> missing;
    private final @NotNull List<String> ambiguous;
    private final @NotNull List<String> modelBoneNames;

    private RigBoneResolution(
            @NotNull String rigName,
            @NotNull String modelId,
            int[] modelIndices,
            @NotNull List<String> missing,
            @NotNull List<String> ambiguous,
            @NotNull List<String> modelBoneNames
    ) {
        this.rigName = rigName;
        this.modelId = modelId;
        this.modelIndices = modelIndices;
        this.missing = List.copyOf(missing);
        this.ambiguous = List.copyOf(ambiguous);
        this.modelBoneNames = List.copyOf(modelBoneNames);
    }

    /**
     * Resolves every bone of {@code rig} against {@code modelBoneNames}.
     *
     * @param modelBoneNames the model's bone names in their own order; the index a rig bone resolves to
     *                       is an index into this list. Bare names are expected — see the class note on
     *                       why — but they need not be normalised, since both sides go through
     *                       {@link Rig#normalise}.
     */
    public static @NotNull RigBoneResolution resolve(
            @NotNull Rig rig,
            @NotNull List<String> modelBoneNames
    ) {
        final Map<String, Integer> indexByName = new HashMap<>();
        final Map<String, Integer> occurrences = new HashMap<>();
        for (int index = 0; index < modelBoneNames.size(); index++) {
            final String key = Rig.normalise(modelBoneNames.get(index));
            // putIfAbsent, not put: the renderer's own lookup returns the first match, so the index we
            // report has to be the one the renderer would pick.
            indexByName.putIfAbsent(key, index);
            occurrences.merge(key, 1, Integer::sum);
        }

        final int[] resolved = new int[rig.boneCount()];
        final List<String> missing = new ArrayList<>();
        final List<String> ambiguous = new ArrayList<>();
        for (int bone = 0; bone < rig.boneCount(); bone++) {
            final String name = rig.bone(bone).name();
            final Integer index = indexByName.get(Rig.normalise(name));
            if (index == null) {
                resolved[bone] = -1;
                missing.add(name);
                continue;
            }
            resolved[bone] = index;
            if (occurrences.getOrDefault(Rig.normalise(name), 0) > 1) {
                ambiguous.add(name);
            }
        }

        return new RigBoneResolution(
                rig.getName(), rig.getModelId(), resolved, missing, ambiguous, modelBoneNames);
    }

    /** Whether every rig bone found a model bone. */
    public boolean isComplete() {
        return this.missing.isEmpty();
    }

    /** Whether some rig bone matched more than one model bone. */
    public boolean isAmbiguous() {
        return !this.ambiguous.isEmpty();
    }

    /** Whether this rig can be bound at all: everything found, nothing ambiguous. */
    public boolean isBindable() {
        return isComplete() && !isAmbiguous();
    }

    /** Every rig bone name the model does not have, in rig order. */
    public @NotNull List<String> missing() {
        return this.missing;
    }

    /** Every rig bone name that matched more than one model bone, in rig order. */
    public @NotNull List<String> ambiguous() {
        return this.ambiguous;
    }

    /** The model bone index rig bone {@code rigBone} resolved to, or {@code -1} if it did not. */
    public int modelIndexOf(int rigBone) {
        return this.modelIndices[rigBone];
    }

    /**
     * Throws unless this rig can be bound, naming the rig, the model and every problem.
     *
     * @param settings supplies how many of the model's bone names the message may list before it
     *                 summarises the rest
     * @throws IllegalStateException if any bone is missing or ambiguous
     */
    public void requireBindable(@NotNull RenderSettings settings) {
        if (isBindable()) {
            return;
        }

        final StringBuilder message = new StringBuilder()
                .append("Rig '").append(this.rigName)
                .append("' cannot bind to model '").append(this.modelId).append("': ");
        if (!this.missing.isEmpty()) {
            message.append(this.missing.size()).append(" bone(s) are not in the model ")
                    .append(this.missing);
        }
        if (!this.missing.isEmpty() && !this.ambiguous.isEmpty()) {
            message.append("; ");
        }
        if (!this.ambiguous.isEmpty()) {
            message.append(this.ambiguous.size())
                    .append(" bone name(s) match more than one model bone, so the rig cannot say which"
                            + " one it means ")
                    .append(this.ambiguous);
        }
        message.append(". The model has ").append(this.modelBoneNames.size()).append(" bone(s): ")
                .append(summarise(this.modelBoneNames, settings.diagnosticBoneNameLimit()));
        throw new IllegalStateException(message.toString());
    }

    /** The model's bone names, truncated so a 256-bone model does not produce an unreadable log line. */
    private static @NotNull String summarise(@NotNull List<String> names, int limit) {
        if (names.size() <= limit) {
            return names.toString();
        }
        return names.subList(0, limit) + " (+" + (names.size() - limit) + " more)";
    }
}
