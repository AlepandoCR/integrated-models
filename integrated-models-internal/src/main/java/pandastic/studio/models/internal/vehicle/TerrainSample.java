package pandastic.studio.models.internal.vehicle;

/**
 * One reading of what a vehicle is driving over: the surface under it, the surface one block ahead, and
 * whether either could be read at all.
 *
 * <h2>This is the impure half, and it is the whole of the impure half</h2>
 * Filling it reads blocks, which is platform work with a thread rule; everything that <em>decides</em>
 * what the vehicle does about it is {@link TerrainSolver}, which is arithmetic over the five numbers
 * below. The split is the same one {@link VehicleSample} and
 * {@link pandastic.studio.models.api.vehicle.VehicleSolver} already make, and it exists for the same
 * reason: the half that contains every judgement is the half that touches nothing, so it can be driven
 * from a test with a scripted hillside instead of a server with a real one.
 *
 * <h2>Mutable, and that is the point twice over</h2>
 * The argument {@link VehicleSample} makes, and then a second one on top of it. One of these is filled
 * for every vehicle on the server and read inside the step that filled it, so a record would allocate an
 * object per vehicle per tick for nothing. It is a reusable buffer owned by one vehicle and written only
 * on that vehicle's owning region thread; what leaves the step is the immutable {@link TerrainAttitude}
 * the solver produced from it.
 *
 * <p>The second reason is {@link TerrainSampleGate}: a buffer that survives the step is a buffer whose
 * last reading is <em>still there</em> on a step that chose not to take one, which is what lets a vehicle
 * answer the terrain without reading it. A fresh record per step would have made the cheap path — reuse
 * what was read — the expensive one. The one thing that must not be reused is the decision to climb; see
 * {@link #markReused()}.
 *
 * <h2>Why "ahead" is one block, and why that is not a tunable</h2>
 * Because the world is made of blocks and the question a step probe asks is whether the <em>next
 * column</em> is higher. The next column is one block away by construction — a probe of half a block
 * asks about the column the vehicle is already in, and a probe of two asks about a column it has to
 * cross another one to reach. So {@link #PROBE_RUN_BLOCKS} is structure, in the same category as a
 * vector stride: nothing reads it to decide how far the model leans, it only names the grid.
 *
 * <p>It also keeps the probe inside the owning region in every case that matters. A region boundary is a
 * chunk boundary, so a probe one block ahead crosses one at most once per sixteen blocks travelled — and
 * when it does, {@link #isAheadKnown()} is false for that single step rather than the read being made
 * illegally. See {@code EntityVehicleHost#probeTerrain} for the decision and the reasoning behind it.
 */
public final class TerrainSample {

    /**
     * How far ahead the step probe looks, in blocks.
     *
     * <p><strong>Structure, not a tunable.</strong> See the class note: it is the width of one column of
     * the world, which is also the run a one-block rise is a forty-five-degree grade over.
     */
    public static final float PROBE_RUN_BLOCKS = 1F;

    /** No surface was found in the scanned window. Not a coordinate, so no coordinate can collide. */
    public static final double NO_SURFACE = Double.NaN;

    private boolean known;
    private boolean reused;
    private boolean supported;
    private boolean aheadKnown;
    private double baseY;
    private double surfaceY;
    private double surfaceYAhead;

    /**
     * Records a reading. Called from the owning region thread and nowhere else.
     *
     * @param baseY         the vehicle's own world y at the moment of the reading
     * @param surfaceY      the top face of the surface under the vehicle, or {@link #NO_SURFACE}
     * @param aheadKnown    whether the column one block ahead could legally be read
     * @param surfaceYAhead the top face of the surface in that column, or {@link #NO_SURFACE}
     */
    public void set(double baseY, double surfaceY, boolean aheadKnown, double surfaceYAhead) {
        this.known = true;
        // A reading taken now, whatever this buffer held before. Clearing it here rather than at the call
        // site is what makes the flag impossible to leave set by accident.
        this.reused = false;
        this.baseY = baseY;
        this.surfaceY = surfaceY;
        this.supported = !Double.isNaN(surfaceY);
        // A column that was read and held nothing solid is still a column that was read; the distinction
        // the solver needs is "I have no answer" against "the answer is air", because the first must not
        // cancel a climb already in flight and the second must.
        this.aheadKnown = aheadKnown;
        this.surfaceYAhead = surfaceYAhead;
    }

