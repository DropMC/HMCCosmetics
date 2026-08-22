package com.hibiscusmc.hmccosmetics.hooks.misc;

import com.hibiscusmc.hmccosmetics.util.MessagesUtil;
import me.lojosho.hibiscuscommons.hooks.Hook;
import me.lojosho.hibiscuscommons.hooks.Hooks;
import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.nametag.NameTagManager;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Paints the aura outline by way of TAB.
 *
 * <p>A glowing entity is drawn in the color of the scoreboard team it belongs to, and TAB owns every
 * team on a server that has its {@code scoreboard-teams} feature on: it puts each player in a team
 * of its own to sort the tablist, and its anti-override cancels attempts by other plugins to move a
 * player elsewhere. Rather than fight that, the color is set through TAB, which derives the team
 * color from the last color of the nametag prefix. A bare color code is therefore the entire
 * payload, and it is invisible in game because the vanilla nametag is turned off in favor of
 * UnlimitedNameTags.</p>
 */
public class HookTAB extends Hook {

    private static final String ID = "TAB";

    public HookTAB() {
        super(ID);
        // Hooks#setup only marks the hook as detected; without this it never counts as active, and
        // every call below would no-op even with TAB installed.
        setActive(true);
    }

    /**
     * Safe to call on every user tick, which is what keeps the color alive across a TAB reload or a
     * relog: a color that is already applied is skipped instead of making TAB rebuild and rebroadcast
     * the team.
     */
    public static void setGlowColor(@NotNull Player player, @NotNull ChatColor color) {
        setPrefix(player, "&" + color.getChar());
    }

    /** Hands the nametag prefix back to whatever TAB's own configuration says it should be. */
    public static void clearGlowColor(@NotNull Player player) {
        setPrefix(player, null);
    }

    private static void setPrefix(@NotNull Player player, @Nullable String prefix) {
        NameTagManager nameTags = nameTagManager();
        if (nameTags == null) return;

        TabPlayer tabPlayer = loadedPlayer(player);
        if (tabPlayer == null) return;

        if (Objects.equals(prefix, nameTags.getCustomPrefix(tabPlayer))) return;
        nameTags.setPrefix(tabPlayer, prefix);
    }

    private static @Nullable NameTagManager nameTagManager() {
        if (!Hooks.isActiveHook(ID)) return null;

        NameTagManager nameTags = TabAPI.getInstance().getNameTagManager();
        if (nameTags == null) {
            MessagesUtil.sendDebugMessages("TAB is present but its nametag feature is off, aura colors are unavailable");
        }
        return nameTags;
    }

    /**
     * TAB throws rather than answering for a player it has not finished loading, which is a live risk
     * here: the aura is applied as soon as the cosmetic user is built on join, and TAB may still be
     * setting that same player up.
     */
    private static @Nullable TabPlayer loadedPlayer(@NotNull Player player) {
        TabPlayer tabPlayer = TabAPI.getInstance().getPlayer(player.getUniqueId());
        if (tabPlayer == null || !tabPlayer.isLoaded()) return null;
        return tabPlayer;
    }
}
