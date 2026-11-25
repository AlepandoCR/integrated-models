package pandastic.studio.models.internal.input;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.input.InputCapability;
import pandastic.studio.models.api.input.InputCombination;
import pandastic.studio.models.api.input.InputEvent;
import pandastic.studio.models.api.input.InputFamily;
import pandastic.studio.models.api.input.InputHandler;
import pandastic.studio.models.api.input.InputIssue;
import pandastic.studio.models.api.input.InputKey;
import pandastic.studio.models.api.input.InputRegistration;
import pandastic.studio.models.api.input.InputRelation;
import pandastic.studio.models.api.input.InputResponse;
import pandastic.studio.models.api.input.InputSnapshot;
import pandastic.studio.models.api.input.InputTuning;
import pandastic.studio.models.api.input.PlayerInputController;
import pandastic.studio.models.internal.sim.SimulationClock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The controller: one track per player, one registry of bindings, and the dispatch between them.
 *
 * <h2>What happens on one observation</h2>
 * <ol>
 *   <li>The capture source pushes an {@link InputDelivery} and the payload it saw.</li>
 *   <li>The player's {@link InputTrack} folds it in and, if the state actually changed, publishes an
 *       immutable {@link InputSnapshot}. Nothing changed means nothing is dispatched, which is what
 *       makes two capture sources seeing the same movement cost one comparison.</li>
 *   <li>Every live binding scoped to that player is evaluated against <strong>that one snapshot</strong>
 *       and the settings read <strong>once</strong> for the whole observation. A binding that matched
 *       last time and still matches is dispatched again only once per configured repeat interval.</li>
 *   <li>Each handler runs inside a {@code try}. What it throws is contained, counted and eventually
 *       closes its own binding.</li>
 * </ol>
 *
 * <p>Both single-read disciplines in there are the ones this project has documented failures from
 * elsewhere: one snapshot per observation, so two bindings cannot disagree about what the player did,
 * and one settings read per observation, so a reload cannot land between two of its own values.
 *
 * <h2>Why every matching handler runs</h2>
 * Not the most specific one, not the first one — all of them, in registration order. A registry that
 * picked a winner would need a rule for picking it, and every rule is wrong for somebody: "most
 * specific wins" breaks a plugin layering a modifier over a base action, "first wins" makes behaviour
 * depend on plugin load order. So nothing is dropped, and the overlap that a consumer probably did not
 * intend is <em>reported</em> at registration instead. That is the whole argument for
 * {@link InputIssue}.
 *
 * <h2>Threading</h2>
 * Every method is safe from any thread. The registry is a {@link CopyOnWriteArrayList} because it is
 * read on every observation and written a handful of times at consumer enable; the per-player tracks
 * live in a {@link ConcurrentHashMap}; the per-binding-per-player match state is mutated under its own
 * monitor. There is no lock held while a handler runs, which is not an optimisation — a handler that
 * blocked while this class held a lock would stall every other player's input.
 */
public final class PlayerInputControllerImpl implements PlayerInputController, InputSink {

    private final @NotNull InputSettingsSource settings;
    private final @NotNull SimulationClock clock;
    private final @NotNull RegionDispatcher regions;
    private final @NotNull InputFaultSink faults;
    private final @NotNull Set<InputCapability> capabilities;
    private final @Nullable String unavailableReason;

    private final @NotNull ConcurrentHashMap<UUID, InputTrack> tracks = new ConcurrentHashMap<>();
    private final @NotNull CopyOnWriteArrayList<Registered> bindings = new CopyOnWriteArrayList<>();