    /**
     * Records that nothing could be read.
     *
     * <p>What a host with no world answers, and what the real host answers for a root entity that has
     * gone. Distinct from "read, and there is nothing under me": a vehicle over a chasm has a sample and
     * no support, and the two lead to different decisions.
     */
    public void unavailable() {
        this.known = false;
        this.reused = false;
        this.supported = false;
        this.aheadKnown = false;
        this.baseY = 0.0D;
        this.surfaceY = NO_SURFACE;
        this.surfaceYAhead = NO_SURFACE;
    }

    /**
     * Records that this step took no reading and is using the previous one.
     *
     * <p>What {@link TerrainSampleGate} suppressing a probe looks like from here: every number is left
     * exactly as the last reading set it, and the only thing that changes is that the sample now says it
     * is second-hand.
     *
     * <h2>Why that distinction has to exist, and what happens without it</h2>
     * Because one of the two decisions taken from a sample is <em>not</em> idempotent. The lean and the
     * pitch are a target the model blends toward, so acting on the same reading twice is acting on the
     * same hillside twice and is harmless. Arming a climb is not: it adds a rise to the vehicle's own
     * position, and a rise applied against a reading that is already in the past is a block of height the
     * vehicle got for free.
     *
     * <p>Concretely, and this was the bug the first draft of the deadband had. A parked vehicle facing a
     * one-block step arms a climb, rises over its duration, and finishes. Nothing about the reading has
     * changed, because it was taken before any of that happened — so the next step arms the same climb
     * again, and the step after that, and the vehicle ascends for ever, one block at a time, with every
     * individual decision correct. The deadband is what makes a reading survive long enough for that to
     * happen; the reuse flag is what stops it, by making an armed climb require a reading taken this step.
     * See {@link TerrainSolver} for the gate itself.
     */
    public void markReused() {
        // Deliberately not conditional on known: a sample that never held a reading and is now "reused"
        // is still an unknown sample, which is exactly what it should look like to the solver.
        this.reused = true;
    }

    /** Whether this sample holds a reading at all. */
    public boolean isKnown() {
        return this.known;
    }

    /**
     * Whether this sample's numbers were read on an earlier step rather than this one.
     *
     * <p>The one thing a terrain decision may not treat as equivalent to a fresh reading; see
     * {@link #markReused()} for which decision and why.
     */
    public boolean isReused() {
        return this.reused;
    }

    /** Whether a surface was found under the vehicle, within the scanned window. */
    public boolean isSupported() {
        return this.supported;
    }

    /** Whether the column one block ahead could legally be read on this step. */
    public boolean isAheadKnown() {
        return this.aheadKnown;
    }

    /** Whether a surface was found in the column one block ahead. */
    public boolean isAheadSupported() {
        return this.aheadKnown && !Double.isNaN(this.surfaceYAhead);
    }

    /** The vehicle's own world y when the reading was taken. */
    public double baseY() {
        return this.baseY;
    }

    /** The top face of the surface under the vehicle, or {@link #NO_SURFACE}. */
    public double surfaceY() {
        return this.surfaceY;
    }

    /** The top face of the surface one block ahead, or {@link #NO_SURFACE}. */
    public double surfaceYAhead() {
        return this.surfaceYAhead;
    }

    /**
     * How much higher the column ahead is than the one under the vehicle, in blocks.
     *
     * <p>Zero when either column is unknown or holds no surface, which is the answer that makes the
     * solver do nothing rather than the answer that makes it do something arbitrary.
     *
     * @return the rise, positive uphill and negative downhill
     */
    public double rise() {
        if (!this.supported || !isAheadSupported()) {
            return 0.0D;
        }
        return this.surfaceYAhead - this.surfaceY;
    }

    @Override
    public String toString() {
        return "TerrainSample[known=" + this.known + ", reused=" + this.reused
                + ", baseY=" + this.baseY
                + ", surfaceY=" + this.surfaceY + ", aheadKnown=" + this.aheadKnown
                + ", surfaceYAhead=" + this.surfaceYAhead + "]";
    }
}
