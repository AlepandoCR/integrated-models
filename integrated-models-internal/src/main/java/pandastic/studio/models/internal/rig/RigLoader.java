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
 * A handful of top-level keys, and a flat map of bones that each name their own parent:
 *
 * <pre>
 * name: windswept_banner     # optional, defaults to the file name
 * model: banner              # required: the model id the renderer knows
 * kind: vehicle              # optional: creature (default), vehicle or decoration
 * clips:                     # optional: the model's OWN animations, per locomotion state
 *   idle: idle
 *   walk: walk
 * bones:
 *   pole: {}                 # a root, with no physics: an anchor for bones that have some
 *   tail:
 *     parent: pole
 *     animation-owned: false # optional: does a shipped clip already keyframe this bone?
 *     physics:
 *       kind: pendulum
 *       stiffness: 0.08
 *       limits:
 *         pitch: [-60, 60]
 * seats:                     # optional, and only on a `kind: vehicle` rig
 *   - locator: seat_driver   # a LOCATOR element of the model, never a bone
 *     driver: true
 * </pre>
 *
 * {@link #EXAMPLE} is a complete, commented file covering every physics kind and every option; it is
 * the format's reference, and a test asserts that it loads without a single diagnostic.
 *
 * <h2>Three sections that are not about how a bone moves</h2>
 * <ul>
 *   <li>{@code kind} gates the rest of the format instead of tuning anything. A
 *       {@link pandastic.studio.models.api.rig.RigKind#VEHICLE} is the only kind that may declare
 *       seats, and an absent {@code kind} is a {@code creature} — so every file written before the key
 *       existed means exactly what it did then. An unknown spelling is an <strong>error</strong>, like
 *       an unknown {@code arm-swing-axis} and unlike almost everything else here: a kind guessed after
 *       a typo loads a file that means something other than what it says.</li>
 *   <li>{@code seats} says where riders sit on a vehicle, and <strong>every seat names a locator</strong>
 *       — the empty, cube-less marker a rigger places in Blockbench — never a bone. There is no bone
 *       fallback, by policy: a bone is geometry the renderer's own animation pass moves, so a rider
 *       seated on one sits wherever the playing clip left it. A seat naming something this rig declares
 *       as a bone is therefore reported, because that is the mistake the rule exists to catch. It is a
 *       list rather than a mapping because riders are offered seats in file order, and a sequence's
 *       order is the format's guarantee where a mapping's is the parser's.</li>
 *   <li>{@code clips} and the per-bone {@code animation-owned} flag describe the model's <em>own</em>
 *       animations. They exist because this plugin's pose writer is a second writer to the same
 *       display: on a bone a shipped clip keyframes, the renderer sends {@code bind ∘ animation} and
 *       this plugin sends {@code bind ∘ physics} inside one frame, and the display keeps whichever
 *       arrived last. Nothing in the loader can see inside a {@code .bbmodel}, so the file is where
 *       that overlap gets declared — and a bone that declares both physics and animation ownership is
 *       warned about, naming the bone and the kind.</li>
 * </ul>
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
 *   <li>A seat that cannot work loses the <strong>seat</strong>, not the rig: one with no
 *       {@code locator}, one naming a bone, one repeating another's name. A second driver's seat is
 *       demoted to a passenger's rather than dropped, because a vehicle taking input from one rider is
 *       no reason to lose a place to sit; an out-of-range rider offset is clamped like any coefficient;
 *       and seats past {@link pandastic.studio.models.api.rig.SeatBounds#maxSeats()} are dropped from
 *       the end, since the end is the last riders would have been offered. A {@code seats} section on a
 *       rig that is not a vehicle is dropped whole, with a warning naming {@code kind} — the likely
 *       mistake is a forgotten {@code kind: vehicle}, and reading it as a vehicle anyway would make a
 *       creature rideable on the strength of a key the file never wrote.</li>
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

            # What the rig is for: creature (the default), vehicle or decoration. It gates parts of the
            # format rather than changing how anything moves - a vehicle is the only kind that may
            # declare `seats` - so a file that omits it is a creature and means what it always meant.
            # A kind nobody recognises is an error rather than a fallback: guessing one would load a
            # file that means something other than what it says, and the shape of that mistake is a
            # vehicle whose seats were all quietly thrown away.
            #
            # This example is declared a vehicle because one file has to exercise the whole format. A
            # banner on a pole is really a `decoration`; pick the kind your model actually is.
            kind: vehicle

            # The model's OWN animations, bound to the three locomotion states the pipeline can tell
            # apart. Optional, and every state is optional on its own: bind what the model ships.
            #
            # Why a rig names them at all: this plugin poses bones by writing the same display the
            # renderer's animation pass writes. On a bone a clip keyframes, the renderer sends
            # (bind o animation) and we send (bind o physics) inside the same frame, and the display
            # keeps whichever packet arrived last - a bone that flickers between two poses. Naming the
            # clips is what lets the layer that composes the two know which one is playing.
            #
            # Clip names are kept EXACTLY as the rigger typed them, unlike every other name in this
            # file: they are looked up inside a .bbmodel this plugin does not own, and the models in
            # this repository ship clips called `standUp` and `lookaround`. Capitals matter here.
            clips:
              idle: idle
              walk: walk
              # run: running         # omitted roles are simply not bound

            bones:
              # Every key under `bones` is a bone name in the model. Names are matched case-insensitively
              # on the bare name, because renderer bone ids carry tag prefixes that riggers add and
              # remove without telling anybody.
              #
              # Declare the hierarchy ONCE, with `parent`. Order does not matter - the loader sorts
              # parents before their children itself. A bone with nothing to say still needs a value, so
              # write `{}`: a bare `pole:` is dropped by the YAML layer before the loader ever sees it.
              pole:                  # no `parent` -> a root. No `physics` -> a pass-through anchor.
                # Says that one of the model's own clips keyframes this bone. Optional, false when
                # absent, and a statement about the asset rather than a switch - nothing here can look
                # inside a .bbmodel to find out, so the file is where it gets declared.
                #
                # Marking a bone animation-owned AND giving it physics is legal and warns, naming the
                # bone: the two write the same display and the last packet of the frame wins. Say both
                # only when you know the clip and the physics move the bone on different axes.
                animation-owned: true

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

            # Where riders sit. Only a `kind: vehicle` rig may declare this: on any other kind the whole
            # section is dropped with a warning naming `kind`, because the likely mistake is a forgotten
            # `kind: vehicle` and the alternative - reading a creature as rideable - is worse.
            #
            # A seat names a LOCATOR: the empty, cube-less marker a rigger places in Blockbench. Never a
            # bone, and deliberately with no fallback to one. A bone is geometry the model's own
            # animations move, so a rider seated on a bone sits wherever the playing clip left them,
            # while a locator carries no geometry and exists for exactly this. A seat naming something
            # this rig declares as a bone is reported and dropped - that is the mistake this rule exists
            # to catch, not a mode to fall back on. A model whose rigger has not added locators yet
            # therefore declares no working seat, and that is the expected state rather than a fault.
            #
            # A list rather than a mapping, because riders are offered seats in the order written here
            # and a YAML sequence is the only form whose order is the format's guarantee.
            seats:
              - locator: seat_driver  # matched like a bone name: lower-cased, tag prefixes stripped
                driver: true          # at most one seat drives. A second is read as a passenger seat
                                      # with a warning naming the first, because a vehicle takes its
                                      # input from one rider.
              - name: bench_left      # optional: defaults to the locator's name. This is what a plugin
                                      # asks for a seat by, and it is matched case-insensitively.
                locator: seat_bench_left
                offset: [0.0, 0.1, 0.0]   # blocks from the locator, within the configured ceiling. The
                                          # rigger put the locator where the rider belongs, so this is
                                          # for the half-block a saddle sits above a pivot - a rider who
                                          # needs more than a nudge has a locator in the wrong place.
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
