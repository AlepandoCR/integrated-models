package pandastic.studio.models.api.ai;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Reading and writing what a creature remembers.
 *
 * <h2>Thread rule, and it is the strict one</h2>
 * Every method here must be called from the thread that owns the creature. On a regionised server that
 * is the region thread the creature's region is ticking on, which is where a behaviour body and a
 * sensor body both already run — so inside the plan you wrote, this rule costs you nothing and you
 * cannot get it wrong.
 *
 * <p>Where it can be got wrong is outside: holding an {@link AiController} and reading a memory from an
 * event handler for a <em>different</em> entity, from a scheduled task, or from the simulation thread.
 * The platform does not check this, so an implementation of this interface has to, and the one this
 * plugin installs does — it refuses rather than returning a value that was read while another thread
 * was writing it. If you want a value off the owning thread, publish it as an {@link AiIntent}
 * instead; that is what an intent is for.
 *
 * <h2>Why an unknown memory throws instead of answering empty</h2>
 * Because there is no state of the world in which the answer is interesting. A memory the installed
 * plan does not contain is not a memory the creature has forgotten — it is a memory nothing will ever
 * write, so {@code empty} would be an honest answer to the wrong question and would make a behaviour
 * that reads the wrong key look like a behaviour whose trigger never fires. That is the exact failure
 * the server's own API produces, in the direction that is hardest to notice, and it is the reason this
 * interface exists rather than the brain being exposed directly.
 *
 * @see AiMemory
 */
public interface AiMemoryView {

    /**
     * What the creature currently remembers for {@code memory}.
     *
     * <p>Empty means the memory holds no value: either nothing has written it, or what was written has
     * expired. Always empty for an {@link AiMemory#vanilla(String)} reference, whose value is a
     * server-internal object this layer will not hand out; use {@link #has(AiMemory)} for those.
     *
     * @param memory      the memory to read, which must be one the installed plan declared
     * @param <ValueType> the value type
     * @return the remembered value, or empty
     * @throws IllegalArgumentException if the installed plan does not declare {@code memory}
     * @throws IllegalStateException    if called from a thread that does not own the creature
     */
    <ValueType> @NotNull Optional<ValueType> get(@NotNull AiMemory<ValueType> memory);

    /**
     * Remembers {@code value}, for {@link AiMemory#getDefaultExpiryTicks()}.
     *
     * @param memory      the memory to write, which must be one the installed plan declared
     * @param value       the value; never {@code null}, because forgetting is {@link #clear(AiMemory)}
     *                    and a {@code null} that meant "forget" would make the two indistinguishable at
     *                    the call site
     * @param <ValueType> the value type
     * @throws IllegalArgumentException if the installed plan does not declare {@code memory}
     * @throws IllegalStateException    if called from a thread that does not own the creature
     */
    <ValueType> void set(@NotNull AiMemory<ValueType> memory, @NotNull ValueType value);

    /**
     * Remembers {@code value} for {@code expiryTicks}, overriding the memory's own default.
     *
     * <p>For the case the default cannot express: a cooldown whose length depends on what just
     * happened. An expiry of {@link AiMemory#NEVER_EXPIRES} means the value stays until something
     * erases it.
     *
     * @param memory      the memory to write, which must be one the installed plan declared
     * @param value       the value
     * @param expiryTicks ticks the value survives, or {@link AiMemory#NEVER_EXPIRES}
     * @param <ValueType> the value type
     * @throws IllegalArgumentException if the plan does not declare {@code memory}, or the expiry is
     *                                 negative or above the configured ceiling
     * @throws IllegalStateException    if called from a thread that does not own the creature
     */
    <ValueType> void set(@NotNull AiMemory<ValueType> memory, @NotNull ValueType value, long expiryTicks);

    /**
     * Whether {@code memory} holds a value right now.
     *
     * <p>The only question that can be asked about an {@link AiMemory#vanilla(String)} reference, and
     * the one an activity condition of {@link AiMemoryStatus#VALUE_PRESENT} asks.
     *
     * @param memory the memory to test, which must be one the installed plan declared
     * @return whether it holds a value
     * @throws IllegalArgumentException if the installed plan does not declare {@code memory}
     * @throws IllegalStateException    if called from a thread that does not own the creature
     */
    boolean has(@NotNull AiMemory<?> memory);

    /**
     * Forgets {@code memory}.
     *
     * <p>Idempotent: forgetting something the creature does not remember is not an error, because a
     * behaviour that clears a cooldown on stop should not have to check whether it set one.
     *
     * @param memory the memory to erase, which must be one the installed plan declared
     * @throws IllegalArgumentException if the installed plan does not declare {@code memory}
     * @throws IllegalStateException    if called from a thread that does not own the creature
     */
    void clear(@NotNull AiMemory<?> memory);
}
