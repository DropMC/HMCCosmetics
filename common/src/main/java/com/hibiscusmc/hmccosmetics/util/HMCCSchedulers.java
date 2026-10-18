package com.hibiscusmc.hmccosmetics.util;

import com.hibiscusmc.hmccosmetics.HMCCosmeticsPlugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Paper's region-aware schedulers, which Paper runs on its main thread and Folia on the thread that
 * owns the entity. Work on a player or an entity goes through its own scheduler: the Bukkit
 * scheduler does not exist on Folia.
 */
public final class HMCCSchedulers {

    /** Whether the server ticks regions on their own threads, where a sync teleport throws. */
    public static final boolean REGIONIZED = classExists("io.papermc.paper.threadedregions.RegionizedServer");

    private HMCCSchedulers() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /** Runs {@code task} on the entity's thread next tick. Dropped when the entity is gone by then. */
    public static void run(@NotNull Entity entity, @NotNull Runnable task) {
        later(entity, task, null, 1);
    }

    /** Runs {@code task} on the entity's thread after {@code delay} ticks. Dropped when the entity is gone by then. */
    public static void later(@NotNull Entity entity, @NotNull Runnable task, long delay) {
        later(entity, task, null, delay);
    }

    /**
     * Runs {@code task} on the entity's thread after {@code delay} ticks, or {@code retired} instead
     * when the entity is removed first (quit, death, unload).
     */
    public static void later(@NotNull Entity entity, @NotNull Runnable task, @Nullable Runnable retired, long delay) {
        if (entity.getScheduler().runDelayed(plugin(), t -> task.run(), retired, Math.max(1, delay)) == null && retired != null) {
            retired.run();
        }
    }

    /** Repeats {@code task} on the entity's thread every {@code period} ticks, from the next tick, until cancelled or the entity is gone. */
    @Nullable
    public static ScheduledTask timer(@NotNull Entity entity, @NotNull Runnable task, long period) {
        return entity.getScheduler().runAtFixedRate(plugin(), t -> task.run(), null, 1, period);
    }

    /** {@link #timer(Entity, Runnable, long)} for a task that cancels itself. */
    @Nullable
    public static ScheduledTask timer(@NotNull Entity entity, @NotNull Consumer<ScheduledTask> task, long period) {
        return entity.getScheduler().runAtFixedRate(plugin(), task, null, 1, period);
    }

    /** Runs {@code task} right here when this thread owns the entity, otherwise on its thread next tick. */
    public static void owner(@NotNull Entity entity, @NotNull Runnable task) {
        if (Bukkit.isOwnedByCurrentRegion(entity)) {
            task.run();
        } else {
            run(entity, task);
        }
    }

    /** Runs {@code task} off the tick threads. */
    public static void async(@NotNull Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin(), t -> task.run());
    }

    /**
     * Teleports the entity and then runs {@code then} on its thread. Paper teleports right away and
     * runs {@code then} inline, as a sync teleport always did; Folia teleports asynchronously.
     * {@code then} runs whether or not the teleport went through, as the sync code did not look.
     */
    public static void teleport(@NotNull Entity entity, @NotNull Location location, @NotNull PlayerTeleportEvent.TeleportCause cause,
                                @Nullable Runnable then) {
        if (!REGIONIZED) {
            entity.teleport(location, cause);
            if (then != null) then.run();
            return;
        }
        entity.teleportAsync(location, cause).thenAccept(moved -> {
            if (then != null) owner(entity, then);
        });
    }

    /** {@link #teleport(Entity, Location, PlayerTeleportEvent.TeleportCause, Runnable)} with nothing to run after. */
    public static void teleport(@NotNull Entity entity, @NotNull Location location) {
        teleport(entity, location, PlayerTeleportEvent.TeleportCause.PLUGIN, null);
    }

    /** Removes an entity from whichever thread, on the thread that owns it. */
    public static void remove(@NotNull Entity entity) {
        owner(entity, entity::remove);
    }

    private static HMCCosmeticsPlugin plugin() {
        return HMCCosmeticsPlugin.getInstance();
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
