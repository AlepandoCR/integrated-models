package pandastic.studio.models.internal.command;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.input.InputCapability;
import pandastic.studio.models.api.model.Model;
import pandastic.studio.models.api.rig.Rig;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Everything the two status commands report, read once and frozen.
 *
 * <h2>Why one value rather than seven accessors</h2>
 * For the reason the configuration layer publishes one snapshot: a report assembled by asking seven live
 * sources seven questions while it formats can straddle a bind, a reload or a shutdown, and then prints a
 * line claiming eleven models and a line claiming nine bones' worth of them. An operator reading two
 * figures that cannot both be true learns nothing except not to trust the command. So the whole thing is
 * captured in one pass through {@link StatusSource} and the reporter never touches a live object.
 *
 * <h2>Platform-free on purpose</h2>
 * Nothing here is a Bukkit type, a renderer type or an Adventure component. The capture is the only part
 * that needs a server, and it is one lambda; the verdict logic below and the whole of
 * {@link StatusReport} are consequently exercisable with values a test wrote by hand, which is what makes
 * "what does this command say when the AI layer is absent and the loop is stopped" a question with an
 * answer that does not need a server to get wrong.
 *
 * <h2>What this type refuses to claim</h2>
 * Three figures an operator would reasonably expect are genuinely not measurable from here, and each is
 * reported as such rather than guessed at — see {@link KnownGap}. A status command that printed a
 * plausible number for a thing nothing counts would be worse than one that admits it: the number would be
 * believed.
 *
 * @param renderer      which rendering backend is driving this server, or why none is
 * @param configuration the revision in force and what the last reload did
 * @param rigs          what was read out of {@code rigs/}
 * @param simulation    the off-tick loop every model's secondary motion is stepped from
 * @param drivers       the off-tick loop every decoration's ambient driver is published from
 * @param emission      how often a pose leaves for the client, and over how long a window
 * @param ai            the creature AI layer, or why there is none
 * @param input         what this server can observe and suppress of a player's input
 * @param world         what this plugin has standing in the world right now
 */
