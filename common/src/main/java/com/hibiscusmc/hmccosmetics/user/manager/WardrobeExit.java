package com.hibiscusmc.hmccosmetics.user.manager;

import com.hibiscusmc.hmccosmetics.util.HMCCSchedulers;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

/**
 * The wardrobe exit owed to a player who left the server from inside a wardrobe on Folia, where a
 * player on the way out can no longer be teleported. It is saved with the player and paid on their
 * next join, so they come back where the wardrobe would have put them.
 */
public final class WardrobeExit {

    private static final NamespacedKey KEY = new NamespacedKey("hmccosmetics", "wardrobe_exit");

    private WardrobeExit() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    public static void remember(@NotNull Player player, @NotNull Location exit) {
        player.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, exit.getWorld().getName()
                + ',' + exit.getX() + ',' + exit.getY() + ',' + exit.getZ() + ',' + exit.getYaw() + ',' + exit.getPitch());
    }

    /** Teleports a joining player to the exit they are owed, if any. A teleport during the join itself fails, so it waits a tick. */
    public static void restore(@NotNull Player player) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        String saved = data.get(KEY, PersistentDataType.STRING);
        if (saved == null) return;
        data.remove(KEY);

        String[] parts = saved.split(",");
        World world = parts.length == 6 ? Bukkit.getWorld(parts[0]) : null;
        if (world == null) return;
        Location exit = new Location(world, Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3]),
                Float.parseFloat(parts[4]), Float.parseFloat(parts[5]));
        HMCCSchedulers.later(player, () -> HMCCSchedulers.teleport(player, exit), 1);
    }
}
