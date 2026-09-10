package com.hibiscusmc.hmccosmetics.hooks.misc;

import ac.grim.grimac.api.AbstractCheck;
import ac.grim.grimac.api.GrimAPIProvider;
import ac.grim.grimac.api.GrimUser;
import com.hibiscusmc.hmccosmetics.api.events.PlayerWardrobeEnterEvent;
import com.hibiscusmc.hmccosmetics.api.events.PlayerWardrobeLeaveEvent;
import com.hibiscusmc.hmccosmetics.config.Settings;
import com.hibiscusmc.hmccosmetics.util.MessagesUtil;
import me.lojosho.hibiscuscommons.hooks.Hook;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Suppresses the anticheat checks that the wardrobe itself breaks, for as long as a player is
 * inside one.
 * <p>
 * The wardrobe hands the client a gamemode packet putting it in spectator and mounts it on a
 * camera armorstand, while the server still has the player in survival. On top of that,
 * {@code CosmeticPacketInterface#readPlayerArm} removes the swing packet from the stream once it
 * has opened the menu. GrimAC never sees that animation, so the next attack trips
 * {@code PacketOrderB} ("Did not swing for attack"), and that check does not merely flag: it
 * cancels the packet, which is what stops the menu from opening for anyone the anticheat is
 * actually watching. Staff never notice because {@code grim.exempt} skips the checks entirely.
 * </p>
 * This mirrors what {@link HookVulcan} already does for Vulcan, which is the same problem on the
 * other anticheat.
 */
public class HookGrim extends Hook {

    /**
     * The checks a wardrobe session is allowed to switch off. Grim keys checks by the name in its
     * config, which is what {@link AbstractCheck#getCheckName()} returns, so the match is on the
     * lowercased name. Add to this list rather than exempting the player wholesale: a wardrobe
     * should not be a blind spot for anything it does not actually break.
     */
    private static final Set<String> SUPPRESSED = Set.of("packetorderb");

    /** Checks this hook turned off, per player, so leaving restores nothing it did not disable. */
    private final ConcurrentHashMap<UUID, Set<String>> disabledByUs = new ConcurrentHashMap<>();

    public HookGrim() {
        super("GrimAC");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerEnterWardrobe(PlayerWardrobeEnterEvent event) {
        if (!Settings.isGrimExemptChecksInWardrobe()) return;

        setEnabled(event.getUser().getUniqueId(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerLeaveWardrobe(PlayerWardrobeLeaveEvent event) {
        setEnabled(event.getUser().getUniqueId(), true);
    }

    /**
     * A player who disconnects inside a wardrobe never fires the leave event. Their Grim session
     * dies with the connection, so nothing has to be restored, but the bookkeeping would otherwise
     * outlive it and make the next session look already suppressed.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        disabledByUs.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Flips the suppressed checks on the player's Grim session. The mutation goes through
     * {@link GrimUser#runSafely(Runnable)} because checks are read from the netty threads feeding
     * the anticheat, not only from the main thread this runs on.
     */
    private void setEnabled(UUID uuid, boolean enabled) {
        GrimUser user = grimUser(uuid);
        if (user == null) return;

        Set<String> ours = disabledByUs.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
        try {
            user.runSafely(() -> {
                for (AbstractCheck check : user.getChecks()) {
                    String name = check.getCheckName().toLowerCase(Locale.ROOT);
                    if (!SUPPRESSED.contains(name)) continue;

                    if (enabled) {
                        // Only undo our own suppression, so a check an admin turned off stays off
                        if (ours.remove(name)) check.setEnabled(true);
                    } else if (check.isEnabled()) {
                        check.setEnabled(false);
                        ours.add(name);
                    }
                }
            });
        } catch (Exception e) {
            MessagesUtil.sendDebugMessages("Failed to " + (enabled ? "restore" : "suppress")
                    + " anticheat checks for " + uuid + ": " + e.getMessage());
        }

        if (enabled && ours.isEmpty()) disabledByUs.remove(uuid);
    }

    private GrimUser grimUser(UUID uuid) {
        if (!isActive()) return null;
        try {
            return GrimAPIProvider.get().getGrimUser(uuid);
        } catch (Exception e) {
            // Grim is installed but not started yet, or the player has no session
            return null;
        }
    }
}
