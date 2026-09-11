package com.hibiscusmc.hmccosmetics.hooks.misc;

import ac.grim.grimac.api.AbstractCheck;
import ac.grim.grimac.api.GrimAPIProvider;
import ac.grim.grimac.api.GrimAbstractAPI;
import ac.grim.grimac.api.GrimUser;
import com.hibiscusmc.hmccosmetics.api.events.PlayerWardrobeEnterEvent;
import com.hibiscusmc.hmccosmetics.api.events.PlayerWardrobeLeaveEvent;
import com.hibiscusmc.hmccosmetics.config.Settings;
import com.hibiscusmc.hmccosmetics.util.MessagesUtil;
import me.lojosho.hibiscuscommons.hooks.Hook;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;

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
            // runSafely hands this to an anticheat executor thread, so anything thrown in here
            // never reaches the catch below: it surfaces as a bare netty task warning instead.
            // Everything inside therefore has to catch its own failures.
            user.runSafely(() -> {
                try {
                    // A check whose name stops matching makes this hook a silent no-op, which looks
                    // exactly like the bug it fixes. The count is the only way to tell the two apart
                    // on a server where the anticheat exposes no command to inspect check state.
                    int matched = 0;
                    for (AbstractCheck check : user.getChecks()) {
                        String name = checkName(check);
                        if (name == null || !SUPPRESSED.contains(name)) continue;

                        matched++;
                        if (enabled) {
                            // Only undo our own suppression, so a check an admin turned off stays off
                            if (ours.remove(name)) check.setEnabled(true);
                        } else if (check.isEnabled()) {
                            check.setEnabled(false);
                            ours.add(name);
                        }
                    }
                    MessagesUtil.sendDebugMessages((enabled ? "Restored " : "Suppressed ") + matched
                            + " of " + SUPPRESSED.size() + " anticheat check(s) for " + uuid);
                } catch (Throwable t) {
                    MessagesUtil.sendDebugMessages("Failed to " + (enabled ? "restore" : "suppress")
                            + " anticheat checks for " + uuid + ": " + t);
                }
            });
        } catch (Exception e) {
            MessagesUtil.sendDebugMessages("Failed to hand the " + (enabled ? "restore" : "suppress")
                    + " to the anticheat for " + uuid + ": " + e.getMessage());
        }

        if (enabled && ours.isEmpty()) disabledByUs.remove(uuid);
    }

    private GrimUser grimUser(UUID uuid) {
        // isDetected, not isActive: the Hook base only ever sets "detected", and "active" is left to
        // each concrete hook to set inside its own load(). This one has nothing to load, so active
        // would stay false forever and silently turn the whole hook into a no-op.
        if (!isDetected()) return null;

        GrimAbstractAPI api = api();
        if (api == null) {
            MessagesUtil.sendDebugMessages("GrimAC is present but exposes no API, cannot suppress checks");
            return null;
        }

        GrimUser user = api.getGrimUser(uuid);
        if (user == null) MessagesUtil.sendDebugMessages("No Grim session for " + uuid);
        return user;
    }

    /**
     * The lowercased name a check is matched by, or null when it has none.
     * <p>
     * {@link AbstractCheck#getCheckName()} is documented as the check's config name but really is
     * null for some of them, and one null in the middle of the collection used to kill the whole
     * loop before it reached the check this hook cares about. The alternative name is what those
     * fall back on.
     * </p>
     */
    private String checkName(AbstractCheck check) {
        String name = check.getCheckName();
        if (name == null) name = check.getAlternativeName();
        return name == null ? null : name.toLowerCase(Locale.ROOT);
    }

    /**
     * Grim publishes its API two ways and neither is reliable on its own here. The provider throws
     * outright when Grim has not initialized yet, and this plugin enables before GrimAC does, so
     * the service registration is the fallback. Never swallow the failure silently: a hook that
     * cannot reach the anticheat looks exactly like the bug it exists to fix.
     */
    private GrimAbstractAPI api() {
        try {
            return GrimAPIProvider.get();
        } catch (Throwable t) {
            MessagesUtil.sendDebugMessages("GrimAPIProvider unavailable (" + t + "), trying the service registration");
        }

        RegisteredServiceProvider<GrimAbstractAPI> registration =
                Bukkit.getServicesManager().getRegistration(GrimAbstractAPI.class);
        return registration == null ? null : registration.getProvider();
    }
}
