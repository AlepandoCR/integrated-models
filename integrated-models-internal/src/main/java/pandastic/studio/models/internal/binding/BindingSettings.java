package pandastic.studio.models.internal.binding;

/**
 * Everything the binding layer reads out of configuration, as one immutable snapshot.
 *
 * <p>Same shape and same contract as {@link pandastic.studio.models.internal.render.RenderSettings} and
 * {@link pandastic.studio.models.internal.sim.SimulationSettings}: a value a consumer reads once and
 * uses for a whole operation, never a setting per call site. No field has a default here, because a
 * default would be the hardcoded value this type exists to remove.
 *
 * @param samplePeriodTicks ticks between two reads of a model's anchor, from {@code samplePeriodTicks}
 *                          in {@code binding.yml}. Operator-facing because it is a direct trade of
 *                          region-thread cost against how stale the simulation's idea of the owner's
 *                          movement is, and the right answer depends on how many bound models a server
 *                          runs rather than on anything derivable here. One is the useful ceiling — an
 *                          entity's position only changes once per tick, so sampling faster reads the
 *                          same numbers again — and raising it above one halves or thirds the per-model
 *                          tick cost at the price of a gait and a wind term that lag the owner's real
 *                          motion by that many ticks
 */
public record BindingSettings(long samplePeriodTicks) {

    public BindingSettings {
        if (samplePeriodTicks < 1L) {
            // The platform treats any period below one tick as one, so a zero or negative figure does
            // not do what a file saying it would mean — it silently becomes one. Refusing it is what
            // makes the configured value and the scheduled value the same number.
            throw new IllegalArgumentException(
                    "samplePeriodTicks must be at least 1 but was " + samplePeriodTicks
                            + "; an anchor's position only changes once per tick, so there is nothing"
                            + " below one tick to sample");
        }
    }
}
