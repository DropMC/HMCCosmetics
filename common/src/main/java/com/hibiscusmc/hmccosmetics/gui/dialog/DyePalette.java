package com.hibiscusmc.hmccosmetics.gui.dialog;

import com.hibiscusmc.hmccosmetics.HMCCosmeticsPlugin;
import gg.dropmc.commons.bukkit.api.ui.kit.input.Swatches;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The colours a cosmetic can be dyed, which are the ones the Bedrock dye form offers: a row of base
 * colours and, under the chosen one, its shades.
 * <p>
 * Read from that palette's own file so the two cannot drift apart: a colour that exists in one and
 * not in the other is a colour a player can wear and not pick again.
 * </p>
 */
final class DyePalette {

    private static final String FILE = "menus/functional/internal_dye_menu.yml";

    private static List<Swatches.Family> families = List.of();
    private static long loadedAt = -1;

    private DyePalette() {
    }

    /** The families in the file's order, read again whenever the file changes. */
    static synchronized @NotNull List<Swatches.Family> families() {
        File file = new File(HMCCosmeticsPlugin.getInstance().getDataFolder(), FILE);
        if (file.lastModified() != loadedAt) {
            loadedAt = file.lastModified();
            families = read(YamlConfiguration.loadConfiguration(file).getConfigurationSection("colors"));
        }
        return families;
    }

    /** A colour of the file, its name stripped of the MiniMessage tags that paint it in a chest menu. */
    private static @Nullable Swatches.Shade shade(@Nullable Object name, @Nullable Object hex) {
        TextColor colour = hex == null ? null : TextColor.fromHexString(hex.toString());
        if (colour == null) return null;
        String plain = name == null ? "" : name.toString().replaceAll("<[^<>]*>", "").trim();
        return new Swatches.Shade(plain.isEmpty() ? colour.asHexString() : plain, colour);
    }

    private static List<Swatches.Family> read(@Nullable ConfigurationSection colours) {
        List<Swatches.Family> read = new ArrayList<>();
        if (colours == null) return read;
        for (String key : colours.getKeys(false)) {
            ConfigurationSection family = colours.getConfigurationSection(key);
            Swatches.Shade base = family == null ? null : shade(family.getString("name"), family.getString("color"));
            if (base == null) continue;
            List<Swatches.Shade> shades = new ArrayList<>();
            for (var entry : family.getMapList("subcolors")) {
                Swatches.Shade shade = shade(entry.get("name"), entry.get("color"));
                if (shade != null) shades.add(shade);
            }
            read.add(new Swatches.Family(base, shades));
        }
        return read;
    }
}
