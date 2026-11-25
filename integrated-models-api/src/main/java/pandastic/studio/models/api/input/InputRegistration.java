package pandastic.studio.models.api.input;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * A live binding, and what the controller had to say about it.
 *
 * <h2>Why registration hands something back rather than returning {@code void}</h2>
 * Two reasons, and both are things a consumer cannot find out any other way. The first is
 * {@link #issues()}: a shadowing combination, a suppression this server cannot perform, a family
 * nothing can observe. The second is {@link #close()}: a plugin that binds in {@code onEnable} and is
 * then reloaded has to be able to take its handlers off, and a registry keyed on the handler object
 * would make an anonymous lambda unremovable.
 *
 * <h2>Closing is idempotent and safe from any thread</h2>
 * A consumer disabling itself does not have to care whether an input is being dispatched concurrently:
 * a closed registration stops matching, and a delivery already in flight completes. It implements
 * {@link AutoCloseable} so a consumer that binds for the duration of something can use
 * try-with-resources, though the common case is a field held for the plugin's lifetime.
 */
public interface InputRegistration extends AutoCloseable {

    /** What was bound. */
    @NotNull InputCombination combination();

    /**
     * What the controller found worth saying about this binding.
     *
     * <p>Empty in the ordinary case. When any element {@link InputIssue#isRefusal() refuses}, the
     * binding is not live and {@link #isActive()} is {@code false} from the start.
     *
     * @return an unmodifiable list, in the order the findings were made
     */
    @NotNull List<InputIssue> issues();

    /**
     * Whether this binding will still be matched.
     *
     * <p>{@code false} either because a finding refused it or because {@link #close()} has been called.
     * A consumer that wants to distinguish the two reads {@link #issues()}.
     */
    boolean isActive();

    /**
     * Stops matching this binding.
     *
     * <p>Does not throw. Declared without the {@code throws} of {@link AutoCloseable#close()} on
     * purpose: there is nothing a caller could do about a failure to unregister, and a checked
     * exception here would put a {@code try} around every teardown.
     */
    @Override
    void close();

    /**
     * A registration that was refused.
     *
     * <p>Returned rather than throwing, so a consumer binding several combinations in a loop collects
     * every finding instead of dying on the first one — and so that the shape of the answer does not
     * depend on whether the binding was accepted.
     *
     * @param combination what was asked for
     * @param issues      why it was refused; at least one must be a refusal
     * @return an inactive registration carrying {@code issues}
     */
    @Contract(value = "_, _ -> new", pure = true)
    static @NotNull InputRegistration refused(
            @NotNull InputCombination combination,
            @NotNull List<InputIssue> issues
    ) {
        Objects.requireNonNull(combination, "combination");
        final List<InputIssue> copy = List.copyOf(Objects.requireNonNull(issues, "issues"));
        if (copy.stream().noneMatch(InputIssue::isRefusal)) {
            throw new IllegalArgumentException(
                    "A refused registration must carry at least one refusing issue");
        }
        return new InputRegistration() {

            @Override
            public @NotNull InputCombination combination() {
                return combination;
            }

            @Override
            public @NotNull List<InputIssue> issues() {
                return copy;
            }

            @Override
            public boolean isActive() {
                return false;
            }

            @Override
            public void close() {
                // Nothing was registered, so there is nothing to undo. Still a no-op rather than a
                // throw: a consumer closing every registration it holds should not have to ask which
                // of them succeeded.
            }
        };
    }
}