    /**
     * Assembles the controller.
     *
     * @param settings          where the tuning, the limits and the fault policy come from
     * @param clock             the monotonic clock staleness and repeat intervals are measured on
     * @param regions           how a handler gets onto the thread that may touch the player
     * @param faults            where a handler's exception goes
     * @param capabilities      what the started capture sources between them can do
     * @param unavailableReason one sentence naming what is missing and why, or {@code null} when
     *                          nothing is
     */
    public PlayerInputControllerImpl(
            @NotNull InputSettingsSource settings,
            @NotNull SimulationClock clock,
            @NotNull RegionDispatcher regions,
            @NotNull InputFaultSink faults,
            @NotNull Set<InputCapability> capabilities,
            @Nullable String unavailableReason
    ) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.regions = Objects.requireNonNull(regions, "regions");
        this.faults = Objects.requireNonNull(faults, "faults");
        this.capabilities = capabilities.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(capabilities));
        this.unavailableReason = unavailableReason;
    }

    // ------------------------------------------------------------- the api surface

    @Override
    public @NotNull Set<InputCapability> capabilities() {
        return this.capabilities;
    }

    @Override
    public @NotNull Optional<String> unavailableReason() {
        return Optional.ofNullable(this.unavailableReason);
    }

    @Override
    public @NotNull InputTuning tuning() {
        return this.settings.current().tuning();
    }

    @Override
    public @NotNull InputSnapshot snapshot(@NotNull Player player) {
        return snapshot(Objects.requireNonNull(player, "player").getUniqueId());
    }

    @Override
    public @NotNull InputSnapshot snapshot(@NotNull UUID playerId) {
        final InputTrack track = this.tracks.get(Objects.requireNonNull(playerId, "playerId"));
        if (track == null) {
            return InputSnapshot.IDLE;
        }

        final InputSettings current = this.settings.current();
        final long now = this.clock.nanoTime();
        final InputSnapshot snapshot = track.current(now, current.pulseVisibilityNanos());

        // A client that goes away mid-key never sends the release, so the last state the server saw
        // has that key held forever. Reporting it would drive a vehicle with nobody on it.
        return snapshot.isStale(now, current.tuning().staleAfterNanos()) ? InputSnapshot.IDLE : snapshot;
    }

    @Override
    public @NotNull InputRegistration bind(
            @NotNull InputCombination combination,
            @NotNull InputResponse response,
            @NotNull InputHandler handler
    ) {
        return register(null, combination, response, handler);
    }

    @Override
    public @NotNull InputRegistration bind(
            @NotNull UUID playerId,
            @NotNull InputCombination combination,
            @NotNull InputResponse response,
            @NotNull InputHandler handler
    ) {
        return register(Objects.requireNonNull(playerId, "playerId"), combination, response, handler);
    }

    @Override
    public @NotNull List<InputIssue> audit() {
        final List<Registered> live = live();
        final List<InputIssue> issues = new ArrayList<>();
        for (int index = 0; index < live.size(); index++) {
            final Registered binding = live.get(index);
            issues.addAll(capabilityIssues(binding.combination, binding.response));
            for (int other = index + 1; other < live.size(); other++) {
                relationIssue(binding, live.get(other)).ifPresent(issues::add);
            }
        }
        return List.copyOf(issues);
    }

    @Override
    public int bindingCount() {
        return live().size();
    }

    // -------------------------------------------------------------- the sink surface

    @Override
    public boolean movement(@NotNull InputDelivery delivery, int movementBits) {
        Objects.requireNonNull(delivery, "delivery");
        final InputSnapshot snapshot =
                track(delivery.playerId()).held(movementBits, delivery.nowNanos());
        return snapshot != null && dispatch(delivery, snapshot);
    }

    @Override
    public boolean pulse(@NotNull InputDelivery delivery, @NotNull InputKey key) {
        Objects.requireNonNull(delivery, "delivery");
        if (Objects.requireNonNull(key, "key").family() != InputFamily.ACTION) {
            // A held key pushed as a pulse would publish a press that nothing ever releases, because
            // the release of a held key is the absence of its bit in the next movement observation.
            throw new IllegalArgumentException(key + " is not a momentary input; push it as movement");
        }
        return dispatch(delivery, track(delivery.playerId()).pulse(key, delivery.nowNanos()));
    }

    @Override
    public boolean hotbarSlot(@NotNull InputDelivery delivery, int slot) {
        Objects.requireNonNull(delivery, "delivery");
        return dispatch(delivery, track(delivery.playerId()).hotbarSlot(slot, delivery.nowNanos()));
    }

    @Override
    public void forget(@NotNull UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        this.tracks.remove(playerId);
        // Also from every binding's match state, or a server that has seen ten thousand players keeps
        // ten thousand entries per binding for players that will never come back.
        for (final Registered binding : this.bindings) {
            binding.matches.remove(playerId);
        }
    }

    /**
     * Drops every track and every binding.
     *
     * <p>For shutdown. Closing the bindings rather than only clearing the list is what makes a
     * consumer's retained {@link InputRegistration} report {@code isActive() == false} afterwards,
     * instead of looking live against a controller that no longer dispatches.
     *
     * @return how many bindings were closed
     */
    public int shutdown() {
        final int closed = this.bindings.size();
        for (final Registered binding : this.bindings) {
            binding.active = false;
        }
        this.bindings.clear();
        this.tracks.clear();
        return closed;
    }

    // ------------------------------------------------------------------- internals

    private @NotNull InputTrack track(@NotNull UUID playerId) {
        return this.tracks.computeIfAbsent(playerId, ignored -> new InputTrack());
    }

    private @NotNull List<Registered> live() {
        final List<Registered> live = new ArrayList<>(this.bindings.size());
        for (final Registered binding : this.bindings) {
            if (binding.active) {
                live.add(binding);
            }
        }
        return live;
    }

    /**
     * Evaluates every binding against one snapshot and runs the ones that match.
     *
     * @return whether any handler asked for the input to be stopped
     */
    private boolean dispatch(@NotNull InputDelivery delivery, @NotNull InputSnapshot snapshot) {
        // One read for the whole observation. Two reads here could judge the same input against two
        // generations of configuration, which is the hazard the settings source's contract names.
        final InputSettings current = this.settings.current();
        final UUID playerId = delivery.playerId();

        boolean suppressed = false;
        for (final Registered binding : this.bindings) {
            if (!binding.active || !binding.appliesTo(playerId)) {
                continue;
            }
            final boolean matched = binding.combination.matches(snapshot, current.tuning());
            if (!binding.shouldDispatch(playerId, matched, snapshot.nowNanos(),
                    current.tuning().repeatIntervalNanos())) {
                continue;
            }
            suppressed |= run(binding, delivery, snapshot);
        }
        return suppressed;
    }

    /** Runs one handler, contains what it throws, and answers whether it suppressed. */
    private boolean run(
            @NotNull Registered binding,
            @NotNull InputDelivery delivery,
            @NotNull InputSnapshot snapshot
    ) {
        final InputEvent event = new InputEvent(
                delivery.player(),
                binding.combination,
                snapshot,
                binding.response,
                delivery.dispatch(),
                binding.response == InputResponse.SUPPRESS
                        ? delivery.suppressor()
                        : InputEvent.UNSUPPORTED,
                this.regions.forPlayer(delivery.player()));

        final String described = binding.combination.describe();
        try {
            binding.handler.onInput(event);
            this.faults.succeeded(described);
        } catch (final RuntimeException | LinkageError failure) {
            // RuntimeException and LinkageError, not Throwable: an Error the JVM raised about itself —
            // out of memory, a stack overflow in our own frames — is not something to count and carry
            // on from, and swallowing it here would hide it on a network thread. A LinkageError is
            // caught because a consumer's handler can reference a class its own plugin has since
            // unloaded, which is a consumer fault and survivable.
            if (!this.faults.failed(described, failure)) {
                binding.close();
            }
        }
        return event.isSuppressed();
    }

    private @NotNull InputRegistration register(
            @Nullable UUID scope,
            @NotNull InputCombination combination,
            @NotNull InputResponse response,
            @NotNull InputHandler handler
    ) {
        Objects.requireNonNull(combination, "combination");
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(handler, "handler");

        final List<InputIssue> issues = new ArrayList<>();
        if (!combination.isSatisfiable()) {
            issues.add(InputIssue.of(InputIssue.Kind.UNSATISFIABLE,
                    "No input can satisfy " + combination.describe()
                            + ", so the handler would never run"));
        }

        // One read for the whole registration, as the source's contract requires.
        final int maxBindings = this.settings.current().maxBindings();
        final List<Registered> live = live();
        if (live.size() >= maxBindings) {
            issues.add(InputIssue.of(InputIssue.Kind.LIMIT_REACHED,
                    "This server is configured to hold at most " + maxBindings
                            + " input bindings and already holds that many; " + combination.describe()
                            + " was not registered"));
        }

        if (issues.stream().anyMatch(InputIssue::isRefusal)) {
            return InputRegistration.refused(combination, issues);
        }

        issues.addAll(capabilityIssues(combination, response));

        final Registered registered = new Registered(scope, combination, response, handler, issues);
        for (final Registered existing : live) {
            if (registered.couldCollideWith(existing)) {
                relationIssue(registered, existing).ifPresent(registered.issues::add);
            }
        }

        this.bindings.add(registered);
        return registered;
    }

    /** What is wrong with asking for this combination and this response on this server. */
    private @NotNull List<InputIssue> capabilityIssues(
            @NotNull InputCombination combination,
            @NotNull InputResponse response
    ) {
        final List<InputIssue> issues = new ArrayList<>();
        final EnumSet<InputFamily> families = EnumSet.noneOf(InputFamily.class);
        for (final InputKey key : combination.requiredKeys()) {
            families.add(key.family());
        }
        for (final InputKey key : combination.forbiddenKeys()) {
            families.add(key.family());
        }

        for (final InputFamily family : families) {
            if (!canObserve(family)) {
                issues.add(InputIssue.of(InputIssue.Kind.FAMILY_UNOBSERVABLE,
                        combination.describe() + " names a " + family
                                + " input, which this server's input capture cannot observe; the"
                                + " binding stays registered and will not match until it can"));
            }
            if (response == InputResponse.SUPPRESS && !canSuppress(family)) {
                issues.add(InputIssue.of(InputIssue.Kind.SUPPRESSION_UNSUPPORTED,
                        combination.describe() + " asked to suppress a " + family
                                + " input, which this server's input capture cannot do; the handler"
                                + " will run and suppress() will answer false"));
            }
        }
        return issues;
    }

    /** The finding about how two bindings stand to each other, if there is one worth making. */
    private static @NotNull Optional<InputIssue> relationIssue(
            @NotNull Registered binding,
            @NotNull Registered other
    ) {
        final InputRelation relation = binding.combination.relationTo(other.combination);
        final InputIssue.Kind kind = switch (relation) {
            case EQUIVALENT -> InputIssue.Kind.EQUIVALENT;
            case SHADOWS -> InputIssue.Kind.SHADOWS;
            case SHADOWED_BY -> InputIssue.Kind.SHADOWED_BY;
            // Overlapping and mutually exclusive bindings are both normal. Reporting them would bury
            // the two findings that are not.
            case OVERLAPS, INDEPENDENT -> null;
        };
        if (kind == null) {
            return Optional.empty();
        }

        final String message = switch (relation) {
            case EQUIVALENT -> binding.combination.describe() + " has the same requirements as the"
                    + " already-bound " + other.combination.describe()
                    + "; both handlers will run, in registration order";
            case SHADOWS -> binding.combination.describe() + " fires whenever the already-bound "
                    + other.combination.describe() + " does, because its requirements are weaker";
            default -> "the already-bound " + other.combination.describe()
                    + " fires whenever " + binding.combination.describe() + " does";
        };
        return Optional.of(new InputIssue(kind, message, other.combination));
    }

    /**
     * One registered binding.
     *
     * <p>Also the {@link InputRegistration} the consumer holds, rather than a separate handle over it:
     * two objects would mean a consumer could hold a registration whose binding had been replaced, and
     * the only state either needs is the one {@code active} flag.
     */
    private final class Registered implements InputRegistration {

        /** The player this binding is scoped to, or {@code null} for every player. */
        private final @Nullable UUID scope;

        private final @NotNull InputCombination combination;
        private final @NotNull InputResponse response;
        private final @NotNull InputHandler handler;
        private final @NotNull List<InputIssue> issues;

        /** Per-player match state, so a repeat interval is per player rather than per binding. */
        private final @NotNull ConcurrentHashMap<UUID, MatchState> matches = new ConcurrentHashMap<>();

        private volatile boolean active = true;

        private Registered(
                @Nullable UUID scope,
                @NotNull InputCombination combination,
                @NotNull InputResponse response,
                @NotNull InputHandler handler,
                @NotNull List<InputIssue> issues
        ) {
            this.scope = scope;
            this.combination = combination;
            this.response = response;
            this.handler = handler;
            this.issues = new ArrayList<>(issues);
        }

        @Override
        public @NotNull InputCombination combination() {
            return this.combination;
        }

        @Override
        public @NotNull List<InputIssue> issues() {
            return List.copyOf(this.issues);
        }

        @Override
        public boolean isActive() {
            return this.active;
        }

        @Override
        public void close() {
            this.active = false;
            this.matches.clear();
            PlayerInputControllerImpl.this.bindings.remove(this);
        }

        private boolean appliesTo(@NotNull UUID playerId) {
            return this.scope == null || this.scope.equals(playerId);
        }

        /** Whether these two bindings could ever fire for the same player. */
        private boolean couldCollideWith(@NotNull Registered other) {
            return this.scope == null || other.scope == null || this.scope.equals(other.scope);
        }

        /**
         * Whether this delivery should reach the handler.
         *
         * <p>Three cases, and the middle one is the reason this is not just {@code matched}: a held
         * combination matches on every publication, which for a movement key is every client tick, so
         * a handler bound to "sneak held" would run twenty times a second. The first match always
         * dispatches; a continuing match dispatches at the configured repeat interval; a stopped match
         * re-arms.
         */
        private boolean shouldDispatch(
                @NotNull UUID playerId,
                boolean matched,
                long nowNanos,
                long repeatIntervalNanos
        ) {
            final MatchState state = this.matches.computeIfAbsent(playerId, id -> new MatchState());
            synchronized (state) {
                if (!matched) {
                    state.matching = false;
                    return false;
                }
                if (!state.matching) {
                    state.matching = true;
                    state.lastDispatchNanos = nowNanos;
                    return true;
                }
                if (nowNanos - state.lastDispatchNanos >= repeatIntervalNanos) {
                    state.lastDispatchNanos = nowNanos;
                    return true;
                }
                return false;
            }
        }
    }

    /** Per binding, per player: whether it was matching and when it last fired. */
    private static final class MatchState {

        private boolean matching;
        private long lastDispatchNanos;
    }
}
