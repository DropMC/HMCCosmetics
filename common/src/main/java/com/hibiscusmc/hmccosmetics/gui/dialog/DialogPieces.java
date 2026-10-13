package com.hibiscusmc.hmccosmetics.gui.dialog;

import com.hibiscusmc.hmccosmetics.HMCCosmeticsPlugin;
import com.hibiscusmc.hmccosmetics.cosmetic.Cosmetic;
import com.hibiscusmc.hmccosmetics.cosmetic.CosmeticSlot;
import com.hibiscusmc.hmccosmetics.cosmetic.Cosmetics;
import com.hibiscusmc.hmccosmetics.cosmetic.Rarity;
import gg.dropmc.commons.bukkit.api.ui.element.Element;
import gg.dropmc.commons.bukkit.api.ui.element.Image;
import gg.dropmc.commons.bukkit.api.ui.icon.PackIcons;
import gg.dropmc.commons.bukkit.api.ui.canvas.Piece;
import gg.dropmc.commons.bukkit.api.ui.canvas.Pixels;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The pictures the wardrobe dialog draws that the interface kit does not have: the currency's coin,
 * the pack's rarity tags, the marks of a limited and of a dyeable cosmetic, and every cosmetic's
 * icon.
 * <p>
 * All of it is declared once, while the plugin enables, and comes out of the next pack. A cosmetic
 * added to the configuration needs nothing here: its icon is drawn from its model.
 * </p>
 */
final class DialogPieces {

    static final String LIMITED = "limited";
    static final String DYEABLE = "dyeable";

    private static final int TAG_HEIGHT = 8;
    private static final int COIN_HEIGHT = 10;
    private static final TextColor BLACK = TextColor.color(0x000000);

    private static @Nullable Piece coin;
    private static final Map<String, Piece> TAGS = new java.util.HashMap<>();
    private static final Map<PackIcons.Size, Piece> AURAS = new EnumMap<>(PackIcons.Size.class);
    private static Piece limitedMark;
    private static Piece dyeableMark;

    private DialogPieces() {
    }

    static void declare(@NotNull HMCCosmeticsPlugin plugin) {
        DialogConfig config = DialogConfig.get();
        if (!config.currencyTexture().isBlank()) {
            coin = Piece.named("hmcc/coin").height(COIN_HEIGHT).texture(Key.key(config.currencyTexture()));
        }
        if (!config.tagTextures().isBlank()) {
            List<String> names = new ArrayList<>(List.of(LIMITED, DYEABLE));
            for (Rarity rarity : Rarity.values()) names.add(rarity.name().toLowerCase(Locale.ROOT));
            for (String name : names) {
                TAGS.put(name, Piece.named("hmcc/tag_" + name).height(TAG_HEIGHT)
                        .texture(Key.key(String.format(config.tagTextures(), name))));
            }
        }
        limitedMark = Piece.painted("hmcc/mark_limited", paintLimitedMark());
        dyeableMark = Piece.painted("hmcc/mark_dyeable", paintDyeableMark());
        // An aura has no model: it is a ring in the aura's colour, at each size an icon comes in.
        AURAS.put(PackIcons.Size.MINI, aura("hmcc/aura_mini", PackIcons.Size.MINI, 48, 6));
        AURAS.put(PackIcons.Size.SMALL, aura("hmcc/aura_small", PackIcons.Size.SMALL, 96, 9));
        AURAS.put(PackIcons.Size.LARGE, aura("hmcc/aura_large", PackIcons.Size.LARGE, 192, 16));

        PackIcons.source(plugin, () -> {
            List<PackIcons.Request> requests = new ArrayList<>();
            for (Cosmetic cosmetic : Cosmetics.values()) {
                ItemStack item = cosmetic.getSlot().equals(CosmeticSlot.AURA) ? null : cosmetic.getItem();
                if (item != null) requests.add(PackIcons.request(cosmetic.getId(), item).dyeable(cosmetic.isDyeable()));
            }
            return requests;
        });
    }

    static @Nullable Piece coin() {
        return coin;
    }

    /** The pack's tag for a rarity name, {@link #LIMITED} or {@link #DYEABLE}, or null without one. */
    static @Nullable Piece tag(@NotNull String name) {
        return TAGS.get(name);
    }

    static @NotNull Piece limitedMark() {
        return limitedMark;
    }

    static @NotNull Piece dyeableMark() {
        return dyeableMark;
    }

    /**
     * The cosmetic's picture in {@code dye}, or null when the pack has none for it yet. A dimmed one
     * is drawn darker, which is how the dialog marks what the player cannot get.
     */
    static @Nullable Element icon(@NotNull Cosmetic cosmetic, @NotNull PackIcons.Size size, @NotNull TextColor dye,
                                  boolean isDimmed) {
        if (cosmetic.getSlot().equals(CosmeticSlot.AURA)) {
            return Image.of(AURAS.get(size), isDimmed ? TextColor.lerp(0.6f, dye, BLACK) : dye);
        }
        PackIcons.Icon icon = PackIcons.of(HMCCosmeticsPlugin.getInstance(), cosmetic.getId(), size);
        return icon == null ? null : icon.dye(dye).dimmed(isDimmed);
    }

    private static Piece aura(String name, PackIcons.Size size, int texels, int stroke) {
        BufferedImage image = Pixels.blank(texels, texels);
        int inset = texels / 6;
        Pixels.fill(image, inset, inset, texels - inset, texels - inset, Pixels.WHITE);
        Pixels.fill(image, inset + stroke, inset + stroke, texels - inset - stroke, texels - inset - stroke, Pixels.CLEAR);
        return Piece.named(name).height(size.pixels()).offsets(size.offset()).painted(image);
    }

    /** An orange corner with an exclamation mark. */
    private static BufferedImage paintLimitedMark() {
        BufferedImage image = Pixels.blank(11, 11);
        for (int y = 0; y < 11; y++) Pixels.horizontal(image, 0, 11 - y, y, 0xFFF08A1D);
        Pixels.vertical(image, 3, 2, 5, Pixels.WHITE);
        image.setRGB(3, 6, Pixels.WHITE);
        return image;
    }

    /** A three by three palette, which reads as "comes in colours" at seven pixels. */
    private static BufferedImage paintDyeableMark() {
        int[] colours = {0xFFFF5555, 0xFFFFAA33, 0xFFFFEE44, 0xFFFF66BB, 0xFFFFFFFF, 0xFF55EE55, 0xFFBB66FF,
                0xFF5588FF, 0xFF55CCFF};
        BufferedImage image = Pixels.box(7, 7, Pixels.OUTLINE, Pixels.BLACK);
        for (int index = 0; index < colours.length; index++) {
            int x = 1 + (index % 3) * 2;
            int y = 1 + (index / 3) * 2;
            Pixels.fill(image, x, y, Math.min(x + 2, 6), Math.min(y + 2, 6), colours[index]);
        }
        return image;
    }
}
