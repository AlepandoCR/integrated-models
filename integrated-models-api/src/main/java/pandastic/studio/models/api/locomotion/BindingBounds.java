package pandastic.studio.models.api.locomotion;

/**
 * The ranges a {@link LocomotionBinding} and a {@link GaitProfile} are accepted within.
 *
 * <p>One record for both because every number in it is a statement about <em>model scale</em>, and a
 * server's models all share one. Limb segments, stride length and the exaggeration ceiling are the three
 * places a gait meets the physical size of the thing it is driving, so splitting them across two
 * configuration blocks would let them drift apart — a stride range tuned for a giant alongside segment
 * lengths tuned for a doll is a binding that validates and then slides its feet.
 *
 * @param minSegmentLength shortest limb segment accepted, in blocks. Below this the two-bone solver's
 *                         law-of-cosines denominator approaches zero and the solved angle stops meaning
 *                         anything
 * @param maxSegmentLength longest limb segment accepted, in blocks. A sanity ceiling, not a geometric limit
 * @param minStrideLength  shortest stride accepted, in blocks. The phase advance divides by the stride,
 *                         so a stride approaching zero turns any movement at all into an unbounded number
 *                         of cycles per step
 * @param maxStrideLength  longest stride accepted, in blocks
 * @param maxScale         ceiling on every dimensionless gait scale. Exaggeration is a legitimate style —
 *                         a cartoon walk is a real walk with the swing amplitudes doubled — so this sits
 *                         above {@code 1}; past it the limb is passing through the body and the problem is
 *                         not tuning
 */
public record BindingBounds(
        float minSegmentLength,
        float maxSegmentLength,
        float minStrideLength,
        float maxStrideLength,
        float maxScale
) {

    public BindingBounds {
        requirePositive(minSegmentLength, "minSegmentLength");
        requirePositive(maxSegmentLength, "maxSegmentLength");
        requireOrdered(minSegmentLength, maxSegmentLength, "minSegmentLength", "maxSegmentLength");
        requirePositive(minStrideLength, "minStrideLength");
        requirePositive(maxStrideLength, "maxStrideLength");
        requireOrdered(minStrideLength, maxStrideLength, "minStrideLength", "maxStrideLength");
        // A ceiling of zero would force every scale to zero, i.e. refuse to animate anything at all.
        requirePositive(maxScale, "maxScale");
    }

    private static void requirePositive(float value, String name) {
        if (!Float.isFinite(value) || value <= 0F) {
            throw new IllegalArgumentException(name + " must be finite and positive but was " + value);
        }
    }

    private static void requireOrdered(float min, float max, String minName, String maxName) {
        if (min > max) {
            throw new IllegalArgumentException(
                    "Inverted range: " + minName + "=" + min + " > " + maxName + "=" + max
                            + ", which would refuse every value");
        }
    }
}
