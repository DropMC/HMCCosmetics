package com.hibiscusmc.hmccosmetics.cosmetic;

import net.kyori.adventure.text.format.TextColor;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Rarity tier for a cosmetic. Colors, names and Nexo glyph IDs mirror
 * {@code gg.dropmc.survival.core.api.item.Rarity} in the survival-root {@code core} module so cosmetics
 * look consistent with spawners/bazaar, even though HMCCosmetics can't depend on that module directly.
 */
public enum Rarity {
    COMMON(0x7f7f7f, "Comum", "rarity_common"),
    UNCOMMON(0x00ae00, "Incomum", "rarity_uncommon"),
    RARE(0x5858ff, "Raro", "rarity_rare"),
    EPIC(0xa800a8, "Épico", "rarity_epic"),
    LEGENDARY(0xffa900, "Lendário", "rarity_legendary");

    private final TextColor color;
    private final String displayName;
    private final String glyphId;

    Rarity(int rgb, String displayName, String glyphId) {
        this.color = TextColor.color(rgb);
        this.displayName = displayName;
        this.glyphId = glyphId;
    }

    public TextColor color() {
        return color;
    }

    public String displayName() {
        return displayName;
    }

    /** This rarity's Nexo glyph id, for embedding as a {@code <glyph:...>} MiniMessage tag. */
    public String glyphId() {
        return glyphId;
    }

    public static @Nullable Rarity fromConfig(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Rarity.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
