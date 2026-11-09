package pandastic.studio.models.internal.config;

import pandastic.studio.core.service.api.config.ConfigFormat;
import pandastic.studio.core.service.api.config.ConfigValue;
import pandastic.studio.core.service.api.config.Configurable;

/**
 * Declaration of {@code view.yml}: which of a server's models a given player is sent the bones of.
 *
 * <h2>Why every one of these is operator-facing</h2>
 * Showing a model is the most expensive single operation this plugin performs. There is no resume — a
 * model that comes back has every bone re-sent at full state — so the only two numbers that matter about
 * this layer are how much it saves and how often it changes its mind, and both of them are a property of
 * the server rather than of the code. A lobby with forty models in one room and a survival world with
 * four models a chunk want opposite answers, and neither is derivable here.
 *
 * <h2>The shape the keys describe</h2>
 * Visibility is decided in two zones. Inside {@link #nearRadiusBlocks()} it is omnidirectional:
 * anything that close is shown however the viewer is facing. Outside it an angular test applies, and
 * that test is a <em>double</em> cone — a bowtie — because nothing in the protocol reports which camera
 * a player is using and the third-person front view renders what lies behind them. Both halves carry
 * hysteresis: a distance margin and a wider exit angle, so a model on a boundary is not re-sent every
 * pass. {@link pandastic.studio.models.internal.view.ViewSettings} documents the whole argument; this
 * file is where the numbers come from.
 *
 * <h2>Three of these keys are a statement about each other</h2>
 * None of them span another file, and all three are checked by {@link ConfigMapper} rather than left to
 * the value type's constructor, because a record can name a bad component but not the key it came from:
 * <ul>
 *   <li>{@link #nearRadiusBlocks()} has to stay inside {@link #viewRangeBlocks()}, or the omnidirectional
 *       zone covers everything the range admits and the angular test can never run;</li>
 *   <li>{@link #coneEnterDegrees()} has to stay below {@link #coneExitDegrees()}, or there is no angular
 *       hysteresis and a model sitting on the boundary is taken away and handed back on alternate
 *       passes;</li>
 *   <li>{@link #exitMarginBlocks()} has to exceed {@link #maxViewerSpeedBlocksPerTick()} times
 *       {@link #sampleIntervalTicks()}, or a viewer crosses the whole distance band between two samples
 *       and gets no distance hysteresis either. That one is the reason the assumed top speed is a key at
 *       all: nothing is clamped to it, it exists so the margin can be proven.</li>
 * </ul>
 *
 * <h2>Not to be confused with its namesake</h2>
 * {@link pandastic.studio.models.internal.view.ViewSettings} is a different type with the same simple
 * name: it is the assembled, already-validated bundle the culler reads once per pass. This one is the
 * <em>file</em> — the keys, the comments and the defaults. The project has the same deliberate shadow
 * for render, simulation, AI, vehicle and binding; nothing outside this package should need to name this
 * interface, because what the rest of the plugin sees is {@link ConfigSnapshot}.
 *
 * @see ConfigMapper
 * @see ConfigSnapshot
 */
@Configurable(name = "ViewConfig", format = ConfigFormat.YAML)
public interface ViewSettings {

    /**
     * Distance within which a model may be shown at all, in blocks.
     *
     * <p>The default is the 48 blocks a Paper server tracks a player at by default, which makes a worn
     * model disappear at about the distance the player wearing it does. Past that the client is already
     * being told less about the entity the model is anchored to than the model itself would say, so the
     * bones are detail nobody asked for — and the cost is per bone, not per model.
     *
     * <p>Inflated by {@link #cameraOffsetBlocks()} before it is compared against anything, because the
     * distance is measured from the player and it is the camera that sees.
     *
     * @return the view range, in blocks
     */
    @ConfigValue(comment = "Distance within which a model may be shown at all, in blocks. 48 is the range Paper tracks a player at by default, so a worn model goes away at about the distance its wearer does. The cost of raising it is per bone, not per model.")
    default double viewRangeBlocks() {
        return 48D;
    }

