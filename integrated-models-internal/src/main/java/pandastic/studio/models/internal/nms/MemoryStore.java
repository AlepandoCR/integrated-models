package pandastic.studio.models.internal.nms;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import pandastic.studio.models.api.ai.AiMemory;

/**
 * Raw access to one creature's memories, with no policy on top of it.
 *
 * <h2>Why the untyped shape, when the api is typed</h2>
 * Because the thing underneath is untyped. A brain holds one slot per memory identifier and will store
 * any object in it; the type is a promise the plan made, not something the server checks. So the seam
 * passes {@link Object} and the narrowing happens exactly once, above it, in
 * {@link AiMemory#cast(Object)} — which is a checked cast, so a value of the wrong type fails naming
 * both types instead of being quietly assigned and failing three frames later.
 *
 * <p>The alternative — a generic method per operation — would need an unchecked cast below the seam to
 * satisfy it, in the module where a cast is least verifiable. Pushing the cast up is what keeps this
 * project's rule that an unchecked cast needs a comment proving it safe, because here there is one and
 * it is {@code Class::cast}.
 *
 * <h2>What this interface deliberately does not do</h2>
 * It does not check whether the memory belongs to the installed plan, it does not check the calling
 * thread, and it does not bound an expiry. All three are
 * {@link pandastic.studio.models.internal.ai.GuardedMemoryView}'s job, above the seam, where the plan
 * and the configuration are. An implementation here is the thinnest possible wrapper over the server's
 * own four operations — which is the point: the policy is testable without a server and the adapter has
 * nothing in it to get wrong.
 *
 * <h2>Threading</h2>
 * Owning thread only, every method, and unchecked here. The guard is above.
 */
public interface MemoryStore {

    /**
     * The value stored for {@code memory}, or {@code null} if there is none.
     *
     * <p>{@code null} rather than {@link java.util.Optional}, because this is the inner call of a path
     * that runs inside a creature's tick and the {@code Optional} belongs to the one public method that
     * wraps it.
     *
     * <p>Always {@code null} for an {@link AiMemory#vanilla(String)} reference: a vanilla memory's value
     * is a server-internal object that must not cross this seam. Use {@link #present(AiMemory)}.
     *
     * @param memory which memory to read
     * @return the stored value, or {@code null}
     */
    @Nullable Object read(@NotNull AiMemory<?> memory);

    /**
     * Stores {@code value} for {@code memory}, expiring after {@code expiryTicks}.
     *
     * @param memory      which memory to write; never a vanilla reference, which the type system
     *                    already prevents at the api surface
     * @param value       the value
     * @param expiryTicks ticks the value survives, or {@link AiMemory#NEVER_EXPIRES}
     */
    void write(@NotNull AiMemory<?> memory, @NotNull Object value, long expiryTicks);

    /**
     * Whether {@code memory} holds a value right now.
     *
     * <p>The only readable thing about a vanilla memory, and the question an activity condition of
     * {@link pandastic.studio.models.api.ai.AiMemoryStatus#VALUE_PRESENT} asks.
     *
     * @param memory which memory to test
     * @return whether it holds a value
     */
    boolean present(@NotNull AiMemory<?> memory);

    /**
     * Erases {@code memory}.
     *
     * @param memory which memory to erase
     */
    void erase(@NotNull AiMemory<?> memory);
}
