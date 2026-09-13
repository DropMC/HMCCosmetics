package com.hibiscusmc.hmccosmetics.hooks.misc;

import com.hibiscusmc.hmccosmetics.util.MessagesUtil;
import me.lojosho.hibiscuscommons.hooks.Hook;
import org.bukkit.entity.Player;
import org.geysermc.floodgate.api.FloodgateApi;
import org.jetbrains.annotations.Nullable;

/**
 * Tells apart the players who are on Bedrock Edition and therefore see this server through Geyser.
 * <p>
 * Floodgate is what answers, rather than Geyser itself, because Geyser usually runs on the proxy
 * where its API cannot be reached from a backend server, while Floodgate is installed here.
 * </p>
 * The wardrobe is the one feature that has to care: everything it shows a player is built out of
 * packets whose Java meaning does not survive the trip through Geyser. See
 * {@link com.hibiscusmc.hmccosmetics.user.manager.UserWardrobeManager}.
 */
public class HookFloodgate extends Hook {

    private static HookFloodgate instance;

    public HookFloodgate() {
        super("floodgate");
        instance = this;
    }

    /**
     * Whether this player joined from Bedrock Edition. Always false while Floodgate is not installed,
     * which is also the right answer then: without Floodgate no Bedrock player can be connected.
     */
    public static boolean isBedrockPlayer(@Nullable Player player) {
        // isDetected, not isActive: the Hook base only ever sets "detected", and "active" is left to
        // each concrete hook to set inside its own load(). This one has nothing to load, so active
        // would stay false forever and silently turn the whole hook into a no-op.
        if (player == null || instance == null || !instance.isDetected()) return false;

        try {
            return FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
        } catch (Throwable t) {
            MessagesUtil.sendDebugMessages("Floodgate is present but could not be asked about "
                    + player.getName() + ": " + t);
            return false;
        }
    }
}
