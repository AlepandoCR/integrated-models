package pandastic.studio.models.internal.sim;

/**
 * Everything the clip-driving layer reads out of configuration, as one immutable snapshot.
 *
 * <h2>Why a model's own clips are driven at all</h2>
 * Most models arrive with animations in them, hand-authored by whoever built the model, and they are
 * better than anything {@link pandastic.studio.models.internal.locomotion.WalkCycleSolver} produces — its
 * cosmetic constants were chosen rather than measured and have never been seen on a running server. So a
 * rig that names clips ({@link pandastic.studio.models.api.rig.RigClips}) gets its own walk played for it
 * instead of a synthesised gait, and the synthesised gait stays for the models that ship nothing.
 *
 * <h2>Read once per decision, never per value</h2>
 * {@link ClipStage#advance} takes exactly one snapshot and uses it for the whole decision, for the same
 * reason every other layer here does: a reload landing between the threshold test and the hold test would
 * let one decision be made against two generations of configuration, and the hysteresis the thresholds
 * exist to provide would be the thing that broke.
 *
 * <h2>Carried whole by the configuration snapshot</h2>
 * Like {@code ViewSettings} and {@code BindingSettings}, this type is stored in
 * {@link pandastic.studio.models.internal.config.ConfigSnapshot} as itself rather than being assembled
 * from several areas, so {@code ConfigService::clips} satisfies {@link ClipSettingsSource} directly,
 * allocation-free, and with a stable identity. Nothing compares it by identity today, but the guarantee
 * is free and the alternative is the hazard {@code CLAUDE.md} opens its configuration section with.
 *
 * @param enabled                     whether a rig's own clips are driven at all. Operator-facing because
 *                                    a server that would rather see the synthesised gait, or that is
 *                                    debugging which of the two writers is moving a bone, needs to turn
 *                                    this off without editing every rig file
 * @param walkSpeedBlocksPerTick      horizontal speed at or above which the walk clip plays. Below it the
 *                                    model is standing — or being nudged by collision resolution, which
 *                                    is why this sits above zero rather than at it
 * @param runSpeedBlocksPerTick       horizontal speed at or above which the run clip plays. Must sit
 *                                    strictly above the walk threshold
 * @param speedHysteresisBlocksPerTick how much further past a threshold the speed must go to <em>enter</em>
 *                                    a faster state than to stay in it, and how far back to leave it. The
 *                                    whole reason this exists: an owner moving at exactly walking pace
 *                                    crosses the threshold several times a second, and a clip restarted
 *                                    several times a second is a model that twitches rather than walks
 * @param minimumHoldSeconds          how long a chosen state is held before another may replace it. The
 *                                    second half of the anti-flap guard, and the one that covers a speed
 *                                    oscillating by more than the hysteresis band — a stutter-stepping
 *                                    player, say
 * @param blendInTicks                ticks the renderer blends into a clip over. Zero snaps
 * @param blendOutTicks               ticks the renderer blends out of a clip over. Zero snaps
 */
public record ClipSettings(
        boolean enabled,
        float walkSpeedBlocksPerTick,
        float runSpeedBlocksPerTick,
        float speedHysteresisBlocksPerTick,
        float minimumHoldSeconds,
        int blendInTicks,
        int blendOutTicks
) {

    public ClipSettings {
        requireFinite(walkSpeedBlocksPerTick, "walkSpeedBlocksPerTick");
        requireFinite(runSpeedBlocksPerTick, "runSpeedBlocksPerTick");
        requireFinite(speedHysteresisBlocksPerTick, "speedHysteresisBlocksPerTick");
        requireFinite(minimumHoldSeconds, "minimumHoldSeconds");
        if (!(walkSpeedBlocksPerTick < runSpeedBlocksPerTick)) {
            throw new IllegalArgumentException(
                    "walkSpeedBlocksPerTick (" + walkSpeedBlocksPerTick + ") must sit strictly below"
                            + " runSpeedBlocksPerTick (" + runSpeedBlocksPerTick + "); equal thresholds"
                            + " leave no speed at which the walk clip plays");
        }
        if (blendInTicks < 0 || blendOutTicks < 0) {
            throw new IllegalArgumentException(
                    "Blend durations cannot be negative but were " + blendInTicks + " and "
                            + blendOutTicks);
        }
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value) || value < 0F) {
            throw new IllegalArgumentException(
                    name + " must be finite and at least 0 but was " + value);
        }
    }
}
