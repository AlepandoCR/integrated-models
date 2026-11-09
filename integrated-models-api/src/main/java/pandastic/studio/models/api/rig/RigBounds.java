package pandastic.studio.models.api.rig;

/**
 * The structural limits a {@link Rig} is built within.
 *
 * <p>Configurable because the real limit is a bandwidth budget, not a number of bones: every simulated
 * bone is a transform on the wire for every viewer in range, so the ceiling that matters depends on how
 * many models a server runs, how close players get to them, and what the operator is willing to spend.
 * A figure chosen here would be wrong for both the server with one centrepiece model and the server with
 * two hundred decorations.
 *
 * @param maxBones hard ceiling on bones in a single rig, so a malformed rig cannot allocate unboundedly
 */
public record RigBounds(int maxBones) {

    public RigBounds {
        if (maxBones < 1) {
            // A rig with no bones is refused at build time anyway, so a ceiling below one would make
            // every rig on the server unloadable rather than merely limited.
            throw new IllegalArgumentException("maxBones must be at least 1 but was " + maxBones);
        }
    }
}
