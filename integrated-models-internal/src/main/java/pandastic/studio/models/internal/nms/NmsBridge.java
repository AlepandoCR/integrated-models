package pandastic.studio.models.internal.nms;

import org.jetbrains.annotations.NotNull;

/**
 * The one seam between this plugin and Mojang-mapped server internals.
 *
 * <h2>Why a seam at all, when there is only ever one server</h2>
 * Not for portability — this plugin targets Folia and nothing else. The seam exists because of
 * where the implementation has to be <em>compiled</em>. Mojang-mapped access needs
 * paperweight-userdev and a Folia dev bundle, and paperweight resolves that bundle while Gradle
 * configures the project it is applied to. Applied to the module holding the rig, physics,
 * locomotion and render layers, it would make a ~26 MB download and a server remap a precondition
 * for compiling any of them, and would put an alpha-channel Folia server jar on the compile and
 * test classpaths of 459 tests that have no business seeing one.
 *
 * <p>So the implementation lives in its own module, {@code integrated-models-nms}, which depends on
 * this module to see these interfaces and which nothing depends on in return. Everything above the
 * seam compiles and tests with plain {@code paper-api}. That is the entire justification for the
 * indirection, and it is a build fact rather than a design preference.
 *
 * <h2>Why it is also a version adapter</h2>
 * NMS signatures are not stable across Minecraft versions, and 26.3 exists on Folia's <em>alpha</em>
 * channel only, where the shape of what we reach into can change between two builds nobody chose to
 * upgrade between. An implementation therefore declares the exact version it was compiled against
 * (see {@link NmsBridgeProvider}) and {@link NmsBridgeLookup} refuses to hand it to any other. A
 * bridge that claims to work on a version it was not compiled for fails as a
 * {@link NoSuchMethodError} deep inside a tick, which is the single worst place to learn about it.
 *
 * <h2>Absence is a supported state</h2>
 * {@link NmsBridgeLookup#resolve(String)} returns no bridge when the running server is a version no
 * shipped adapter was built for, and also when the NMS module was excluded from the build. Callers
 * must handle that rather than assume it away: the plugin's rendering pipeline does not need NMS,
 * so a missing bridge costs the AI layer and nothing else. Degrade, log once, keep rendering.
 *
 * <h2>Nothing here is tunable</h2>
 * This is a capability lookup, not a calibration surface. Any coefficient the AI layer eventually
 * needs — follow distance, path recalculation interval, rider acceleration — is a
 * {@code @Configurable} key read above the seam and passed in, never a literal below it. An
 * implementation of this interface that reads configuration has the dependency backwards.
 */
public interface NmsBridge {

    /**
     * The Minecraft version this bridge was compiled against, as the server reports its own.
     *
     * <p>Held for diagnostics: when something below the seam misbehaves, the first question is
     * always whether the adapter and the server agree, and the answer has to be in the log line
     * rather than inferred from the jar.
     */
    @NotNull
    String minecraftVersion();

    /**
     * Brain and goal installation.
     *
     * @see BrainBridge
     */
    @NotNull
    BrainBridge brains();

    /**
     * Path-navigation construction.
     *
     * @see NavigationBridge
     */
    @NotNull
    NavigationBridge navigation();

    /**
     * Rider intent, read off the controlling player.
     *
     * @see RiderInputBridge
     */
    @NotNull
    RiderInputBridge riderInput();

    /**
     * Applying a decided movement to the entity.
     *
     * @see MovementBridge
     */
    @NotNull
    MovementBridge movement();
}