    /**
     * Distance within which direction is not considered at all, in blocks.
     *
     * <p>Derived rather than chosen. A model of the assumed radius subtends {@code asin(r / d)} at
     * distance {@code d}, and the angular test is only safe where that is small compared with the
     * angular hysteresis band: at 16 blocks a two-block radius subtends about 7 degrees against the
     * default band of 13, while at 8 blocks it subtends 14 and a single turn of the head can carry the
     * model from inside the enter angle to outside the exit angle. Every one of those flips re-sends
     * every bone.
     *
     * <p>Has to stay inside {@link #viewRangeBlocks()}; {@link ConfigMapper} refuses the pairing.
     *
     * @return the near radius, in blocks
     */
    @ConfigValue(comment = "Distance within which a model is shown however the viewer is facing, in blocks. Not a convenience: a near model subtends a large angle, so a small turn of the head would sweep it across the cone boundary and every flip re-sends every bone. Must stay inside viewRangeBlocks.")
    default double nearRadiusBlocks() {
        return 16D;
    }

    /**
     * Extra distance a viewer must travel beyond the range before the model is taken away, in blocks.
     *
     * <p>The distance half of the hysteresis. The default is twice the 4 blocks a viewer covers between
     * two samples at the assumed top speed, so crossing the band takes two full sampling intervals
     * rather than one — a margin that only just clears the reach is a margin that a fast viewer lands
     * inside of once and outside of next time.
     *
     * <p>Must exceed {@link #maxViewerSpeedBlocksPerTick()} times {@link #sampleIntervalTicks()};
     * {@link ConfigMapper} computes that reach and names all three keys when it does not.
     *
     * @return the exit margin, in blocks
     */
    @ConfigValue(comment = "Extra distance beyond the range a viewer must travel before the model is taken away, in blocks. Must exceed maxViewerSpeedBlocksPerTick times sampleIntervalTicks, or a viewer crosses the whole band between two samples and gets no hysteresis at all. The default is twice that reach.")
    default double exitMarginBlocks() {
        return 8D;
    }

    /**
     * Half-angle from the look axis at which a hidden model appears, in degrees.
     *
     * <p>The server cannot know a player's field of view — nothing in the client-information packet
     * carries it — so this has to cover a generous one. Minecraft's slider is the <em>vertical</em>
     * angle: at the default 70 on a 16:9 screen the horizontal half-angle is about 51 degrees, and at
     * 100 it is about 65. So the default shows a model before it can reach the edge of the screen for
     * every ordinary setting, and the keys that pay for that generosity are the near radius and the
     * range rather than this one.
     *
     * @return the enter half-angle, in degrees
     */
    @ConfigValue(comment = "Half-angle from the look axis at which a hidden model appears, in degrees. The FOV slider is the vertical angle: 70 on a 16:9 screen is about 51 degrees of horizontal half-angle and 100 is about 65, and the server is never told which the player chose. Must be under 90 and below coneExitDegrees.")
    default double coneEnterDegrees() {
        return 65D;
    }

    /**
     * Half-angle beyond which a shown model is taken away, in degrees.
     *
     * <p>The angular half of the hysteresis, and unlike the distance margin it cannot be <em>proven</em>
     * against anything: a player can spin a full turn in less than any sampling interval, so no finite
     * band survives a deliberate spin. What the 13 degrees above the enter angle buys is the stationary
     * case — a model sitting exactly on the boundary while its viewer holds still, which without
     * hysteresis is a full re-send of every bone on every pass.
     *
     * <p>The 90-degree ceiling is structural rather than a preference: at 90 the two halves of the
     * bowtie meet, every direction is admitted, and the angular test has stopped existing.
     *
     * @return the exit half-angle, in degrees
     */
    @ConfigValue(comment = "Half-angle beyond which a shown model is taken away, in degrees. The difference from coneEnterDegrees is the angular hysteresis. At or past 90 the two halves of the bowtie meet and every direction is admitted, which is the same as having no angular test.")
    default double coneExitDegrees() {
        return 78D;
    }

    /**
     * How far from the player a third-person camera can sit, in blocks.
     *
     * <p>The vanilla third-person camera sits about 4 blocks from the player, and every distance here is
     * measured from the player's eye, so the camera can be that much nearer a model than the measurement
     * says. Added to both the enter and the exit range — culling at the player's own distance would take
     * a model away while it was still on screen.
     *
     * <p>Zero is legal and means "assume first person", which is correct for a server that forces it.
     *
     * @return the camera offset, in blocks
     */
    @ConfigValue(comment = "How far from the player a third-person camera can sit, in blocks. Added to the range, because distance is measured from the player's eye and it is the camera that sees. The vanilla third-person camera is about 4 blocks out; 0 means assume first person.")
    default double cameraOffsetBlocks() {
        return 4D;
    }

