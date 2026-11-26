package pandastic.studio.models.api.vehicle;

import org.jetbrains.annotations.NotNull;

/**
 * What medium a vehicle moves through, and therefore which parts of the terrain it reacts to.
 *
 * <h2>Why this is an enum and not four interfaces</h2>
 * Because the four kinds do not differ in <em>what</em> can be asked of a vehicle — all four accelerate,
 * turn, carry riders and are stepped by the same control loop — they differ in which terms of the same
 * motion are meaningful. A boat has no step height because it cannot climb; a flyer has no ground clearance
 * because it is not on the ground; a submarine's buoyancy acts downward where a boat's pins it to a
 * surface. Four interfaces would mean four parallel binders, four solvers and four sets of tests to keep
 * in agreement, and a consumer choosing between them before it knows what it is building.
 *
 * <p>So the kind is <strong>data on the profile</strong>, and it selects which terms
 * {@link TerrainResponse} and the solver honour. Adding a fifth kind is one constant and the arms of the
 * switches that name it, all of which the compiler finds.
 *
 * <h2>What each kind means to the terrain layer</h2>
 * Stated here rather than in the solver because it is part of the published contract: a consumer picking
 * a kind is choosing these behaviours, and a kind whose meaning lives only in an implementation is a kind
 * whose meaning can change without the consumer being told.
 */
public enum VehicleKind {

    /**
     * Rides on solid ground, climbs steps, and follows the slope under it.
     *
     * <p>The only kind for which {@link TerrainResponse#stepHeightBlocks()} and
     * {@link TerrainResponse#slopePitchDegrees()} mean anything: it is the only one with a surface
     * beneath it to climb or to lie along.
     */
    GROUND(true, true, false, false),

    /**
     * Moves in free air, with no surface under it and no ceiling over it.
     *
     * <p>Banks into its turns like the others and <strong>ignores slope and steps entirely</strong>:
     * there is nothing below it to take an angle from. A flyer that followed terrain would pitch to
     * match the ground it is passing over, which reads as a bug rather than as flight. Its vertical
     * motion is the rider's to ask for, not the terrain's to impose.
     */
    FLYER(false, false, true, false),

    /**
     * Moves through water in three dimensions, pitching into its own climb and dive.
     *
     * <p>Like a flyer in that no surface constrains it, and unlike one in that it pitches toward where
     * it is <em>going</em> rather than staying level — a submarine nose-down while descending is the
     * whole visual language of the thing.
     */
    SUBMARINE(false, false, true, true),

    /**
     * Floats on a water surface, rolling with its turns and pitching with its own acceleration.
     *
     * <p>No step height, because a hull does not climb a block. Its pitch comes from acceleration
     * rather than from the ground: a boat squats at the stern when it opens up and noses down when it
     * slows, which is the one terrain-reactive term a surface vessel actually has.
     */
    BOAT(false, false, false, true);

    /** How many kinds there are. Structure, not a tunable. */
    public static final int COUNT = values().length;

    private final boolean climbsSteps;
    private final boolean followsSlope;
    private final boolean freeVertical;
    private final boolean buoyant;

    VehicleKind(boolean climbsSteps, boolean followsSlope, boolean freeVertical, boolean buoyant) {
        this.climbsSteps = climbsSteps;
        this.followsSlope = followsSlope;
        this.freeVertical = freeVertical;
        this.buoyant = buoyant;
    }

    /**
     * Whether a step up onto a higher block is something this kind does.
     *
     * <p>When {@code false}, {@link TerrainResponse#stepHeightBlocks()} is ignored rather than
     * rejected: a consumer reusing one terrain response across several kinds should not have to blank
     * the fields that do not apply, and a thrown exception there would make sharing a response
     * impossible for no gain.
     */
    public boolean climbsSteps() {
        return this.climbsSteps;
    }

    /** Whether this kind takes its pitch and roll from the surface beneath it. */
    public boolean followsSlope() {
        return this.followsSlope;
    }

    /** Whether the rider can ask for vertical motion directly, rather than only gaining it from terrain. */
    public boolean hasFreeVerticalMotion() {
        return this.freeVertical;
    }

    /** Whether the medium pushes back vertically — a surface to float on, or a column to hold depth in. */
    public boolean isBuoyant() {
        return this.buoyant;
    }

    /**
     * Whether this kind takes an angle from its own acceleration rather than from the ground.
     *
     * <p>True for the two water kinds and for a flyer, false for a ground vehicle — a car's pitch is the
     * hill it is on, and adding acceleration squat on top would fight it.
     */
    public boolean pitchesFromAcceleration() {
        return this != GROUND;
    }

    /** The lower-case name, which is what a rig file and a log line use. */
    public @NotNull String key() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
