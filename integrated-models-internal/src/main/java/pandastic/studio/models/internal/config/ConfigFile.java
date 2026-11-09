package pandastic.studio.models.internal.config;

import org.jetbrains.annotations.NotNull;

/**
 * The files this plugin's tunable values live in.
 *
 * <p>The enum exists so a diagnostic can <em>name</em> the file it came from. A message that says a
 * value is out of range without saying where to go and change it costs the operator a grep, and the
 * grep is over YAML keys that look alike across several files.
 *
 * <h2>Why not the generated {@code DEFAULT_FILENAME}</h2>
 * Every generated configuration class publishes one, but it is derived from the generated class name —
 * {@code PhysicsConfig.yml}. That name leaks an implementation detail of the code generator into the
 * server's data folder, and {@code PhysicsConfig} is a class, not a subject. The operator-facing names
 * are declared here instead, and they are the names handed to
 * {@link pandastic.studio.core.service.api.config.io.ConfigFactory} so the generated default is never
 * used.
 */
public enum ConfigFile {

    /** Solver calibration and the bounds a physics profile is validated against. */
    PHYSICS("physics.yml"),

    /** Walk cycle calibration, plus the bounds on a locomotion binding and a gait profile. */
    GAIT("gait.yml"),

    /** Emission rate, interpolation window and the dirty-tracking threshold. */
    RENDER("render.yml"),

    /** Loop rate, log rate limiting, and the driver and IK guards. */
    SIMULATION("simulation.yml"),

    /** What the plugin is allowed to say about its own files. */
    DIAGNOSTICS("diagnostics.yml"),

    /** How much behaviour a creature may be given, and what happens when a behaviour throws. */
    AI("ai.yml"),

    /** How fast a vehicle may go, how often it is decided, and when a move becomes a teleport. */
    VEHICLE("vehicle.yml"),

    /** Every word a menu puts on a screen, every bound a menu control offers, and the session timeout. */
    MENU("menu.yml"),

    /** How far, and in which directions, a model is worth sending the bones of. */
    VIEW("view.yml"),

    /** How often the region threads tell the simulation where a bound model's owner is. */
    BINDING("binding.yml"),

    /** Every line the reload command says back to whoever typed it. */
    COMMAND("command.yml");

    private final @NotNull String fileName;

    ConfigFile(@NotNull String fileName) {
        this.fileName = fileName;
    }

    /**
     * The file name, including its extension, relative to the directory the configuration layer was
     * pointed at.
     *
     * @return the file name
     */
    public @NotNull String fileName() {
        return this.fileName;
    }

    @Override
    public @NotNull String toString() {
        return this.fileName;
    }
}
