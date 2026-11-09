package pandastic.studio.models.api.render;

/**
 * The interpolation window an {@link EmissionCadence} may ask the client for, in client ticks.
 *
 * <p>Configurable because the right window is a trade against a server's own jitter, which is a property
 * of that server rather than of the protocol. The window has to cover the gap between updates or the
 * tween finishes early and the bone sits still until the next packet — motion that stutters in exactly
 * the rhythm of the emission rate — and covering it with a margin costs a tick of visual lag. Where that
 * balance sits depends on how reliably the region threads keep up.
 *
 * <p>Worth knowing when choosing {@link #maxInterpolationTicks()}: the server re-sends an entity's
 * position unprompted every 60 ticks, restarting the client's countdown, so a window at or beyond that
 * can never complete. This type does not enforce that — it is a vanilla behaviour an operator may be
 * running a fork of — but a value near it buys nothing.
 *
 * @param minInterpolationTicks smallest window that still interpolates. Floored at {@code 1} because
 *                              zero means "snap", which is a different mode rather than a shorter window
 * @param maxInterpolationTicks largest window worth sending
 */
public record CadenceBounds(int minInterpolationTicks, int maxInterpolationTicks) {

    public CadenceBounds {
        if (minInterpolationTicks < 1) {
            throw new IllegalArgumentException(
                    "minInterpolationTicks must be at least 1 but was " + minInterpolationTicks
                            + ". A window of zero is a snap, not an interpolation.");
        }
        if (maxInterpolationTicks < minInterpolationTicks) {
            throw new IllegalArgumentException(
                    "Inverted range: minInterpolationTicks=" + minInterpolationTicks
                            + " > maxInterpolationTicks=" + maxInterpolationTicks);
        }
    }
}
