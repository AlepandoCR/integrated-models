package pandastic.studio.models.internal.rig;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.rig.Rig;
import pandastic.studio.models.api.rig.RigBone;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a YAML rig file into a validated {@link Rig}, or into an explanation of why it could not.
 *
 * <h2>The format</h2>
 * Three top-level keys, and a flat map of bones that each name their own parent:
 *
 * <pre>
 * name: windswept_banner     # optional, defaults to the file name
 * model: banner              # required: the model id the renderer knows
 * bones:
 *   pole: {}                 # a root, with no physics: an anchor for bones that have some
 *   tail:
 *     parent: pole
 *     physics:
 *       kind: pendulum
 *       stiffness: 0.08
 *       limits:
 *         pitch: [-60, 60]
 * </pre>
 *
 * {@link #EXAMPLE} is a complete, commented file covering every physics kind and every option; it is
 * the format's reference, and a test asserts that it loads without a single diagnostic.
 *
 * <p>The hierarchy is written <strong>once</strong>: a bone names its parent and nothing nests. That
 * costs a topological sort at load time — {@link Rig.Builder} insists a parent is declared before its
 * children, and a YAML mapping's order is whatever the author felt like — and buys a file where moving
 * a bone is a one-line edit instead of a cut and paste, and where no second copy of the tree can drift
 * out of step with the first.
 *
 * <h2>Clamp and warn, do not refuse</h2>
 * The api types throw on bad input, correctly: a programmatic caller handing {@code PhysicsProfile} a
 * stiffness of 50 has a bug, and the stack trace is the fastest way to it. A <em>file</em> is different.
 * Its author is a human with a text editor, they are usually not the person running the server, and a
 * model that loads with a warning beats a model that does not load. So:
 *
 * <ul>
 *   <li>A coefficient outside its permitted range is <strong>clamped</strong>, with a warning naming
 *       the file, the bone and the value.</li>
 *   <li>Damping past the integrator's stability bound is clamped through
 *       {@code AngularPhysicsSolver.maxStableDamping}, asked of the solver in
 *       {@link RigLoadSettings#solver()} — the one that will actually run the rig, because the bound
 *       divides by that solver's own calibration. This is the bound nobody guesses — damping has to
 *       stay below <em>mass</em> at the configured substep rate, and the permitted ranges allow
 *       violating that by a wide margin — so it is also the one most worth clamping rather than
 *       refusing.</li>
 *   <li>A value that is not a number at all, a degenerate reach vector or an unreadable limits block
 *       <strong>falls back to its default</strong>, with a warning. Nothing is silently ignored: an
 *       unknown key, a repeated key and a key the chosen physics kind does not read all warn too,
 *       because a tuning value that never applied is worse than one that was rejected.</li>
 *   <li>A bone whose physics block is structurally broken — not a mapping, no {@code kind}, an unknown
 *       {@code kind} — loses its <strong>physics</strong>, not its existence. It stays in the rig as a
 *       pass-through so its children still hang off it and the rest of the chain still works.</li>
 *   <li>Bones past {@link pandastic.studio.models.api.rig.RigBounds#maxBones()} are
 *       <strong>dropped</strong> from the deep end, with a warning naming how many were lost. The list
 *       is in topological order by then, so a dropped bone's descendants go with it and what is left is
 *       still a complete hierarchy.</li>
 *   <li>Structural damage that leaves nothing coherent — no {@code model}, no bones, a cycle, a parent
 *       that does not exist, unparseable YAML — is an <strong>error</strong>, and no rig comes back.</li>
 * </ul>
 *
 * Nothing here logs, and nothing throws per problem: every complaint is collected into the
 * {@link RigLoadResult} so that one bad bone cannot hide the next nine and so the whole loader is
 * testable as a plain function. The single exception is a caller that supplies no source name, which
 * is a programming error rather than a file problem.
 *
 * <h2>Two sharp edges of Bukkit's YAML layer</h2>
 * Both are the configuration layer's, not this format's, and both are worth knowing before debugging
 * a file that reads correctly and loads wrong:
 *
 * <ul>
 *   <li>A key with <em>no value</em> is removed before the loader sees it, so a bone written as a bare
 *       {@code pole:} simply does not exist. Write {@code pole: {}}.</li>
 *   <li>A key containing a {@code .} is split into nested sections, so a bone name cannot contain one.
 *       Such a name degrades to an unknown-key warning rather than silence.</li>
 * </ul>
 *
 * <h2>Names are not resolved here</h2>
 * A rig names bones in a model this plugin does not own, and this loader never checks whether they
 * exist: it has no model to ask, and rig files are readable long before the renderer has loaded its
 * assets. {@link #expectedBoneNames(Rig)} hands the set out so the binding layer can check it against
 * a live model, once, at bind time.
 *
 * <h2>Every entry point takes its settings, and none of them has a default</h2>
 * Bounds, the clamp margin and the solver whose calibration the damping ceiling is derived from all
 * arrive as a {@link RigLoadSettings} snapshot. It is a value rather than a source on purpose: a load
 * is a single bounded operation, and taking the snapshot once at the entry point means one file is
 * never read half against one set of bounds and half against another. A caller reloading rigs after a
 * configuration reload simply loads them again with the new snapshot.
 */
public final class RigLoader {

    /**
     * A complete rig file, exercising every physics kind and every option the format accepts.
     *
     * <p>Kept as a constant rather than as a resource so that it is documentation a reader finds next
     * to the parser that implements it, and so that a test can load it — a documented format that
     * nothing parses drifts from the code within a release.
     */
    public static final @NotNull String EXAMPLE = """
            # An integrated-models rig: this plugin's own skeleton overlay for a model it does not own.
            # The model file lives in the renderer's asset folder; this file only names bones inside it
            # and says what drives each one.

            name: windswept_banner   # this rig's own id, for logs. Optional: defaults to the file name.
            model: banner            # the model id the renderer knows this model by. Required.

            bones:
              # Every key under `bones` is a bone name in the model. Names are matched case-insensitively
              # on the bare name, because renderer bone ids carry tag prefixes that riggers add and
              # remove without telling anybody.
              #
              # Declare the hierarchy ONCE, with `parent`. Order does not matter - the loader sorts
              # parents before their children itself. A bone with nothing to say still needs a value, so
              # write `{}`: a bare `pole:` is dropped by the YAML layer before the loader ever sees it.
              pole: {}               # no `parent` -> a root. No `physics` -> a pass-through anchor.

              # Every coefficient below is clamped into a range this server configures, and a value
              # outside it loads with a warning naming the bone and the clamp rather than failing. The
              # ranges quoted here are the ones a stock configuration ships with; check yours.
              #
              # PENDULUM: a damped spring with gravity on the pitch axis. Hair, tails, anything that
              # should hang and ring.
              tail:
                parent: pole
                physics:
                  kind: pendulum
                  stiffness: 0.08    # 0..1, how hard the bone is pulled back to its rest pose
                  damping: 0.015     # 0..1, how fast its motion bleeds off - and below `mass`, always
                  gravity: 0.25      # up to the configured ceiling; downward pull on the pitch axis
                  mass: 1.0          # within the configured range; divides restoring and damping
                  wind: 1.0          # up to the configured ceiling; how hard movement drags the bone
                  reach: [0.0, -1.0, 0.0]   # direction the bone extends along, in model space
                  limits:            # degrees, converted to radians once, here at load
                    pitch: [-60, 60] # a two-element list is an explicit [min, max]
                    yaw: 45          # a single number is a symmetric half-width
                    roll: [-10, 10]  # an omitted axis keeps the configured default half-width

              # SPRING: the same spring without gravity, so it returns to wherever the rig put it
              # instead of hanging. Antennae, ornaments, anything mounted rather than suspended.
              antenna:
                parent: pole
                physics:
                  kind: spring
                  stiffness: 0.2
                  damping: 0.05
                  mass: 0.5
                  wind: 2.0
                  limits: 30         # one number: symmetric on all three axes

              # CLOTH_STRIP: a pendulum plus a clearance cone, folded into a plain pitch clamp at load
              # time so the solver never has to run a collision test.
              cloth_left:
                parent: pole
                physics:
                  kind: cloth-strip  # '-' and '_' are interchangeable and case is ignored
                  stiffness: 0.05
                  damping: 0.02
                  gravity: 1.0
                  mass: 1.5
                  wind: 1.5
                  collision-radius: 0.4   # blocks of clearance, up to the configured ceiling. 0 disables.
                  reach: [0.0, -1.0, 0.2]
                  limits:
                    pitch: [-80, 80]

              # DRAG: exponential lag with no velocity term. It trails its parent and settles, but never
              # overshoots and cannot diverge - the right choice when ringing is unwanted.
              cloak:
                parent: cloth_left   # parenting to a physics bone makes this one inherit its motion
                physics:
                  kind: drag
                  lag: 0.6           # 0..1, the fraction of its offset the bone keeps each step
                  mass: 1.0
                  wind: 1.0
                  limits: unbounded  # only sane for drag, which has nothing to diverge
            """;

    private RigLoader() {
    }

    /**
     * Reads a rig from a YAML document held in memory.
     *
     * @param source   where the text came from, used only to make diagnostics actionable
     * @param yaml     the document
     * @param settings the bounds, clamp margin and solver this read is performed against
     */
    public static @NotNull RigLoadResult loadText(
            @NotNull String source,
            @NotNull String yaml,
            @NotNull RigLoadSettings settings
    ) {
        requireSource(source);
        final YamlConfiguration configuration = new YamlConfiguration();
        try {
            // loadFromString rather than YamlConfiguration.loadConfiguration: the latter swallows a
            // parse failure, logs it through Bukkit's java.util.logging logger and hands back a blank
            // configuration. That is the wrong shape twice over - this loader reports rather than logs,
            // and a blank config would surface as "no model id" instead of "line 7 is not YAML".
            configuration.loadFromString(yaml);
        } catch (final InvalidConfigurationException invalid) {
            return RigLoadResult.failure(List.of(RigDiagnostic.error(
                    source, null, "is not readable YAML: " + rootCause(invalid))));
        }
        return loadSection(source, configuration, settings);
    }

    /**
     * Reads a rig from an already-parsed configuration — a section of a larger file, say.
     *
     * @param source   where the configuration came from, used only in diagnostics
     * @param section  the section holding {@code name}, {@code model} and {@code bones}
     * @param settings the bounds, clamp margin and solver this read is performed against
     */
    public static @NotNull RigLoadResult loadSection(
            @NotNull String source,
            @NotNull ConfigurationSection section,
            @NotNull RigLoadSettings settings
    ) {
        requireSource(source);
        return new RigFileReader(source, settings).read(section);
    }

    /**
     * Reads a rig from a file, naming it in diagnostics by its file name.
     *
     * <p>An unreadable file is an error like any other rather than a thrown {@link IOException}: a
     * caller walking a directory of rigs wants to report the one that failed and load the rest.
     *
     * @param settings the bounds, clamp margin and solver this read is performed against
     */
    public static @NotNull RigLoadResult loadFile(
            @NotNull Path file,
            @NotNull RigLoadSettings settings
    ) {
        final String source = file.getFileName() == null ? file.toString() : file.getFileName().toString();
        final String yaml;
        try {
            yaml = Files.readString(file, StandardCharsets.UTF_8);
        } catch (final IOException unreadable) {
            return RigLoadResult.failure(List.of(RigDiagnostic.error(
                    source, null, "could not be read: " + rootCause(unreadable))));
        }
        return loadText(source, yaml, settings);
    }

    /**
     * The bone names {@code rig} expects to find in a model, lower-cased and in root-first order.
     *
     * <p>Exists so the binding layer can compare a rig against a live model before anything spawns,
     * and so a mismatch can be reported as a list rather than as the first failure. The order is the
     * rig's own topological order, which makes such a report read from the root outward.
     *
     * @return an unmodifiable set
     */
    public static @NotNull Set<String> expectedBoneNames(@NotNull Rig rig) {
        final Set<String> names = LinkedHashSet.newLinkedHashSet(rig.boneCount());
        for (final RigBone bone : rig.getBones()) {
            names.add(bone.name());
        }
        return Collections.unmodifiableSet(names);
    }

    private static void requireSource(@NotNull String source) {
        if (source.isBlank()) {
            throw new IllegalArgumentException(
                    "A rig load needs a source name: every diagnostic has to say which file it is about");
        }
    }

    /** The innermost message of a wrapped failure, which is the only part that names a line number. */
    private static @NotNull String rootCause(@NotNull Throwable thrown) {
        Throwable cursor = thrown;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        final String message = cursor.getMessage();
        return message == null || message.isBlank() ? cursor.getClass().getSimpleName() : message;
    }
}
