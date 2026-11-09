package pandastic.studio.models.nms;

import net.minecraft.world.entity.schedule.Activity;
import org.jetbrains.annotations.NotNull;
import pandastic.studio.models.api.ai.AiActivity;

/**
 * Turns an {@link AiActivity} slot into the server's own activity constant.
 *
 * <h2>A switch rather than a registry lookup, deliberately</h2>
 * The server keeps its activities in a registry and a lookup by name would be three lines instead of
 * twenty-six. It would also move the failure: a version that renamed a slot would compile cleanly and
 * fail at install, on a server, with a message about a missing registry key. An exhaustive switch over
 * the api's enum fails <em>here</em>, at compile time, in the module whose whole purpose is to be the
 * one place a version change is felt.
 *
 * <p>That is the trade this seam exists to make. The api's enum is a published, version-independent
 * vocabulary; this file is the version-specific proof that the vocabulary still maps onto something. If
 * 26.4 removes a slot, this file stops compiling and the api stays valid for every other server.
 *
 * <h2>Why the mapping is one-to-one and uninteresting</h2>
 * Because a plan does not get to invent activities — an activity is a registry object with a private
 * constructor and the registry is frozen before any plugin loads. So the api's enum is the server's
 * list, and this method is the identity function expressed in two type systems.
 */
final class VanillaActivities {

    private VanillaActivities() {
        throw new AssertionError("No instances.");
    }

    /**
     * The server's constant for {@code activity}.
     *
     * <p>Exhaustive over the api's enum, with no default branch: adding a slot to the api without
     * mapping it here is a compile error rather than a runtime surprise.
     *
     * @param activity the slot
     * @return the server's activity
     */
    static @NotNull Activity of(@NotNull AiActivity activity) {
        return switch (activity) {
            case CORE -> Activity.CORE;
            case IDLE -> Activity.IDLE;
            case WORK -> Activity.WORK;
            case PLAY -> Activity.PLAY;
            case REST -> Activity.REST;
            case MEET -> Activity.MEET;
            case PANIC -> Activity.PANIC;
            case RAID -> Activity.RAID;
            case PRE_RAID -> Activity.PRE_RAID;
            case HIDE -> Activity.HIDE;
            case FIGHT -> Activity.FIGHT;
            case CELEBRATE -> Activity.CELEBRATE;
            case ADMIRE_ITEM -> Activity.ADMIRE_ITEM;
            case AVOID -> Activity.AVOID;
            case RIDE -> Activity.RIDE;
            case PLAY_DEAD -> Activity.PLAY_DEAD;
            case LONG_JUMP -> Activity.LONG_JUMP;
            case RAM -> Activity.RAM;
            case TONGUE -> Activity.TONGUE;
            case SWIM -> Activity.SWIM;
            case LAY_SPAWN -> Activity.LAY_SPAWN;
            case SNIFF -> Activity.SNIFF;
            case INVESTIGATE -> Activity.INVESTIGATE;
            case ROAR -> Activity.ROAR;
            case EMERGE -> Activity.EMERGE;
            case DIG -> Activity.DIG;
        };
    }
}
