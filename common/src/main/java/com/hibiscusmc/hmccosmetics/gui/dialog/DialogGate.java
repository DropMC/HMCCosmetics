package com.hibiscusmc.hmccosmetics.gui.dialog;

import com.hibiscusmc.hmccosmetics.HMCCosmeticsPlugin;
import com.hibiscusmc.hmccosmetics.hooks.misc.HookFloodgate;
import com.hibiscusmc.hmccosmetics.user.CosmeticUser;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;

/**
 * The way into the wardrobe dialog for the rest of the plugin, which must keep working on a server
 * that cannot show one.
 * <p>
 * The dialog is drawn with the interface of DropMC's commons plugin, and that is an optional
 * dependency: nothing outside this package names one of its classes, and this class only reaches
 * the ones that do once it has seen the plugin enabled.
 * </p>
 */
public final class DialogGate {

    private static final String COMMONS = "CommonsBukkit";

    private static boolean isEnabled;

    private DialogGate() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /**
     * Declares the dialog's pictures, so the next pack carries them. Called once while the plugin
     * enables: the pack is generated right after the server finishes loading.
     * <p>
     * Commons may still be disabled by then. Each plugin lists the other as an optional dependency
     * through a third one, and the server breaks such a circle by enabling either first. The
     * pictures are then declared as commons enables, which is still before the pack.
     * </p>
     */
    public static void enable(@NotNull HMCCosmeticsPlugin plugin) {
        Plugin commons = Bukkit.getPluginManager().getPlugin(COMMONS);
        if (commons == null) {
            plugin.getLogger().info("The wardrobe dialog is off: " + COMMONS + " is not installed");
            return;
        }
        if (commons.isEnabled()) {
            declare(plugin);
            return;
        }
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPluginEnable(PluginEnableEvent event) {
                if (event.getPlugin().getName().equals(COMMONS)) declare(plugin);
            }
        }, plugin);
    }

    private static void declare(HMCCosmeticsPlugin plugin) {
        try {
            DialogPieces.declare(plugin);
            isEnabled = true;
        } catch (LinkageError | RuntimeException exception) {
            // An older commons without the interface: the chest menus are still there.
            plugin.getLogger().log(Level.WARNING, "The wardrobe dialog is off: commons has no interface to draw it with", exception);
        }
    }

    /**
     * Opens the dialog for {@code user}.
     *
     * @return false when it was not shown and the caller has to open something else: a Bedrock
     *         client without the core's {@code java-dialogs} gets its own form, and nobody can draw it
     *         before the pack is generated
     */
    public static boolean open(@NotNull CosmeticUser user) {
        if (!isEnabled || HookFloodgate.getsBedrockForms(user.getPlayer())) return false;
        return WardrobeDialog.open(user);
    }

    /** Whether {@code dialog.yml} keeps the cosmetic out of every listing, as it does the staff's own. */
    public static boolean isHidden(@NotNull String cosmeticId) {
        return DialogConfig.get().hidden().contains(cosmeticId);
    }
}
