package pandastic.studio.models.internal.binding;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes player arm-swing pulses without making the off-tick simulation touch Bukkit.
 *
 * <p>The value is a monotonically increasing sequence rather than a boolean: a swing can happen and
 * finish between two anchor samples, and the next sampler only needs to know that at least one new
 * swing occurred. The map is intentionally concurrent because the event and the entity scheduler are
 * not required to run on the same region thread.
 */
public final class AttackTracker implements Listener {

    private final @NotNull ConcurrentHashMap<UUID, AtomicLong> swings = new ConcurrentHashMap<>();

    @EventHandler
    public void onAnimation(@NotNull PlayerArmSwingEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        final Player player = event.getPlayer();
        this.swings.computeIfAbsent(player.getUniqueId(), ignored -> new AtomicLong())
                .incrementAndGet();
    }

    @EventHandler
    public void onQuit(@NotNull PlayerQuitEvent event) {
        this.swings.remove(event.getPlayer().getUniqueId());
    }

    /** Returns the current swing sequence for {@code player}. */
    public long sequence(@NotNull Player player) {
        final AtomicLong sequence = this.swings.get(player.getUniqueId());
        return sequence == null ? 0L : sequence.get();
    }
}
