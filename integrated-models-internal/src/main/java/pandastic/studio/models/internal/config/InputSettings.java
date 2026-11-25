package pandastic.studio.models.internal.config;

import pandastic.studio.core.service.api.config.ConfigFormat;
import pandastic.studio.core.service.api.config.ConfigValue;
import pandastic.studio.core.service.api.config.Configurable;

/**
 * Declaration of {@code input.yml}: how a player's keys and clicks are observed, judged and reported.
 *
 * <h2>Why every one of these is operator-facing</h2>
 * Because every one of them is a statement about hands and latency rather than about code. How long two
 * taps may be apart and still be one gesture, how long a key must be down before it counts as held, how
 * often a still-held combination should fire again — these depend on the player, the client's frame
 * rate and the route between them, and nothing in this plugin can derive any of them. A server where
 * everybody is on the same continent and a server where half the players are 250 ms away want different
 * double-tap windows, and the second one will find out by feel.
 *
 * <p>The two switches are a different kind of key: they are what an operator reaches for when something
 * is wrong. {@link #packetSourceEnabled()} takes a handler out of every player's network pipeline
 * without removing the plugin, and {@link #actionCaptureEnabled()} stops five event listeners firing for
 * every player on a server whose plugins only use movement.
 *
 * <h2>Not to be confused with its namesake</h2>
 * {@link pandastic.studio.models.internal.input.InputSettings} is a different type with the same simple
 * name: it is the assembled, already-validated bundle the input layer reads once per observation. This
 * one is the <em>file</em> — the keys, the comments and the defaults. The project has the same
 * deliberate shadow for render, simulation, view, binding and command; nothing outside this package
 * should need to name this interface, because what the rest of the plugin sees is {@link ConfigSnapshot}.
 *
 * @see ConfigMapper
 * @see ConfigSnapshot
 */
@Configurable(name = "InputConfig", format = ConfigFormat.YAML)
public interface InputSettings {

    /**
     * Longest gap between two presses of a key that still counts as a double tap, in milliseconds.
     *
     * <p>250 ms is about the upper end of a deliberate double tap on a keyboard and the lower end of
     * two separate intentional presses, which is why it is the default and why it is the key most worth
     * tuning: too wide and a player walking in stop-start traffic triggers a dash they did not ask for,
     * too narrow and the gesture simply does not work for anybody with a slow connection.
     *
     * <p>Measured between the two presses as the server saw them, so it already includes the client's
     * send interval and the network. A player's own perception of the gap is shorter than this number.
     *
     * @return the double-tap window, in milliseconds
     */
    @ConfigValue(comment = "Longest gap between two presses of a key that still counts as a double tap, in milliseconds. Measured as the server saw them, so it includes the network. Too wide and stop-start walking triggers a dash nobody asked for; too narrow and the gesture does not work for distant players.")
    default int doubleTapWindowMillis() {
        return 250;
    }

    /**
     * How long a key must be down for an unqualified hold combination to match, in milliseconds.
     *
     * <p>Only the default: a consumer that cares states its own duration on the combination, the same
     * way a vehicle profile states its own speeds. This is the figure every consumer that did not care
     * gets, which makes it the one that has to feel right — half a second is long enough that a normal
     * press does not reach it and short enough that a player does not think the control is broken.
     *
     * @return the hold threshold, in milliseconds
     */
    @ConfigValue(comment = "How long a key must be down for an unqualified hold combination to match, in milliseconds. Half a second is long enough that a normal press does not reach it and short enough not to feel broken. A consumer that cares states its own duration instead.")
    default int holdThresholdMillis() {
        return 500;
    }

    /**
     * Shortest interval at which a still-matching combination is dispatched again, in milliseconds.
     *
     * <p>A held combination matches on every publication, and for a movement key that is every client
     * tick — so without this a handler bound to "sneak held" runs twenty times a second for every
     * player holding sneak. The first match always fires immediately; this only bounds the repeats.
     *
     * <p>Raising it is the cheapest way to cut the cost of a plugin that binds several held
     * combinations. Lowering it towards one client tick makes a held binding behave like a per-tick
     * poll, which is what the snapshot is for and is better done by polling.
     *
     * @return the repeat interval, in milliseconds
     */
    @ConfigValue(comment = "Shortest interval at which a still-matching combination is dispatched again, in milliseconds. A held combination matches every client tick, so without this a 'sneak held' handler runs twenty times a second. The first match always fires immediately.")
    default int repeatIntervalMillis() {
        return 250;
    }

    /**
     * How long after the last observation a player's input stops being trusted, in milliseconds.
     *
     * <p>Not a performance figure, a correctness one. A client that disconnects or freezes mid-key
     * never sends the release, so the last state the server saw has that key held — forever. Anything
     * polling it would keep driving a vehicle with nobody at the controls. Past this window the
     * controller answers "nothing held" instead.
     *
     * <p>The trade is against a legitimately idle player: the client only sends input when it changes,
     * so a player holding forward steadily and smoothly can go a while without a new observation. Too
     * low and that player's vehicle stutters; too high and a dropped connection keeps driving.
     *
     * @return the staleness window, in milliseconds
     */
    @ConfigValue(comment = "How long after the last observation a player's input stops being trusted, in milliseconds. A client that disconnects mid-key never sends the release, so without this a dropped connection drives a vehicle forever. Too low and a steadily-held key stutters, because the client only sends input when it changes.")
    default int staleAfterMillis() {
        return 1_500;
    }

