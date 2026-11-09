package pandastic.studio.models.internal.config;

import pandastic.studio.core.service.api.config.ConfigFormat;
import pandastic.studio.core.service.api.config.ConfigValue;
import pandastic.studio.core.service.api.config.Configurable;

/**
 * Declaration of {@code binding.yml}: how often the region threads tell the simulation where a bound
 * model's owner is.
 *
 * <h2>Why this is its own file rather than a key in {@code simulation.yml}</h2>
 * Because it is the one rate in this project that is paid on a <em>region</em> thread. Everything in
 * {@code simulation.yml} describes the asynchronous loop — one task for the whole server, whose cost is
 * independent of how many models exist. This is a repeating task per bound model, scheduled on the
 * entity scheduler of the thing it samples, so its cost is the model count times the tick rate and an
 * operator tuning it is trading against the server's own tick budget rather than against a pool thread.
 * Putting the two in one file would invite the reading that they are the same kind of number.
 *
 * <h2>Not to be confused with its namesake</h2>
 * {@link pandastic.studio.models.internal.binding.BindingSettings} is a different type with the same
 * simple name: it is the assembled, already-validated bundle the binding layer reads when it dispatches
 * a sampler. This one is the <em>file</em> — the keys, the comments and the defaults. The project has
 * the same deliberate shadow for render, simulation, AI, vehicle and view; nothing outside this package
 * should need to name this interface, because what the rest of the plugin sees is {@link ConfigSnapshot}.
 *
 * @see ConfigMapper
 * @see ConfigSnapshot
 */
@Configurable(name = "BindingConfig", format = ConfigFormat.YAML)
public interface BindingSettings {

    /**
     * Ticks between two reads of a bound model's anchor.
     *
     * <p>One is the default and the finest value that means anything: an entity's position changes once
     * per tick, so sampling faster would read the same numbers again. It is also the finest the platform
     * will schedule — the entity scheduler floors any sub-tick delay at one tick — which is why
     * {@link ConfigMapper} refuses zero rather than accepting a figure that silently becomes one. That
     * floor is a platform constraint, not a preference.
     *
     * <p>Raising it is a real trade and the reason this is a key. Two ticks halves the per-model
     * region-thread cost and thirds of it go at three, at the price of a gait and a wind term that lag
     * the owner's real motion by that many ticks — the simulation keeps stepping at its own rate off a
     * sample that is older. Which answer is right depends on how many bound models a server runs, and
     * nothing in this codebase can derive that.
     *
     * @return the sampling period, in ticks
     */
    @ConfigValue(comment = "Ticks between two reads of a bound model's anchor. One is the floor and the default: an entity's position only changes once per tick, and the entity scheduler floors any sub-tick delay at one tick anyway. Raising it halves or thirds the per-model region-thread cost at the price of a gait that lags the owner by that many ticks.")
    default int samplePeriodTicks() {
        return 1;
    }
}
