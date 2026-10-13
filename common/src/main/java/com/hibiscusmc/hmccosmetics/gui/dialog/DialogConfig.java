package com.hibiscusmc.hmccosmetics.gui.dialog;

import com.hibiscusmc.hmccosmetics.HMCCosmeticsPlugin;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code dialog.yml}: what the wardrobe dialog cannot work out from the cosmetics themselves, such
 * as which of them make a set and where the ones that are not sold come from.
 * <p>
 * Read again whenever the file changes, so an edit shows on the next screen without a reload.
 * </p>
 */
final class DialogConfig {

    private static final String FILE = "dialog.yml";

    record SetEntry(@NotNull String id, @NotNull String name, @NotNull List<String> pieces) {
    }

    record SourceDetail(@NotNull List<String> description, @NotNull String button, @NotNull String url,
                        @NotNull String command) {
    }

    /** @param set the id of the set the card opens, or blank to open the sets under {@code filter} */
    record Promo(@NotNull String title, @NotNull String subtitle, @NotNull List<String> pieces, @NotNull String set,
                 @NotNull String filter, @NotNull TextColor colour, @NotNull TextColor accent,
                 @NotNull TextColor titleColour) {
    }

    private static DialogConfig loaded;
    private static long loadedAt = -1;

    private final List<SetEntry> sets = new ArrayList<>();
    private final Map<String, String> sources = new HashMap<>();
    private final Map<String, SourceDetail> sourceDetails = new HashMap<>();
    private final Set<String> hidden = new HashSet<>();
    private final String unavailable;
    private final String currencyTexture;
    private final String tagTextures;
    private final @Nullable Promo promo;

    private DialogConfig(YamlConfiguration yaml) {
        for (Map<?, ?> entry : yaml.getMapList("sets")) {
            Object pieces = entry.get("pieces");
            this.sets.add(new SetEntry(String.valueOf(entry.get("id")), String.valueOf(entry.get("name")),
                    pieces instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of()));
        }
        ConfigurationSection sourceSection = yaml.getConfigurationSection("sources");
        if (sourceSection != null) {
            for (String id : sourceSection.getKeys(false)) this.sources.put(id, sourceSection.getString(id, ""));
        }
        ConfigurationSection details = yaml.getConfigurationSection("source-details");
        if (details != null) {
            for (String label : details.getKeys(false)) {
                ConfigurationSection detail = details.getConfigurationSection(label);
                if (detail == null) continue;
                this.sourceDetails.put(label, new SourceDetail(detail.getStringList("description"),
                        detail.getString("button", ""), detail.getString("url", ""), detail.getString("command", "")));
            }
        }
        this.hidden.addAll(yaml.getStringList("hidden"));
        this.unavailable = yaml.getString("unavailable", "Indisponível");
        this.currencyTexture = yaml.getString("currency-texture", "");
        this.tagTextures = yaml.getString("tag-textures", "");
        this.promo = promo(yaml.getConfigurationSection("promo"));
    }

    /** The promotional card, or null when it is off or one of its colours is not a hex. */
    private static @Nullable Promo promo(@Nullable ConfigurationSection section) {
        if (section == null || !section.getBoolean("enabled")) return null;
        TextColor colour = TextColor.fromHexString(section.getString("colour", ""));
        TextColor accent = TextColor.fromHexString(section.getString("accent", ""));
        TextColor titleColour = TextColor.fromHexString(section.getString("title-colour", ""));
        if (colour == null || accent == null || titleColour == null) return null;
        return new Promo(section.getString("title", ""), section.getString("subtitle", ""),
                section.getStringList("pieces"), section.getString("set", ""), section.getString("filter", "all"),
                colour, accent, titleColour);
    }

    static synchronized @NotNull DialogConfig get() {
        HMCCosmeticsPlugin plugin = HMCCosmeticsPlugin.getInstance();
        File file = new File(plugin.getDataFolder(), FILE);
        if (!file.exists()) plugin.saveResource(FILE, false);
        if (loaded == null || file.lastModified() != loadedAt) {
            loadedAt = file.lastModified();
            loaded = new DialogConfig(YamlConfiguration.loadConfiguration(file));
        }
        return loaded;
    }

    @NotNull List<SetEntry> sets() {
        return sets;
    }

    /** Where a cosmetic that is not sold comes from, by its id. */
    @NotNull Map<String, String> sources() {
        return sources;
    }

    /** What a cosmetic's page says about an origin of {@link #sources()}, by the origin's text. */
    @NotNull Map<String, SourceDetail> sourceDetails() {
        return sourceDetails;
    }

    /** Cosmetics the dialog never lists, such as the staff's own. */
    @NotNull Set<String> hidden() {
        return hidden;
    }

    @NotNull String unavailable() {
        return unavailable;
    }

    /** The pack texture of the currency's coin, or blank to show prices as bare numbers. */
    @NotNull String currencyTexture() {
        return currencyTexture;
    }

    /** The pack texture of a rarity, limited or dyeable tag, with {@code %s} where its name goes. */
    @NotNull String tagTextures() {
        return tagTextures;
    }

    @Nullable Promo promo() {
        return promo;
    }
}