    /**
     * Bounding radius assumed for a model that does not report one, in blocks.
     *
     * <p>The assumption errs toward showing on purpose. The radius enters the angular test as
     * {@code -radius * sin(theta)}, so a larger one admits a model whose centre is outside the cone but
     * whose edge is not; getting it too small culls a large model while part of it is still on screen,
     * and getting it too large costs a few models nobody was looking at. Two blocks covers a humanoid
     * rig or a small mount measured from its anchor, and a model that knows better reports its own.
     *
     * @return the assumed radius, in blocks
     */
    @ConfigValue(comment = "Bounding radius assumed for a model that does not report one, in blocks. Errs toward showing: too small culls a large model while part of it is still on screen, too large costs a few models nobody was looking at. A model that knows its own size reports it instead.")
    default double defaultModelRadiusBlocks() {
        return 2D;
    }

    /**
     * Fastest a viewer is assumed to travel, in blocks per tick.
     *
     * <p><strong>Nothing is clamped to this.</strong> It is the figure {@link #exitMarginBlocks()} is
     * proven against, so the only dangerous direction is too low: underestimate it and the margin stops
     * being a margin for exactly the players who move fast enough to notice. A sprinting player manages
     * about 0.28 blocks a tick and a galloping horse about 0.65; a firework-boosted elytra cruises near
     * 1.6 and a dive briefly exceeds that. Two covers all of them, and overestimating costs a few blocks
     * of margin and nothing else.
     *
     * @return the assumed top viewer speed, in blocks per tick
     */
    @ConfigValue(comment = "Fastest a viewer is assumed to travel, in blocks per tick. Nothing is clamped to it: it is what exitMarginBlocks is proven against, so too low is the dangerous direction. A sprint is 0.28, a galloping horse 0.65, a boosted elytra about 1.6.")
    default double maxViewerSpeedBlocksPerTick() {
        return 2D;
    }

    /**
     * Ticks between two re-samples of viewer positions.
     *
     * <p>Ten samples a second. The work is one position read per player, on that player's own region
     * thread, so the cost is small and linear in players; what it buys is a culling decision that is at
     * most two ticks stale.
     *
     * <p>It multiplies with {@link #maxViewerSpeedBlocksPerTick()} into the distance
     * {@link #exitMarginBlocks()} has to clear, which is why raising it is not free: a full second's
     * interval at the default assumed speed demands a margin of forty blocks, and {@link ConfigMapper}
     * will say so rather than let the hysteresis quietly stop working.
     *
     * @return the sampling interval, in ticks
     */
    @ConfigValue(comment = "Ticks between two re-samples of viewer positions. 20 is a second. One position read per player on that player's own region thread, so the cost is linear in players; raising it also raises the exit margin the validator demands, because a staler sample reaches further.")
    default int sampleIntervalTicks() {
        return 2;
    }

    /**
     * Whether a client's reported render distance caps the range.
     *
     * <p>A client that reports four chunks cannot draw past 64 blocks whatever the server sends, so
     * bones beyond its own render distance are packets with no picture. On by default because the saving
     * is free; turn it off for a server that wants a model visible regardless of what a player chose,
     * which is the one case where honouring the client's setting is the wrong answer.
     *
     * <p>A client that has not reported yet is assumed normal rather than blind, which is a property of
     * the culler and not of this key.
     *
     * @return whether to cap the range at what the client admits it can draw
     */
    @ConfigValue(comment = "Whether a client's reported render distance caps the view range. A client at 4 chunks cannot draw past 64 blocks whatever the server sends. Turn it off only for a server that wants a model visible regardless of what the player chose.")
    default boolean honourClientViewDistance() {
        return true;
    }

    /**
     * Most models that may be handed to one viewer in a single pass.
     *
     * <p>Showing a model re-sends every bone at full state, so a player teleporting into a crowded area
     * would otherwise receive every model there at once — several kilobytes each for a sixty-bone rig,
     * in one burst, on the thread that is also running the simulation. Four per pass at the default
     * sampling interval is forty models a second with the nearest served first, so the burst becomes a
     * drip that finishes before the player has turned around. A deferred show is picked up by the next
     * pass rather than lost.
     *
     * @return the spawn budget, in models per viewer per pass
     */
    @ConfigValue(comment = "Most models that may be handed to one viewer in a single pass, nearest first. Showing a model re-sends every bone at full state, so this is what turns a teleport into a crowd from one burst into a drip. Deferred shows are picked up by the next pass, not lost.")
    default int spawnBudgetPerViewer() {
        return 4;
    }
}
