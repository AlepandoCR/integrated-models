package pandastic.studio.models.internal.view;

import org.jetbrains.annotations.NotNull;

/**
 * What governs the culling decision. Every value is configuration; none of it is a literal anywhere.
 *
 * <h2>Two zones, because one test cannot serve both</h2>
 * Close in, visibility is <strong>omnidirectional</strong>: anything within {@link #nearRadiusBlocks()}
 * is shown regardless of where the viewer is looking. Further out, an angular test applies.
 *
 * <p>The split is not a refinement, it is what makes the angular test usable at all. A near model
 * subtends a large angle, so a small turn of the head sweeps it across a cone boundary — and because
 * showing a model again means re-sending every bone, a model flickering on a cone edge is the most
 * expensive thing this layer could possibly do. Near models are also the ones a third-person camera
 * offset displaces most, relative to their distance. Both problems vanish by not testing direction
 * there, and the cost is bounded: a near model is one a viewer almost certainly can see anyway.
 *
 * <h2>The angular test is a double cone, and that is the camera-mode answer</h2>
 * The server cannot know which camera a player is using. Nothing in the protocol carries it — the
 * client-information packet reports render distance, locale, main hand, skin parts and chat
 * preferences, and no camera or perspective type exists in the API at all.
 *
 * <p>That matters because the three modes do not share a forward direction. In third-person
 * <em>front</em> view the camera sits ahead of the player looking <em>back</em>, so what it renders is
 * what lies behind them. A single forward cone would cull precisely what that player is looking at.
 *
 * <p>So the test admits a model that is near the look axis in <em>either</em> direction — a bowtie
 * rather than a cone, which is why {@link #isWithinViewCone} compares the <em>magnitude</em> of the
 * axis projection and ignores its sign. What that culls is the band perpendicular to the look axis,
 * which no camera mode can see. It is a smaller saving than a one-sided cone would appear to offer, and
 * it is the saving that is actually correct.
 *
 * <h2>Nothing here needs the main thread</h2>
 * Every value is a number and every test is arithmetic over a published snapshot, so a whole pass runs
 * on the simulation thread with no scheduling hop. The only region-thread work in the layer is reading
 * a player's own position, which is the one thing that cannot legally happen anywhere else.
 *
 * @param viewRangeBlocks             distance within which a model may be shown at all
 * @param nearRadiusBlocks            distance within which direction is not considered
 * @param exitMarginBlocks            extra distance a viewer must travel beyond the range before the
 *                                    model is taken away. The distance hysteresis band
 * @param coneEnterDegrees            half-angle from the look axis at which a hidden model appears
 * @param coneExitDegrees             half-angle beyond which a shown model is taken away. Must exceed
 *                                    {@code coneEnterDegrees}; the difference is the angular hysteresis
 * @param cameraOffsetBlocks          how far from the player a third-person camera can sit. Added to
 *                                    the range, because the camera can be nearer the model than the
 *                                    player is
 * @param defaultModelRadiusBlocks    bounding radius assumed for a model that does not report one, so
 *                                    a large model is not culled while part of it is still on screen
 * @param maxViewerSpeedBlocksPerTick fastest a viewer is assumed to travel, used to prove the margin
 *                                    survives the sampling interval
 * @param sampleIntervalTicks         how often viewer positions are re-sampled
 * @param honourClientViewDistance    whether a client's reported render distance caps the range
 * @param spawnBudgetPerViewer        most models that may be handed to one viewer in a single pass
 */
