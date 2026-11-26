package pandastic.studio.models.nms;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * Asks the <em>running</em> server whether a Mojang-mapped method is really there, by name and by shape.
 *
 * <h2>Why a capability has to be probed when the module is version-exact already</h2>
 * {@link pandastic.studio.models.internal.nms.NmsBridgeLookup} refuses to hand this adapter to a server
 * whose version is not the one it was compiled against, which removes most of the ways a member can be
 * missing — but not all of them. 26.3 lives on Folia's alpha channel, where two builds reporting the same
 * Minecraft version can differ; a fork can rename or relocate what it inherited; and a server handed to
 * this module with Spigot mappings keeps every <em>class</em> name and loses every <em>member</em> name,
 * which is the exact failure {@link MojangMappingProbe} exists to make a compile error and which this
 * class exists to make a runtime answer.
 *
 * <p>What makes the probe worth having is the shape of the alternative. A direct Mojang-mapped call site
 * links lazily, on first execution, so an absent member is a {@link NoSuchMethodError} thrown from inside
 * a vehicle's control step — on a region thread, once per vehicle per tick, from a method whose caller
 * has a correct fallback it was never given the chance to use. Asking first turns that into a capability
 * flag read once at construction.
 *
 * <h2>Why a probe is a complete guarantee for a direct call</h2>
 * Because it resolves the same member the call site's {@code invokevirtual} will: the same declaring
 * class, the same name, the same parameter types and — checked separately, since {@link
 * Class#getMethod} does not match on it — the same return type. Together those are the method descriptor,
 * and a descriptor that resolves reflectively resolves for the bytecode too. {@code getMethod} also finds
 * public members only, which is the call site's own requirement: everything below this seam is called
 * from another package.
 *
 * <p>So the pattern below the seam is "probe once, then call directly", and not "invoke through a
 * {@link java.lang.invoke.MethodHandle}". {@link BrainReflection} uses handles because the members it
 * needs are {@code protected} and have no call site at all; here the members are public and the call is
 * on a per-vehicle, per-tick path, where a direct virtual dispatch is the point.
 *
 * <h2>Threading</h2>
 * Pure reflection over a class object. No entity, no world, no thread requirement — which is why the
 * callers resolve in a static initialiser and never on the path that uses the answer.
 */
final class MappedMethods {

    private MappedMethods() {
        throw new AssertionError("No instances.");
    }

    /**
     * Whether {@code owner} is missing a public method of exactly this shape, and why.
     *
     * <p>Phrased as the absence rather than the presence so that the answer and the sentence an operator
     * reads are one value: a {@code null} means the capability is available and there is nothing to say,
     * and anything else is both "unavailable" and the reason.
     *
     * @param owner          the class to ask, which must already be loaded — the caller evaluates
     *                       {@code SomeType.class}, so a server missing the <em>class</em> fails in the
     *                       caller's frame with a {@link LinkageError} rather than here
     * @param name           the mapped method name
     * @param returnType     the return type the call site was compiled against. Checked explicitly
     *                       because {@code getMethod} matches on name and parameters only, and a member
     *                       whose return type moved does not link even though it resolves
     * @param parameterTypes the parameter types the call site was compiled against, in order
     * @return {@code null} when the method is present, public and of that exact shape; otherwise one
     *         sentence naming the full shape that was looked for and what was found instead
     */
    static @Nullable String missing(
            @NotNull Class<?> owner,
            @NotNull String name,
            @NotNull Class<?> returnType,
            @NotNull Class<?>... parameterTypes
    ) {
        final Method method;
        try {
            method = owner.getMethod(name, parameterTypes);
        } catch (final NoSuchMethodException | RuntimeException | LinkageError absent) {
            // LinkageError as well as the checked exception: a parameter or return type that cannot be
            // loaded surfaces here rather than as a missing name, and both mean the same thing to a
            // caller — this server is not the one the call site was compiled against.
            return describe(owner, name, returnType, parameterTypes)
                    + " is not a public method of this server's own class (" + absent + ")";
        }

        final Class<?> actual = method.getReturnType();
        if (actual != returnType) {
            return describe(owner, name, returnType, parameterTypes) + " returns " + actual.getName()
                    + " on this server, so a call compiled against it would not link";
        }
        return null;
    }

    /**
     * One method's full shape, in the form a reader can compare against a decompiled signature.
     *
     * <p>Names rather than simple names throughout: two {@code Entity} types are reachable from every
     * file in this module, and a sentence naming the wrong one would send an operator looking in the
     * wrong half of the server.
     */
    private static @NotNull String describe(
            @NotNull Class<?> owner,
            @NotNull String name,
            @NotNull Class<?> returnType,
            @NotNull Class<?>[] parameterTypes
    ) {
        final StringBuilder text = new StringBuilder(owner.getName()).append('#').append(name).append('(');
        for (int index = 0; index < parameterTypes.length; index++) {
            if (index > 0) {
                text.append(", ");
            }
            text.append(parameterTypes[index].getName());
        }
        return text.append(") -> ").append(returnType.getName()).toString();
    }
}
