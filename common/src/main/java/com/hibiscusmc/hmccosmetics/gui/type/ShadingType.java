package com.hibiscusmc.hmccosmetics.gui.type;

import org.bukkit.Bukkit;

import javax.annotation.Nullable;
import java.util.Locale;

public enum ShadingType {
    /**
     * MODERN tints the item model, TEXT shades every slot through the title, and GLYPH paints one tile
     * per cosmetic slot into the title at its exact position, coloured by state.
     */
    MODERN, TEXT, GLYPH, NONE;

    public static ShadingType fromString(String string, @Nullable ShadingType defaultType) {
        ShadingType fallback = defaultType != null ? defaultType : NONE;
        if (string == null || string.isBlank()) return fallback;
        try {
            return ShadingType.valueOf(string.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            Bukkit.getLogger().warning("[HMCCosmetics] Unknown shading type '" + string + "', valid values are MODERN, TEXT, GLYPH or NONE. Falling back to " + fallback + ".");
            return fallback;
        }
    }
}
