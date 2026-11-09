package pandastic.studio.models.api.ai;

import org.bukkit.entity.Entity;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * The result of looking around, and how far the looking actually got.
 *
 * <h2>This record exists because of one platform fact</h2>
 * On a regionised server a creature <strong>genuinely cannot see across a region boundary</strong>.
 * Asking the world which entities are inside a box that reaches into a region this thread does not own
 * is not slow, not racy and not approximate — it is refused, because the chunks on the other side are
 * being ticked by another thread and reading them would be a data race the server has no way to
 * protect. Vanilla's own sensors live with the same limit: they scan the players the owning region
 * knows about, which is why a mob one region away from you is not looking at you and never will be.
 *
 * <p>So "I found nothing within sixteen blocks" and "I was only allowed to look four blocks" are
 * different answers, and a layer that returned a bare list would make them identical. That distinction
 * is the whole content of this type. A behaviour that treats a truncated scan as an empty one decides
 * the creature is alone at exactly the moment it is standing on a region boundary, which is to say at
 * the moment most likely to matter and least likely to be reproducible.
 *
 * <h2>What an implementation does about it</h2>
 * It shrinks rather than throws. The requested radius is reduced to the largest one wholly inside the
 * owning region, the scan runs at that radius, and {@link #complete()} says so. Shrinking is the only
 * behaviour that keeps a creature on a boundary working at all: refusing would make it blind, and
 * scanning anyway would make the server throw from inside the brain's tick.
 *
 * <h2>Subjects are platform objects, and that is safe here</h2>
 * Everything a scan returns is inside the region the calling thread owns — that is what the shrinking
 * guarantees — so reading a subject's position or state on this thread, in this tick, is defined.
 * Keeping one past the end of the body is not. Store what you learned in a memory, not the entity.
 *
 * @param subjects         what was found, inside {@link #scannedRadius()} of the creature. Immutable
 * @param requestedRadius  the radius the caller asked for, in blocks
 * @param scannedRadius    the radius actually searched, in blocks; at most {@link #requestedRadius()}
 * @param <SubjectType>    what was being looked for
 */
public record AiScan<SubjectType extends Entity>(
        @NotNull List<SubjectType> subjects,
        double requestedRadius,
        double scannedRadius
) {

    /**
     * Copies the subject list defensively and refuses an impossible radius pair.
     *
     * <p>The copy matters more than it looks: a scan is handed to a behaviour body that may keep it for
     * the length of the body, while the list the platform returned is one the platform may reuse.
     */
    public AiScan {
        subjects = List.copyOf(Objects.requireNonNull(subjects, "subjects"));
        if (!Double.isFinite(requestedRadius) || requestedRadius < 0.0D) {
            throw new IllegalArgumentException(
                    "requestedRadius must be finite and non-negative but was " + requestedRadius);
        }
        if (!Double.isFinite(scannedRadius) || scannedRadius < 0.0D) {
            throw new IllegalArgumentException(
                    "scannedRadius must be finite and non-negative but was " + scannedRadius);
        }
        if (scannedRadius > requestedRadius) {
            throw new IllegalArgumentException("scannedRadius " + scannedRadius
                    + " exceeds the requested " + requestedRadius
                    + "; a scan may be narrowed by the region it is in but never widened");
        }
    }

    /**
     * A scan that could not be performed at all.
     *
     * <p>Which happens when the calling thread does not own even the creature's own chunk — a window
     * that exists because an entity can change region between a task being scheduled and its body
     * running. Distinct from a complete scan that found nothing, and distinct from a truncated one.
     *
     * @param requestedRadius the radius that was asked for
     * @param <SubjectType>   what was being looked for
     * @return an empty, zero-radius, incomplete scan
     */
    @Contract(value = "_ -> new", pure = true)
    public static <SubjectType extends Entity> @NotNull AiScan<SubjectType> unavailable(
            double requestedRadius
    ) {
        return new AiScan<>(List.of(), requestedRadius, 0.0D);
    }

    /**
     * Whether the whole requested radius was searched.
     *
     * <p>Derived rather than stored, so it cannot disagree with the two radii. {@code false} means the
     * creature is near a region boundary and part of what it asked about belongs to another thread.
     *
     * @return whether the scan covered the requested radius
     */
    public boolean complete() {
        return this.scannedRadius >= this.requestedRadius;
    }

    /** Whether anything was found. Says nothing about whether the scan was complete. */
    public boolean isEmpty() {
        return this.subjects.isEmpty();
    }

    /** How many subjects were found. */
    public int size() {
        return this.subjects.size();
    }

    /**
     * Whether this scan proves nothing is within {@code radius}.
     *
     * <p>The question a behaviour actually wants answered, and the one that is easy to get wrong by
     * hand: an empty scan only proves emptiness out to {@link #scannedRadius()}. A behaviour deciding
     * "nobody is watching, go back to idle" should ask this rather than {@link #isEmpty()}, or it will
     * decide it on a boundary when it has not looked.
     *
     * @param radius the radius the caller wants to be sure about, in blocks
     * @return whether the scan was wide enough to be sure, and found nothing
     */
    public boolean provesEmptyWithin(double radius) {
        return this.subjects.isEmpty() && this.scannedRadius >= radius;
    }

    @Override
    public @NotNull String toString() {
        return "AiScan[" + this.subjects.size() + " within " + this.scannedRadius
                + (complete() ? " blocks]" : " of " + this.requestedRadius + " blocks requested]");
    }
}
