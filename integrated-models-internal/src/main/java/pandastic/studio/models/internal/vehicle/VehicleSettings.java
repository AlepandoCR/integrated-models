package pandastic.studio.models.internal.vehicle;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.vehicle.TerrainResponse;
import pandastic.studio.models.api.vehicle.VehicleBounds;
import pandastic.studio.models.api.vehicle.VehicleKind;

import java.util.Objects;

/**
 * Everything the vehicle layer reads out of configuration, as one immutable snapshot.
 *
 * <p>Same shape and same contract as {@link pandastic.studio.models.internal.render.RenderSettings},
 * {@link pandastic.studio.models.internal.sim.SimulationSettings} and
 * {@link pandastic.studio.models.internal.binding.BindingSettings}: a value a consumer reads once and
 * uses for a whole operation, never a setting per call site. No field has a default here, because a
 * default would be the hardcoded value this type exists to remove.
 *
 * <p>Not to be confused with {@link pandastic.studio.models.internal.config.VehicleSettings}, which is
 * the <em>declaration</em> of {@code vehicle.yml} — the keys, the comments and the defaults. This is the
 * assembled, already-validated bundle. The project has the same deliberate shadow for render, simulation
 * and AI.
 *
 * @param bounds                 the ranges a profile is accepted within. Held rather than read per
 *                               creation because a profile's validation has to be against one coherent
 *                               set of ranges, and the file could reload between two components of it
 * @param controlPeriodTicks     ticks between two decisions about where a vehicle is. Read when the
 *                               control task is dispatched and never inside it, for the reason
 *                               {@link pandastic.studio.models.internal.binding.BindingSettingsSource}
 *                               gives: a repeating task's rate belongs to the platform
 * @param maxStepTicks           longest gap one step will integrate across. The guard on a server that
 *                               stalled — integrating the whole gap faithfully would move a vehicle
 *                               through whatever was in the way
 * @param maxLocalStepBlocks     furthest a single in-place position write may reach, above which a step
 *                               goes through the asynchronous teleport
 * @param restSpeedBlocksPerTick speed below which a coasting vehicle is snapped to a stop, so that
 *                               friction's geometric tail stops costing a position write per tick
 * @param seats                  everything the seating layer reads: the carrier, the thresholds its
 *                               follow step is decided against, and every label of the seat chart. Here
 *                               rather than beside this record so that one snapshot read per control step
 *                               covers the loop and the seats alike — a step that read them separately
 *                               could straddle a reload and mix two generations
 * @param terrainDefaults        the terrain response a vehicle inherits when its own profile declares
 *                               none, one per {@link VehicleKind}. Here for the same reason {@code seats}
 *                               is: one snapshot read covers the whole decision. Note that the
 *                               <em>ceilings</em> these are checked against are not here — they live in
 *                               {@link VehicleBounds#terrain()} inside {@code bounds}, because what a
 *                               profile is validated against is the api's statement and not this
 *                               record's
 * @param terrainSampling        how often a vehicle may read the blocks under it. Here rather than
 *                               fetched where the probe is decided, for the straddling reason every
 *                               other component is here: a step that read the deadband from one
 *                               generation and the interval from the next would suppress a reading
 *                               against a rule no file describes
 */
