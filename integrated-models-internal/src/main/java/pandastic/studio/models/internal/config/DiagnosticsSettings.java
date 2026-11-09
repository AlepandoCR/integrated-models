package pandastic.studio.models.internal.config;

import org.jetbrains.annotations.NotNull;
import pandastic.studio.core.service.api.config.ConfigFormat;
import pandastic.studio.core.service.api.config.ConfigValue;
import pandastic.studio.core.service.api.config.Configurable;
import pandastic.studio.models.internal.rig.RigDiagnostic;

/**
 * Declaration of {@code diagnostics.yml}: what this plugin is allowed to say about its own files.
 *
 * <p>Three keys, and each one exists because something in this codebase deliberately returns its
 * complaints as data instead of logging them. The rig loader accumulates {@link RigDiagnostic}s and
 * hands them back with the result, precisely so that it needs no logger and no server to be tested;
 * {@link ConfigService} does the same with its own reload diagnostics. That design leaves an open
 * question the loader cannot answer — whether anyone is listening — and this file answers it.
 *
 * <p>It is deliberately small. Every toggle here is a toggle an operator can reach for when the
 * console is too loud or too quiet; anything that changes <em>behaviour</em> rather than visibility
 * belongs in one of the other four files.
 *
 * @see ConfigMapper
 * @see ConfigSnapshot
 */
@Configurable(name = "DiagnosticsConfig", format = ConfigFormat.YAML)
public interface DiagnosticsSettings {

    /**
     * Whether the diagnostics a rig file produced are written to the console at all.
     *
     * <p>On by default. A clamped value is a value the author believes is in effect, so silence here
     * means a rig that behaves differently from the file describing it with nothing to point at. The
     * toggle exists for the server that has forty rigs and has already read the warnings.
     *
     * @return {@code true} to log rig-load diagnostics
     */
    @ConfigValue(comment = "Log the warnings and errors a rig file produced while loading. Turning this off hides values that were clamped, which are exactly the values an author believes are in effect.")
    default boolean logRigDiagnostics() {
        return true;
    }

    /**
     * Lowest {@link RigDiagnostic.Severity} that is logged, by name.
     *
     * <p>Stored as text because the configuration system's type matrix has no enum support, and
     * spelling an enum as a string is better than spelling it as an ordinal: a reordered enum silently
     * changes what an ordinal means, while a name either resolves or produces a diagnostic naming the
     * file and the key. {@link ConfigMapper} refuses a name that is not a constant rather than
     * defaulting it, because an operator who wrote {@code WARN} meant something and should be told it
     * did nothing.
     *
     * @return the name of the lowest severity to log
     */
    @ConfigValue(comment = "Lowest rig diagnostic severity that is logged: WARNING or ERROR. ERROR hides the clamps and keeps only the rigs that failed outright.")
    default @NotNull String rigDiagnosticSeverity() {
        return RigDiagnostic.Severity.WARNING.name();
    }

    /**
     * Whether a configuration reload logs one line saying whether it was applied or refused.
     *
     * <p>On by default, and the one toggle here that guards against a silent failure rather than a
     * noisy success: a refused reload leaves the previous values live, which looks exactly like a
     * reload that did nothing because nothing changed. Without the line, the operator's edit appears to
     * have taken effect.
     *
     * @return {@code true} to log a summary line per reload
     */
    @ConfigValue(comment = "Log one line per configuration reload saying whether it was applied or refused. A refused reload otherwise looks exactly like an edit that changed nothing.")
    default boolean logConfigReloadSummary() {
        return true;
    }
}