public record ViewSettings(
        double viewRangeBlocks,
        double nearRadiusBlocks,
        double exitMarginBlocks,
        double coneEnterDegrees,
        double coneExitDegrees,
        double cameraOffsetBlocks,
        double defaultModelRadiusBlocks,
        double maxViewerSpeedBlocksPerTick,
        int sampleIntervalTicks,
        boolean honourClientViewDistance,
        int spawnBudgetPerViewer
) {

    /**
     * Server ticks per second.
     *
     * <p>A protocol fact rather than a tunable: it is the unit the sampling interval is expressed in,
     * and the same constant the emission cadence documents.
     */
    private static final double TICKS_PER_SECOND = 20D;

    /** Blocks per chunk, for turning a client's reported render distance into a distance. */
    private static final int BLOCKS_PER_CHUNK = 16;

    /** A half-angle at or beyond this admits every direction, so the angular test is pointless. */
    private static final double MAX_USEFUL_HALF_ANGLE_DEGREES = 90D;

    public ViewSettings {
        requirePositive(viewRangeBlocks, "viewRangeBlocks");
        requirePositive(nearRadiusBlocks, "nearRadiusBlocks");
        requirePositive(exitMarginBlocks, "exitMarginBlocks");
        requirePositive(maxViewerSpeedBlocksPerTick, "maxViewerSpeedBlocksPerTick");
        requireNotNegative(cameraOffsetBlocks, "cameraOffsetBlocks");
        requireNotNegative(defaultModelRadiusBlocks, "defaultModelRadiusBlocks");

        requireHalfAngle(coneEnterDegrees, "coneEnterDegrees");
        requireHalfAngle(coneExitDegrees, "coneExitDegrees");
        if (coneExitDegrees <= coneEnterDegrees) {
            throw new IllegalArgumentException(
                    "coneExitDegrees (" + coneExitDegrees + ") must exceed coneEnterDegrees ("
                            + coneEnterDegrees + "), or a model on the boundary is re-sent every pass");
        }

        if (sampleIntervalTicks < 1) {
            throw new IllegalArgumentException(
                    "sampleIntervalTicks must be at least 1, but was " + sampleIntervalTicks);
        }
        if (spawnBudgetPerViewer < 1) {
            throw new IllegalArgumentException(
                    "spawnBudgetPerViewer must be at least 1, but was " + spawnBudgetPerViewer);
        }
        if (nearRadiusBlocks >= viewRangeBlocks) {
            throw new IllegalArgumentException(
                    "nearRadiusBlocks (" + nearRadiusBlocks + ") must be inside viewRangeBlocks ("
                            + viewRangeBlocks + "), or the angular test can never apply");
        }

        // The anti-flap guarantee, and the one relationship between these values that matters. A
        // viewer who can cross the whole hysteresis band between two samples gets no hysteresis at
        // all: inside on one pass, outside on the next, and every flip costs a full respawn of every
        // bone. Refusing the combination beats shipping a configuration whose only symptom is a model
        // that strobes while somebody runs past it.
        final double reach = maxViewerSpeedBlocksPerTick * sampleIntervalTicks;
        if (exitMarginBlocks <= reach) {
            throw new IllegalArgumentException(
                    "exitMarginBlocks (" + exitMarginBlocks + ") must exceed the " + reach
                            + " blocks a viewer covers in " + sampleIntervalTicks + " tick(s) at "
                            + maxViewerSpeedBlocksPerTick
                            + " blocks/tick, or the hysteresis band can be crossed between samples");
        }
    }

    private static void requirePositive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0D) {
            throw new IllegalArgumentException(name + " must be finite and positive, but was " + value);
        }
    }

    private static void requireNotNegative(double value, String name) {
        if (!Double.isFinite(value) || value < 0D) {
            throw new IllegalArgumentException(
                    name + " must be finite and not negative, but was " + value);
        }
    }

    private static void requireHalfAngle(double degrees, String name) {
        if (!Double.isFinite(degrees) || degrees <= 0D
                || degrees >= MAX_USEFUL_HALF_ANGLE_DEGREES) {
            throw new IllegalArgumentException(
                    name + " must be within (0, " + MAX_USEFUL_HALF_ANGLE_DEGREES
                            + ") degrees, but was " + degrees
                            + ". At or past 90 the two halves of the bowtie meet and every direction"
                            + " is admitted, which is the same as having no angular test.");
        }
    }

    /** The interval between samples, in seconds. */
    public double sampleIntervalSeconds() {
        return this.sampleIntervalTicks / TICKS_PER_SECOND;
    }

    /** Squared near radius, so the omnidirectional test needs no square root. */
    public double nearRadiusSquared() {
        return this.nearRadiusBlocks * this.nearRadiusBlocks;
    }

    /**
     * The squared distance within which a hidden model becomes eligible for {@code viewer}.
     *
     * <p>Inflated by {@link #cameraOffsetBlocks()}, because the measurement is from the player and the
     * camera is what sees: in a third-person mode the camera can be that much nearer the model, so
     * culling at the player's own distance would take a model away while it was still on screen.
     *
     * <p>Then capped by what the client admits it can draw, when configured to care.
     */
    public double enterRangeSquared(@NotNull ViewerSnapshot viewer) {
        return square(effectiveRange(viewer, this.viewRangeBlocks + this.cameraOffsetBlocks));
    }

    /** The squared distance beyond which a shown model is taken away from {@code viewer}. */
    public double exitRangeSquared(@NotNull ViewerSnapshot viewer) {
        return square(effectiveRange(
                viewer, this.viewRangeBlocks + this.cameraOffsetBlocks + this.exitMarginBlocks));
    }

    /**
     * Whether a bounding sphere is inside the view bowtie.
     *
     * <p>The test is the standard sphere-against-cone one, rearranged so the hot path has no
     * trigonometry: a sphere of radius {@code r} at distance {@code d} whose centre projects
     * {@code p} along the axis is inside a cone of half-angle θ when
     * {@code |p| >= d·cos θ − r·sin θ}. Both θ terms are constants of this record, so a pass costs one
     * multiply each.
     *
     * <p>The magnitude of {@code p} rather than its value is what makes this a bowtie instead of a
     * cone, and that is the whole reason this works without knowing the camera mode — see the class
     * documentation.
     *
     * <p>Note the right-hand side goes negative for a sphere large enough relative to its distance, at
     * which point the comparison is true for every direction. That is correct: such a sphere straddles
     * the axis plane and is visible whichever way the viewer faces.
     *
     * @param axisProjection signed distance along the look axis, from
     *                       {@link ViewerSnapshot#axisProjectionTo}
     * @param distance       distance from the eye to the sphere's centre
     * @param radius         the sphere's radius
     * @param alreadyShown   whether the model is currently shown, which selects the wider exit angle
     */
    public boolean isWithinViewCone(
            double axisProjection,
            double distance,
            double radius,
            boolean alreadyShown
    ) {
        final double cos = alreadyShown ? cosExit() : cosEnter();
        final double sin = alreadyShown ? sinExit() : sinEnter();
        return Math.abs(axisProjection) >= distance * cos - radius * sin;
    }

    /** Cosine of the enter half-angle. */
    public double cosEnter() {
        return Math.cos(Math.toRadians(this.coneEnterDegrees));
    }

    /** Sine of the enter half-angle. */
    public double sinEnter() {
        return Math.sin(Math.toRadians(this.coneEnterDegrees));
    }

    /** Cosine of the exit half-angle. */
    public double cosExit() {
        return Math.cos(Math.toRadians(this.coneExitDegrees));
    }

    /** Sine of the exit half-angle. */
    public double sinExit() {
        return Math.sin(Math.toRadians(this.coneExitDegrees));
    }

    private double effectiveRange(@NotNull ViewerSnapshot viewer, double configured) {
        if (!this.honourClientViewDistance) {
            return configured;
        }
        final double clientReach = (double) viewer.viewDistanceChunks() * BLOCKS_PER_CHUNK;
        // A client that has not reported yet is assumed normal rather than blind.
        return clientReach <= 0D ? configured : Math.min(configured, clientReach);
    }

    private static double square(double value) {
        return value * value;
    }
}
