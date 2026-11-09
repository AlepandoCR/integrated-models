package pandastic.studio.models.nms;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import org.jetbrains.annotations.NotNull;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;

/**
 * The two things about a brain that cannot be reached without reflection, and nothing else.
 *
 * <h2>Why only two, when the brain API is mostly private</h2>
 * Because the parts that matter are public. Rebuilding a brain needs {@code pack()},
 * {@code Brain.provider(...)}, {@code makeBrain(...)}, {@code stopAll(...)},
 * {@code setCoreActivities(...)}, {@code setDefaultActivity(...)} and {@code useDefaultActivity()},
 * and every one of those is public API on the server side. What is not reachable is
 * <em>installing</em> the result:
 *
 * <ol>
 *   <li><strong>The field.</strong> {@code LivingEntity.brain} is {@code protected} and is not in the
 *       platform's access transformers, so there is no way to assign it from outside the class
 *       hierarchy. A {@link VarHandle} is used rather than {@code Field#set} because the write happens
 *       on a live creature's own thread and a var handle is a plain field store after the first
 *       resolution, where reflection keeps a security check on every call.</li>
 *   <li><strong>The species' own brain factory.</strong> {@code LivingEntity#makeBrain(Brain.Packed)}
 *       is {@code protected} and is overridden per species — it is the method that knows a villager's
 *       brain is a villager's. Reaching it is what makes uninstalling a plan a genuine restoration
 *       rather than a replacement with something generic: the creature gets back the brain its own
 *       class would have built, carrying the memories it currently holds.</li>
 * </ol>
 *
 * <p>Both are resolved once, lazily, in a static initialiser, and a failure to resolve either is
 * reported as an {@link ExceptionInInitializerError} that names the member. That is the correct loudness
 * for this: an adapter that cannot reach the brain field cannot install anything at all, and the
 * resolution failure happens at class load — on the plugin's enable path — rather than inside a
 * creature's tick.
 *
 * <h2>Why {@code privateLookupIn} works here without a module flag</h2>
 * The server's classes are loaded from the classpath, so they are in the unnamed module, which is open
 * to everything by definition. No {@code --add-opens} is needed and none should be added: a build that
 * needed one would be a build where the server had been modularised, and the right response then is to
 * find out rather than to have the flag already hiding it.
 *
 * <h2>Threading</h2>
 * Both operations touch a live creature and are owning-thread only. Resolution is thread-safe by
 * virtue of being a static initialiser; the handles themselves are immutable and shareable.
 */
final class BrainReflection {

    /** {@code LivingEntity.brain} — protected, not access-transformed, so it needs a handle. */
    private static final @NotNull VarHandle BRAIN_FIELD;

    /** {@code LivingEntity#makeBrain(Brain.Packed)} — protected and overridden per species. */
    private static final @NotNull MethodHandle MAKE_BRAIN;

    static {
        try {
            final MethodHandles.Lookup lookup =
                    MethodHandles.privateLookupIn(LivingEntity.class, MethodHandles.lookup());
            BRAIN_FIELD = lookup.findVarHandle(LivingEntity.class, "brain", Brain.class);
            MAKE_BRAIN = lookup.findVirtual(LivingEntity.class, "makeBrain",
                    MethodType.methodType(Brain.class, Brain.Packed.class));
        } catch (final ReflectiveOperationException unreachable) {
            // Deliberately fatal at class load. An adapter that cannot reach these two members cannot
            // install anything, and discovering that on the enable path with the member named is far
            // better than discovering it inside a creature's tick with a NullPointerException.
            throw new ExceptionInInitializerError(unreachable);
        }
    }

    private BrainReflection() {
        throw new AssertionError("No instances.");
    }

    /**
     * Replaces {@code entity}'s brain.
     *
     * <p>Owning-thread only. The store is plain rather than volatile, matching the field's own
     * declaration: the creature's tick reads it from the same thread this writes it on, so there is no
     * cross-thread publication to order and a volatile store would be paying for a guarantee nothing
     * needs.
     *
     * @param entity the creature
     * @param brain  the brain to install
     */
    static void setBrain(@NotNull LivingEntity entity, @NotNull Brain<?> brain) {
        BRAIN_FIELD.set(entity, brain);
    }

    /**
     * Builds the brain {@code entity}'s own species would have built, carrying {@code packed}.
     *
     * <p>Virtual dispatch, so a villager gets a villager's brain. That is the entire reason this is a
     * method handle on the protected method rather than a reimplementation: a generic brain would be
     * correct and would leave the creature behaviourally empty, which looks exactly like a creature
     * whose plan is still installed and broken.
     *
     * @param entity the creature
     * @param packed the memories to carry into the restored brain
     * @return the species' own brain
     * @throws IllegalStateException if the invocation failed, which on this path means the running
     *                               server is not the one this adapter was compiled against
     */
    static @NotNull Brain<?> makeSpeciesBrain(
            @NotNull LivingEntity entity,
            @NotNull Brain.Packed packed
    ) {
        try {
            final Brain<?> made = (Brain<?>) MAKE_BRAIN.invoke(entity, packed);
            if (made == null) {
                throw new IllegalStateException("A species' own brain factory answered null for "
                        + entity.getClass().getName());
            }
            return made;
        } catch (final RuntimeException | Error propagate) {
            throw propagate;
        } catch (final Throwable failure) {
            // MethodHandle#invoke is declared to throw Throwable, so the checked remainder has to be
            // wrapped. Naming the species is what makes the report actionable, because the thing that
            // went wrong is an override we cannot see from here.
            throw new IllegalStateException("Could not rebuild the species brain of "
                    + entity.getClass().getName(), failure);
        }
    }
}