public record StatusSnapshot(
        @NotNull Renderer renderer,
        @NotNull Configuration configuration,
        @NotNull Rigs rigs,
        @NotNull Loop simulation,
        @NotNull Loop drivers,
        @NotNull Emission emission,
        @NotNull Ai ai,
        @NotNull Input input,
        @NotNull World world
) {

    /** Nanoseconds in a second. An SI unit conversion, which is why it is a literal. */
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    public StatusSnapshot {
        Objects.requireNonNull(renderer, "renderer");
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(rigs, "rigs");
        Objects.requireNonNull(simulation, "simulation");
        Objects.requireNonNull(drivers, "drivers");
        Objects.requireNonNull(emission, "emission");
        Objects.requireNonNull(ai, "ai");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(world, "world");
    }

    /**
     * How well one part of the plugin is doing, in the only three gradations worth distinguishing.
     *
     * <p>The ordinal is the severity, which is what {@link #worseOf} relies on and the reason the three
     * are declared in this order. Adding a fourth between two of these would change the comparison, which
     * is why the comparison is written against {@link #ordinal()} once, here, rather than at every site.
     */
    public enum State {

        /** Working as configured. */
        OK,

        /**
         * Working less than completely, and the operator may well have chosen this.
         *
         * <p>A server with no NMS adapter, or one running on files its last reload refused, is in a state
         * somebody can act on and nothing is broken — so this is deliberately not a fault. The health
         * verdict reports it separately for exactly that reason.
         */
        DEGRADED,

        /** Not doing its job at all. */
        DOWN;

        /** Whichever of the two is worse, or either when they are equal. */
        @Contract(pure = true)
        public static @NotNull State worseOf(@NotNull State left, @NotNull State right) {
            return left.ordinal() >= right.ordinal() ? left : right;
        }
    }

    /**
     * A figure this plugin cannot produce, named so that a report can say so.
     *
     * <h2>Why these are enumerated in code and worded in configuration</h2>
     * Whether the per-viewer culler has a loop is a fact about the build, not a preference, so the
     * <em>list</em> belongs here; what the operator reads about it is prose, so the <em>sentence</em>
     * belongs in {@code command.yml}. The alternative — a configurable list of known gaps — would let an
     * operator delete the only line that says a figure is missing, which is the one edit that makes the
     * report dishonest.
     */
    public enum KnownGap {

        /**
         * No transform or packet counter is readable from the engine.
         *
         * <p>The only emission counter in the tree lives on the backend's pose sink, and the engine may
         * not reference the backend — the dependency runs backend → engine → api, which is what makes a
         * second renderer a new module rather than a rewrite. So the statistics report prints the
         * <em>nominal</em> transform rate, which is the emission rate times the posed bone count, and
         * says that is what it is. Measuring the real one means a counter on the render seam, which is a
         * change to a published interface for a diagnostic.
         */
        EMISSION_METRICS,

        /**
         * Rig diagnostics are not retained after a load.
         *
         * <p>They are logged, subject to {@code diagnostics.yml}, and then dropped: a rig that failed to
         * compile is simply absent from the runtime's map. So "three rigs loaded" is exact and "and one
         * was refused" is unanswerable after the fact — the console at load or reload time is where that
         * is written, and the report points at it rather than inventing a count.
         */
        RIG_DIAGNOSTICS,

        /**
         * The per-viewer culler is built, tested and unreachable.
         *
         * <p>It is a stateless pass function with no loop and no viewer sampler, so every key in
         * {@code view.yml} is inert and a decoration is seen by exactly the players a consumer called
         * {@code show} for. A health report that listed it among the working parts would be the worst
         * possible lie to ship, since it would read as "culling is on" to the one person deciding whether
         * their view distance settings are doing anything.
         */
        VIEW_CULLING
    }

    /**
     * Which rendering backend is driving this server.
     *
     * <p>Absent is fatal rather than degraded, and in practice unobservable: plugin enable refuses
     * outright when there is no backend, so a status command running at all implies one was found. It is
     * modelled anyway because the resolution carries <em>which</em> renderer and what it passed over, and
     * because "the report cannot be wrong about the one thing that must be true" is cheaper to guarantee
     * than to reason about.
     *
     * @param present      whether a backend was found and built
     * @param rendererName the renderer it drives, or blank when there is none
     * @param rejections   one line per provider that was seen and not used, in discovery order
     */
    public record Renderer(boolean present, @NotNull String rendererName, @NotNull List<String> rejections) {

        public Renderer {
            Objects.requireNonNull(rendererName, "rendererName");
            rejections = List.copyOf(Objects.requireNonNull(rejections, "rejections"));
        }

        /** {@link State#OK} when a backend is driving, {@link State#DOWN} otherwise. */
        public @NotNull State state() {
            return this.present ? State.OK : State.DOWN;
        }

        /**
         * Everything that was passed over, as one line.
         *
         * @return the joined rejections, or an empty string when there were none
         */
        public @NotNull String reason() {
            return join(this.rejections);
        }
    }

    /**
     * The configuration in force, and what the last reload did to get there.
     *
     * @param revision the publication counter of the values in effect
     * @param lastReload what the last observed reload did; see {@link ReloadOutcome#UNKNOWN}
     * @param problems how many diagnostics that reload produced, zero unless it was refused
     */
    public record Configuration(long revision, @NotNull ReloadOutcome lastReload, int problems) {

        public Configuration {
            Objects.requireNonNull(lastReload, "lastReload");
            if (revision < 0L) {
                throw new IllegalArgumentException("revision cannot be negative but was " + revision);
            }
            if (problems < 0) {
                throw new IllegalArgumentException("problems cannot be negative but was " + problems);
            }
        }

        /**
         * {@link State#DEGRADED} when the last reload did not take, {@link State#OK} otherwise.
         *
         * <p>Degraded rather than down, and deliberately: a refused reload changes nothing, so the server
         * is running on values a file still describes. What is wrong is that the files on disk and the
         * values in force disagree, and nothing else on the server will ever mention it again.
         */
        public @NotNull State state() {
            return this.lastReload == ReloadOutcome.REFUSED || this.lastReload == ReloadOutcome.FAILED
                    ? State.DEGRADED
                    : State.OK;
        }
    }

    /**
     * What was read out of the rig directory.
     *
     * <p>Always {@link State#OK}: zero rigs is a normal state, because a consumer may bind entirely
     * against rigs derived from the renderer's own models and never write a rig file at all. The figure is
     * reported because it is the first thing to check when a bind names a rig nobody can find.
     *
     * @param loaded how many rigs are in the runtime's map
     * @param bones  how many bones they declare between them
     */
    public record Rigs(int loaded, int bones) {

        public Rigs {
            if (loaded < 0 || bones < 0) {
                throw new IllegalArgumentException(
                        "A rig count cannot be negative: loaded=" + loaded + ", bones=" + bones);
            }
        }

        /** Always {@link State#OK}; see the record documentation. */
        public @NotNull State state() {
            return State.OK;
        }

        /**
         * The rigs {@code loaded} describes.
         *
         * <p>Here rather than at the capture site so that the summation is exercisable: a {@link Rig} is
         * pure, validated data with no platform anywhere near it, so a test builds three and asserts the
         * total.
         *
         * @param rigs every loaded rig
         * @return the counts
         */
        @Contract(value = "_ -> new", pure = true)
        public static @NotNull Rigs of(@NotNull Collection<? extends Rig> rigs) {
            Objects.requireNonNull(rigs, "rigs");
            int bones = 0;
            for (final Rig rig : rigs) {
                bones += rig.boneCount();
            }
            return new Rigs(rigs.size(), bones);
        }
    }

    /**
     * One of the two off-tick repeating tasks: whether it is running, at what rate, and how much it is
     * driving.
     *
     * <h2>Why both periods are carried</h2>
     * Because the gap between them is the one loop fault that is otherwise invisible. The platform owns a
     * repeating task's rate and a task cannot change its own, so a reload that moves a rate has to cancel
     * and re-dispatch — and a layer left out of the reload path keeps running at the old period forever,
     * with nothing to report and nothing to notice. An operator who halved a rate and saw nothing change
     * has no other way to find out that the dispatch never followed.
     *
     * @param running                whether the repeating task is live
     * @param configuredHz           the rate the files ask for
     * @param configuredPeriodNanos  the period that rate implies
     * @param dispatchedPeriodNanos  the period the live task was actually dispatched at
     * @param registrations          how many simulations, or decorations, this loop is driving
     */
    public record Loop(
            boolean running,
            int configuredHz,
            long configuredPeriodNanos,
            long dispatchedPeriodNanos,
            int registrations
    ) {

        public Loop {
            if (configuredHz <= 0) {
                throw new IllegalArgumentException(
                        "configuredHz must be positive but was " + configuredHz);
            }
            if (configuredPeriodNanos <= 0L) {
                throw new IllegalArgumentException(
                        "configuredPeriodNanos must be positive but was " + configuredPeriodNanos);
            }
            if (dispatchedPeriodNanos < 0L) {
                // Zero is legal and is what a loop that has never been started reports.
                throw new IllegalArgumentException(
                        "dispatchedPeriodNanos cannot be negative but was " + dispatchedPeriodNanos);
            }
            if (registrations < 0) {
                throw new IllegalArgumentException(
                        "registrations cannot be negative but was " + registrations);
            }
        }

        /**
         * Whether the live task is dispatched at a period the configuration no longer asks for.
         *
         * <p>Only meaningful while the loop is running: a stopped loop reports whatever it was last
         * dispatched at, which is history rather than a disagreement.
         */
        public boolean isRedispatchPending() {
            return this.running && this.dispatchedPeriodNanos != this.configuredPeriodNanos;
        }

        /**
         * The rate the live task is actually dispatched at, rounded to whole hertz.
         *
         * <p>Rounded because it is read by a human: the period is held in nanoseconds precisely so that
         * 60 Hz is not quantised to a whole millisecond, and reporting 59 for it would be a worse lie
         * than reporting 60.
         *
         * @return the dispatched rate, or zero for a loop that was never dispatched
         */
        public int dispatchedHz() {
            if (this.dispatchedPeriodNanos <= 0L) {
                return 0;
            }
            return (int) Math.round((double) NANOS_PER_SECOND / (double) this.dispatchedPeriodNanos);
        }

        /** Down when stopped, degraded when a re-dispatch is owed, otherwise fine. */
        public @NotNull State state() {
            if (!this.running) {
                return State.DOWN;
            }
            return isRedispatchPending() ? State.DEGRADED : State.OK;
        }
    }

    /**
     * How often a finished pose leaves for the client, and over how long the client is told to spread it.
     *
     * <h2>Why this is reported beside the loop rate rather than folded into it</h2>
     * Because the two are deliberately different and the difference is the design. A display entity
     * consumes its pending-update flags exactly once per client tick, so emitting faster than 20 Hz pays
     * full bandwidth for packets nobody can see; the smoothness above the tick rate comes from the
     * client's own interpolation and is free. An operator who finds a 60 Hz simulation and a 20 Hz
     * emission side by side and concludes two thirds of the work is being thrown away has read the
     * numbers correctly and the design backwards — which is why both are printed, with the window beside
     * them.
     *
     * @param emissionHz        emissions per second
     * @param interpolationTicks the window each emission asks the client to spread the motion over
     */
    public record Emission(int emissionHz, int interpolationTicks) {

        public Emission {
            if (emissionHz <= 0) {
                throw new IllegalArgumentException("emissionHz must be positive but was " + emissionHz);
            }
            if (interpolationTicks < 0) {
                throw new IllegalArgumentException(
                        "interpolationTicks cannot be negative but was " + interpolationTicks);
            }
        }
    }

    /**
     * The creature AI layer, or the reason there is none.
     *
     * @param available        whether the service was published
     * @param unavailableReason why it was not, naming the server version and every adapter passed over;
     *                         blank when it was
     * @param supportedSpecies how many creature species the adapter can install a plan on
     */
    public record Ai(
            boolean available,
            @NotNull String unavailableReason,
            int supportedSpecies
    ) {

        public Ai {
            Objects.requireNonNull(unavailableReason, "unavailableReason");
            if (supportedSpecies < 0) {
                throw new IllegalArgumentException(
                        "supportedSpecies cannot be negative but was " + supportedSpecies);
            }
        }

        /**
         * {@link State#DEGRADED} when there is no service, never {@link State#DOWN}.
         *
         * <p>The rendering pipeline does not need NMS, so a server with no adapter has a reduced feature
         * set and not a fault — the same judgement the runtime makes when it logs the reason as a warning
         * at enable rather than refusing to start. Reporting it as down would tell an operator their
         * server is broken when the only thing missing is a layer they may never use.
         */
        public @NotNull State state() {
            return this.available ? State.OK : State.DEGRADED;
        }
    }

    /**
     * What this server can see of a player's input, and what is bound to it.
     *
     * @param capabilities      what the started capture sources can do between them
     * @param unavailableReason why the set is less than complete, or blank when it is not
     * @param bindings          how many live input bindings consumers hold
     * @param issues            how many of them the controller's own audit complains about
     */
    public record Input(
            @NotNull Set<InputCapability> capabilities,
            @NotNull String unavailableReason,
            int bindings,
            int issues
    ) {

        public Input {
            Objects.requireNonNull(capabilities, "capabilities");
            Objects.requireNonNull(unavailableReason, "unavailableReason");
            capabilities = Set.copyOf(capabilities);
            if (bindings < 0 || issues < 0) {
                throw new IllegalArgumentException(
                        "An input count cannot be negative: bindings=" + bindings
                                + ", issues=" + issues);
            }
        }

        /**
         * Degraded when something is missing, and when the audit has a complaint.
         *
         * <p>The audit counts too, because a binding the controller refuses to dispatch is a consumer's
         * feature silently doing nothing — which is exactly the class of problem this command exists to
         * surface, and the controller already knows about it.
         */
        public @NotNull State state() {
            return this.unavailableReason.isBlank() && this.issues == 0
                    ? State.OK
                    : State.DEGRADED;
        }

        /** How many capabilities the started sources have between them. */
        public int capabilityCount() {
            return this.capabilities.size();
        }
    }

    /**
     * What this plugin has standing in the world.
     *
     * <h2>Why the three kinds and the registry total are all reported</h2>
     * Because they do not add up, on purpose, and the difference is information rather than an error. A
     * vehicle contributes <em>two</em> registry entries: itself, and its visual, which is a real rigged
     * model bound to its root entity. So the registry is larger than the sum of the binders' live sets by
     * the vehicle count, and an operator comparing the two figures is reading the one place that says so.
     *
     * @param models      live handles the model binder has handed out, vehicle visuals included
     * @param decorations live handles the decoration binder has handed out
     * @param vehicles    live handles the vehicle binder has handed out
     * @param registered  entries in the published registry
     * @param posedBones  bones across every registered model's rig — the figure every per-step cost in
     *                    this plugin is proportional to
     */
    public record World(int models, int decorations, int vehicles, int registered, int posedBones) {

        public World {
            if (models < 0 || decorations < 0 || vehicles < 0 || registered < 0 || posedBones < 0) {
                throw new IllegalArgumentException("A world count cannot be negative: models=" + models
                        + ", decorations=" + decorations + ", vehicles=" + vehicles
                        + ", registered=" + registered + ", posedBones=" + posedBones);
            }
        }

        /**
         * How many bones {@code models} carries between them.
         *
         * <p>{@link Model#getRig()} is pure data and touches neither the entity nor the renderer, which
         * is what makes this safe to call from whichever thread typed the command — unlike
         * {@code getEntity()}, which nothing in a status report may reach for, because reading an entity
         * owned by another region is a data race the server neither prevents nor reports.
         *
         * @param models every registered model
         * @return the total bone count
         */
        @Contract(pure = true)
        public static int posedBones(@NotNull Collection<? extends Model> models) {
            Objects.requireNonNull(models, "models");
            int bones = 0;
            for (final Model model : models) {
                bones += model.getRig().boneCount();
            }
            return bones;
        }
    }

    /**
     * The worst state any part is in.
     *
     * <p>Worst rather than an average or a count, because that is the question: an operator typing a
     * health command wants to know whether to look further, and one part being down is not offset by six
     * being fine.
     */
    public @NotNull State verdict() {
        State worst = State.OK;
        for (final State state : states()) {
            worst = State.worseOf(worst, state);
        }
        return worst;
    }

    /** Every part's state, in the order a report prints them. */
    public @NotNull List<State> states() {
        return List.of(
                this.renderer.state(),
                this.configuration.state(),
                this.rigs.state(),
                this.simulation.state(),
                this.drivers.state(),
                this.ai.state(),
                this.input.state());
    }

    /** How many parts are in {@code state}. */
    public int countOf(@NotNull State state) {
        Objects.requireNonNull(state, "state");
        int count = 0;
        for (final State each : states()) {
            if (each == state) {
                count++;
            }
        }
        return count;
    }

    /** How many parts a report covers. Structure: the number of parts there are. */
    public int partCount() {
        return states().size();
    }

    /**
     * The nominal transform rate, in bone transforms per second.
     *
     * <p>Nominal and not measured: it is the emission rate times the posed bone count, which is the
     * ceiling the dirty tracker subtracts from rather than the figure that goes out on the wire. See
     * {@link KnownGap#EMISSION_METRICS} for why the measured one is not readable from the engine.
     *
     * @return the ceiling on transforms emitted per second
     */
    public long nominalTransformsPerSecond() {
        return (long) this.emission.emissionHz() * (long) this.world.posedBones();
    }

    /** {@code values} as one line, or an empty string when there are none. */
    private static @NotNull String join(@NotNull List<String> values) {
        if (values.isEmpty()) {
            return "";
        }
        final StringJoiner joined = new StringJoiner("; ");
        for (final String value : values) {
            joined.add(value);
        }
        return joined.toString();
    }
}