    /**
     * How long a momentary input stays visible to a polling reader, in milliseconds.
     *
     * <p>A click is true for one publication, which is exactly right for a bound handler — it is
     * dispatched with that publication and sees it — and useless for a control step that polls twenty
     * times a second and would miss almost every one. So the flag lingers, and this is how long for.
     *
     * <p>Zero would hide every action input from every polling consumer; unbounded would turn one click
     * into an attack flag that reads as held until the player next moves, because the client only sends
     * a movement packet when the movement changes. The default is two client ticks, which is long
     * enough for a 20 Hz control step to see it and short enough that two clicks are never one.
     *
     * @return the pulse visibility window, in milliseconds
     */
    @ConfigValue(comment = "How long a momentary input (attack, use, drop, swap, slot change) stays visible to a polling reader, in milliseconds. A bound handler always sees it; a control step polling at 20 Hz would miss it without this. The default is two client ticks.")
    default int actionPulseVisibilityMillis() {
        return 100;
    }

    /**
     * Whether packet-level input capture is used when this server has an adapter for it.
     *
     * <p>An operator kill switch rather than a feature flag. The packet source is the only thing that
     * can stop a movement input from happening and the only thing that sees an input before the server
     * applies it — but it does that by putting a handler in every player's network pipeline, and an
     * operator debugging a connection problem has to be able to take it out without removing the
     * plugin.
     *
     * <p>Switching it off costs {@code SUPPRESS_MOVEMENT} and {@code OBSERVE_BEFORE_SERVER}. Movement
     * and action observation and action suppression all keep working through the platform's own api,
     * and the reduced capability is logged once at start-up.
     *
     * @return whether to install the packet source
     */
    @ConfigValue(comment = "Whether to use packet-level input capture where this server has an adapter for it. It is the only way to suppress a movement key and the only way to see an input before the server applies it, and it works by putting a handler in every player's network pipeline. Off costs those two capabilities and nothing else; everything is still observed through the platform's own api.")
    default boolean packetSourceEnabled() {
        return true;
    }

    /**
     * Whether the five momentary action inputs are observed at all.
     *
     * <p>Observing them means five more event listeners firing for every player on the server — every
     * click, every dropped item, every hotbar scroll. A server whose plugins only drive vehicles with
     * movement keys is paying that for nothing, and this is how it stops.
     *
     * <p>Off, a combination naming an action key never matches. It is still registered and still
     * reported as naming an unobservable family, so a consumer can tell the difference between "this
     * server cannot" and "my combination is wrong".
     *
     * @return whether to observe attack, use, drop, swap and slot selection
     */
    @ConfigValue(comment = "Whether to observe the five momentary action inputs: attack, use, drop, swap hands and hotbar slot. Observing them means five more event listeners firing for every player on the server. Off, a combination naming one never matches and says so at registration.")
    default boolean actionCaptureEnabled() {
        return true;
    }

    /**
     * How many input bindings the controller will hold.
     *
     * <p>Every live binding is evaluated against every publication for every player it is scoped to,
     * so this is the bound on a cost that a downstream plugin can impose on the server without the
     * operator agreeing to it. A plugin that leaks registrations — binding in a handler, or in a
     * per-player setup that is called on every respawn — is caught here rather than discovered as a
     * slow drift in tick time.
     *
     * <p>A registration past the limit is refused and says so, which is a consumer-visible failure
     * rather than a silent one.
     *
     * @return the binding ceiling
     */
    @ConfigValue(comment = "How many input bindings the controller will hold. Every live binding is evaluated against every publication for every player it is scoped to, so this bounds a cost a downstream plugin can impose. A registration past the limit is refused and says so.")
    default int maxBindings() {
        return 256;
    }

    /**
     * Seconds between repeat reports of one failing input handler.
     *
     * <p>A handler that throws throws on every matching input, which for a held movement combination is
     * several times a second per player: without a throttle it fills the disk and pushes every other
     * line out of the log. The consecutive count is carried into each report, because "this failed
     * twice" and "this failed nine hundred times" call for different responses and look identical
     * without it.
     *
     * <p>Five seconds, the same figure {@code ai.yml} uses for the same job and for the same reason:
     * long enough that a broken body cannot flood the log, short enough that an operator watching the
     * console while they reproduce the problem sees it happen.
     *
     * @return the minimum interval between reports, in seconds
     */
    @ConfigValue(comment = "Seconds between repeat reports of one failing input handler. A broken handler throws on every matching input, which for a held combination is several times a second per player. Each report carries the consecutive failure count.")
    default float faultLogIntervalSeconds() {
        return 5F;
    }

    /**
     * Consecutive failures after which a binding is closed.
     *
     * <p>The half of the failure policy that is about the server rather than the log. A handler that
     * throws every time is doing nothing except costing a stack trace on a region or network thread, so
     * past this count its binding is closed and the consumer's retained registration reports itself
     * inactive — which is the one signal a consumer can actually notice.
     *
     * <p>Counted consecutively, so a delivery that succeeds resets it. A handler that fails once an
     * hour over something transient should not be closed on its third hour.
     *
     * @return the consecutive failure limit
     */
    @ConfigValue(comment = "Consecutive failures after which an input binding is closed. A handler that throws every time costs a stack trace on a region or network thread and does nothing else. A delivery that succeeds resets the count.")
    default int faultsBeforeDisable() {
        return 3;
    }
}
