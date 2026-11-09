package pandastic.studio.models.internal.render;

/**
 * Everything the render layer reads out of configuration, as one immutable snapshot.
 *
 * <h2>Why a snapshot rather than a setting per call site</h2>
 * The emission path reads these values once per simulation step, and a step that read the configuration
 * reference several times could straddle a reload and mix old values with new ones. For the dirty
 * epsilon that is not a cosmetic inconsistency: {@link BoneDirtyTracker} resolves "my parent was
 * admitted, so my absolute transform changed" in a single ascending pass, so a pass whose first bones
 * were judged against one threshold and whose last bones were judged against another can admit a parent
 * and reject its child — which is the one combination the propagation exists to make impossible.
 * Reading one snapshot and using it for the whole pass removes the window instead of narrowing it.
 *
 * <p>Both fields are deliberately operator-facing. Neither has a default here: a default would be the
 * hardcoded value this type exists to remove, and a render layer that silently fell back to one would
 * hide a missing configuration key behind a model that merely looks slightly wrong.
 *
 * @param dirtyEpsilonRadians     angular change below which a bone counts as unmoved. This is the
 *                                bandwidth lever: at zero every bone whose angles differ at all is
 *                                re-emitted, and a value far above the motion in a rig emits nothing
 *                                after the first pass. It belongs in configuration because the right
 *                                figure is a function of how large the models are and how close players
 *                                get to them — the displacement a given angle produces scales with the
 *                                bone's lever arm, so a threshold invisible on a player-sized model is
 *                                a visible stutter on a thirty-block one
 * @param diagnosticBoneNameLimit how many model bone names a bind failure lists before it summarises the
 *                                rest. Operator-facing because it trades a log an operator can read
 *                                against a log that names the bone they are looking for: a console with
 *                                a short scrollback wants a handful, and somebody debugging a 256-bone
 *                                model against a file wants all of them
 */
public record RenderSettings(float dirtyEpsilonRadians, int diagnosticBoneNameLimit) {

    public RenderSettings {
        if (!Float.isFinite(dirtyEpsilonRadians) || dirtyEpsilonRadians < 0F) {
            // Zero is legitimate and documented — it means "emit every change" — but a negative or NaN
            // threshold makes every comparison in the tracker answer "unchanged", so a model would
            // freeze after its first emission with nothing in the log.
            throw new IllegalArgumentException(
                    "dirtyEpsilonRadians must be finite and at least 0 but was " + dirtyEpsilonRadians);
        }
        if (diagnosticBoneNameLimit < 1) {
            throw new IllegalArgumentException(
                    "diagnosticBoneNameLimit must be at least 1 but was " + diagnosticBoneNameLimit
                            + "; a failure message that names no bone at all cannot be acted on");
        }
    }
}
