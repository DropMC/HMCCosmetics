package com.hibiscusmc.hmccosmetics.user;

import com.hibiscusmc.hmccosmetics.HMCCosmeticsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Watches every aura wearer's entity flag byte and re-sends the glow the tick it changes.
 *
 * <p>This is the fallback for a server without PacketEvents, and it cannot be seamless: the earliest
 * a reply can land is the tick after the server rewrote the byte, and that one frame reads as the
 * outline blinking. Where PacketEvents is present the bit is held on the packet itself instead (see
 * {@code HookPacketEvents}) and this never runs.</p>
 *
 * <p>The glowing bit lives in metadata index 0, which the server owns and rewrites whole whenever the
 * player starts or stops sneaking, sprinting, burning, swimming or gliding. Each of those rewrites
 * drops the bit, and waiting for the once-a-second user tick to restore it is long enough to read as
 * the aura blinking out. Watching the byte instead of listening for a handful of events covers every
 * cause, including the ones that have no event at all (fire burning out is the common one).</p>
 *
 * <p>This runs every tick but sends nothing while a wearer's state is unchanged, so a standing player
 * costs a byte comparison and no packets.</p>
 */
public class AuraTicker implements Runnable {

    private static final long PERIOD_TICKS = 1;

    public static void start(HMCCosmeticsPlugin plugin) {
        Bukkit.getScheduler().runTaskTimer(plugin, new AuraTicker(), PERIOD_TICKS, PERIOD_TICKS);
    }

    @Override
    public void run() {
        // Walks the online players rather than CosmeticUsers#values, which copies its map into a new
        // set on every call and would do so twenty times a second here.
        for (Player player : Bukkit.getOnlinePlayers()) {
            CosmeticUser user = CosmeticUsers.getUser(player);
            if (user == null) continue;
            user.refreshAuraOnStateChange();
        }
    }
}
