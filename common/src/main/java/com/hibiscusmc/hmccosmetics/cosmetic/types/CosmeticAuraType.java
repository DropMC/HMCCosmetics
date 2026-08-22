package com.hibiscusmc.hmccosmetics.cosmetic.types;

import com.hibiscusmc.hmccosmetics.cosmetic.Cosmetic;
import com.hibiscusmc.hmccosmetics.cosmetic.behavior.CosmeticUpdateBehavior;
import com.hibiscusmc.hmccosmetics.user.CosmeticUser;
import com.hibiscusmc.hmccosmetics.util.MessagesUtil;
import lombok.Getter;
import me.lojosho.shaded.configurate.ConfigurationNode;
import org.bukkit.ChatColor;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Level;

/**
 * A glowing outline around the wearer, the same effect a spectral arrow applies.
 *
 * <p>The outline itself is a packet: entity metadata index 0 with the glowing bit set, sent to
 * whoever is in range. The server owns that byte and rewrites it whenever the player starts
 * sneaking, sprinting or burning, which clears the bit, so it is re-sent on every user tick and
 * again right after the events that dirty it.</p>
 *
 * <p>The color is not part of that packet. A client paints the outline in the color of the
 * scoreboard team the entity belongs to, which is why an aura only has the sixteen legacy colors
 * available to it and why the color is applied through TAB (see {@code HookTAB}) rather than by a
 * team packet of our own.</p>
 */
public class CosmeticAuraType extends Cosmetic implements CosmeticUpdateBehavior {

    private static final ChatColor DEFAULT_COLOR = ChatColor.WHITE;

    @Getter
    private final ChatColor color;

    public CosmeticAuraType(String id, ConfigurationNode config) {
        super(id, config);

        this.color = readColor(config.node("color").getString());
        MessagesUtil.sendDebugMessages("Aura color " + color.name() + " for " + getId());
    }

    @Override
    public void dispatchUpdate(@NotNull CosmeticUser user) {
        if (user.isHidden()) return;
        user.refreshAura();
    }

    /**
     * Reads {@code color} from the cosmetic's own node, not from {@code item.color}: the latter dyes
     * the swatch shown in the menu and takes any RGB value, while this one has to name a team color.
     */
    private ChatColor readColor(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_COLOR;

        try {
            ChatColor parsed = ChatColor.valueOf(raw.toUpperCase());
            if (parsed.isColor()) return parsed;
        } catch (IllegalArgumentException ignored) {
            // Reported by the shared warning below, same as a format code that is not a color.
        }

        MessagesUtil.sendDebugMessages(
                "Unusable aura color '" + raw + "' for " + getId() + ", falling back to " + DEFAULT_COLOR.name()
                        + ". An aura color has to be one of the sixteen legacy colors.", Level.WARNING);
        return DEFAULT_COLOR;
    }
}