public record VehicleSettings(
        @NotNull VehicleBounds bounds,
        long controlPeriodTicks,
        float maxStepTicks,
        float maxLocalStepBlocks,
        float restSpeedBlocksPerTick,
        @NotNull SeatSettings seats,
        @NotNull TerrainDefaults terrainDefaults,
        @NotNull TerrainSampling terrainSampling
) {

    public VehicleSettings {
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(seats, "seats");
        Objects.requireNonNull(terrainDefaults, "terrainDefaults");
        Objects.requireNonNull(terrainSampling, "terrainSampling");
        if (controlPeriodTicks < 1L) {
            // The platform treats any period below one tick as one, so a zero or negative figure does
            // not do what a file saying it would mean — it silently becomes one. Refusing it is what
            // makes the configured value and the scheduled value the same number.
            throw new IllegalArgumentException(
                    "controlPeriodTicks must be at least 1 but was " + controlPeriodTicks
                            + "; rider input only changes once per client tick, so there is nothing"
                            + " below one tick to decide on");
        }
        requirePositive(maxStepTicks, "maxStepTicks");
        if (maxStepTicks < controlPeriodTicks) {
            // Not a tidiness check. With the ceiling below the period, every ordinary step is clamped,
            // so every vehicle on the server covers less ground than its profile says — silently, with
            // nothing wrong except the speed.
            throw new IllegalArgumentException("maxStepTicks must be at least controlPeriodTicks, which"
                    + " is " + controlPeriodTicks + ", but was " + maxStepTicks
                    + "; otherwise every ordinary step is clamped and every vehicle runs slow");
        }
        requirePositive(maxLocalStepBlocks, "maxLocalStepBlocks");
        requirePositive(restSpeedBlocksPerTick, "restSpeedBlocksPerTick");
    }

    /**
     * The bundle in the shape this record had before the terrain layer: the same six arguments, in the
     * same order, with {@link TerrainDefaults#INERT} for the seventh and
     * {@link TerrainSampling#EVERY_STEP} for the eighth.
     *
     * <p>For a caller that has nothing to say about terrain — the same compatibility the api side gives
     * through {@code VehicleProfile.withoutTerrain}. Both substituted values are identities rather than
     * chosen tunings: every kind inherits {@link TerrainResponse#NONE} and every step may take a reading,
     * so a vehicle built against these settings behaves exactly as it did before either existed. Both are
     * shared constants, so two bundles built this way agree by identity.
     *
     * @param bounds                 the ranges a profile is accepted within
     * @param controlPeriodTicks     ticks between two decisions about where a vehicle is
     * @param maxStepTicks           longest gap one step will integrate across
     * @param maxLocalStepBlocks     furthest a single in-place position write may reach
     * @param restSpeedBlocksPerTick speed below which a coasting vehicle is snapped to a stop
     * @param seats                  everything the seating layer reads
     */
    public VehicleSettings(
            @NotNull VehicleBounds bounds,
            long controlPeriodTicks,
            float maxStepTicks,
            float maxLocalStepBlocks,
            float restSpeedBlocksPerTick,
            @NotNull SeatSettings seats
    ) {
        this(bounds, controlPeriodTicks, maxStepTicks, maxLocalStepBlocks, restSpeedBlocksPerTick,
                seats, TerrainDefaults.INERT, TerrainSampling.EVERY_STEP);
    }

    /**
     * The bundle in the shape it had before the sampling keys: the same seven arguments, with
     * {@link TerrainSampling#EVERY_STEP} for the eighth.
     *
     * <p>For a caller that describes a terrain response and has nothing to say about how often it is
     * read, which is every caller that existed when the probe happened on every step. The substituted
     * value is the identity, so such a caller gets the behaviour it was written against.
     *
     * @param bounds                 the ranges a profile is accepted within
     * @param controlPeriodTicks     ticks between two decisions about where a vehicle is
     * @param maxStepTicks           longest gap one step will integrate across
     * @param maxLocalStepBlocks     furthest a single in-place position write may reach
     * @param restSpeedBlocksPerTick speed below which a coasting vehicle is snapped to a stop
     * @param seats                  everything the seating layer reads
     * @param terrainDefaults        the terrain response each kind inherits
     */
    public VehicleSettings(
            @NotNull VehicleBounds bounds,
            long controlPeriodTicks,
            float maxStepTicks,
            float maxLocalStepBlocks,
            float restSpeedBlocksPerTick,
            @NotNull SeatSettings seats,
            @NotNull TerrainDefaults terrainDefaults
    ) {
        this(bounds, controlPeriodTicks, maxStepTicks, maxLocalStepBlocks, restSpeedBlocksPerTick,
                seats, terrainDefaults, TerrainSampling.EVERY_STEP);
    }

    private static void requirePositive(float value, String name) {
        if (!Float.isFinite(value) || value <= 0F) {
            throw new IllegalArgumentException(name + " must be finite and positive but was " + value);
        }
    }

    /**
     * The terrain response each {@link VehicleKind} inherits when a profile declares none of its own.
     *
     * <h2>Why four components rather than a map</h2>
     * Because the question asked of this type is always "what does <em>this</em> kind do", and a map
     * answers it with a lookup that can miss. Four components make the kinds exhaustive at compile time:
     * {@link #forKind} switches over the enum, so adding a fifth kind is a compile error here rather than
     * a {@code null} at the point a vehicle of that kind is bound.
     *
     * <h2>Why a default exists at all</h2>
     * So that retuning every ground vehicle on a server is an edit to {@code vehicle.yml} rather than a
     * rebuild of the plugin that ships them. A profile that <em>does</em> declare a response keeps it: a
     * statement about one vehicle belongs to whoever wrote that vehicle, and these are only what a plugin
     * with no opinion inherits.
     *
     * <p>Each response here is still subject to {@link VehicleBounds#terrain()} — the ceilings are the
     * operator's other half of the same file, and {@code ConfigMapper} refuses a reload whose defaults
     * those ceilings would reject.
     *
     * @param ground    what a {@link VehicleKind#GROUND} vehicle inherits
     * @param flyer     what a {@link VehicleKind#FLYER} inherits
     * @param submarine what a {@link VehicleKind#SUBMARINE} inherits
     * @param boat      what a {@link VehicleKind#BOAT} inherits
     */
    public record TerrainDefaults(
            @NotNull TerrainResponse ground,
            @NotNull TerrainResponse flyer,
            @NotNull TerrainResponse submarine,
            @NotNull TerrainResponse boat
    ) {

        /**
         * No kind reacts to terrain at all.
         *
         * <p>The identity value, not a tuning: every kind inherits {@link TerrainResponse#NONE}, which is
         * what every vehicle did before this type existed. It is what the six-argument constructor
         * supplies, and it is a shared constant so that identity comparison across two bundles answers as
         * expected.
         */
        public static final @NotNull TerrainDefaults INERT = new TerrainDefaults(
                TerrainResponse.NONE, TerrainResponse.NONE, TerrainResponse.NONE, TerrainResponse.NONE);

        public TerrainDefaults {
            Objects.requireNonNull(ground, "ground");
            Objects.requireNonNull(flyer, "flyer");
            Objects.requireNonNull(submarine, "submarine");
            Objects.requireNonNull(boat, "boat");
        }

        /**
         * What {@code kind} inherits.
         *
         * @param kind the kind of vehicle being described
         * @return its default response, never {@code null}
         */
        @Contract(pure = true)
        public @NotNull TerrainResponse forKind(@NotNull VehicleKind kind) {
            Objects.requireNonNull(kind, "kind");
            return switch (kind) {
                case GROUND -> this.ground;
                case FLYER -> this.flyer;
                case SUBMARINE -> this.submarine;
                case BOAT -> this.boat;
            };
        }

        /** Whether no kind's default can move a model, which is what {@link #INERT} is. */
        @Contract(pure = true)
        public boolean isInert() {
            return this.ground.isInert() && this.flyer.isInert()
                    && this.submarine.isInert() && this.boat.isInert();
        }
    }
}
