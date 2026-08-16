package com.hibiscusmc.hmccosmetics.gui.special.impl;

import com.hibiscusmc.hmccosmetics.HMCCosmeticsPlugin;
import com.hibiscusmc.hmccosmetics.config.Settings;
import com.hibiscusmc.hmccosmetics.cosmetic.Cosmetic;
import com.hibiscusmc.hmccosmetics.cosmetic.CosmeticHolder;
import com.hibiscusmc.hmccosmetics.gui.Menu;
import com.hibiscusmc.hmccosmetics.gui.Menus;
import com.hibiscusmc.hmccosmetics.gui.action.Actions;
import com.hibiscusmc.hmccosmetics.gui.special.DyeMenu;
import com.hibiscusmc.hmccosmetics.util.HMCCServerUtils;
import dev.triumphteam.gui.builder.gui.ChestGuiBuilder;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import me.lojosho.hibiscuscommons.config.serializer.ItemSerializer;
import me.lojosho.hibiscuscommons.hooks.Hooks;
import me.lojosho.hibiscuscommons.nms.NMSHandlers;
import me.lojosho.hibiscuscommons.util.ColorBuilder;
import me.lojosho.hibiscuscommons.util.MessagesUtil;
import me.lojosho.shaded.configurate.CommentedConfigurationNode;
import me.lojosho.shaded.configurate.ConfigurateException;
import me.lojosho.shaded.configurate.ConfigurationNode;
import me.lojosho.shaded.configurate.serialize.SerializationException;
import me.lojosho.shaded.configurate.yaml.YamlConfigurationLoader;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import static com.hibiscusmc.hmccosmetics.util.MessagesUtil.nexoTags;

public class InternalDyeMenu implements DyeMenu {

    private List<PrimaryColor> PRIMARY_COLORS;

    private List<String> FORMAT = List.of();
    private int ROWS;
    private final ArrayList<Integer> PRIMARY_COLORS_SLOTS = new ArrayList<>();
    private final ArrayList<Integer> SECONDARY_COLORS_SLOTS = new ArrayList<>();

    private int INPUT_SLOT;
    private int OUTPUT_SLOT;

    private @Nullable ItemStack PRIMARY_COLOR_ITEM = null;
    private @Nullable ItemStack SECONDARY_COLOR_ITEM = null;

    private @Nullable HeaderButton BACK_BUTTON = null;
    private @Nullable HeaderButton INFO_BUTTON = null;

    /**
     * One of the two buttons in the menu's header. The back button returns to the menu the viewer came
     * from; the info button is inert and only carries its lore.
     */
    private record HeaderButton(int slot, @NotNull ItemStack item, @NotNull List<String> actions) {}

    @Override
    public void reload() {
        YamlConfigurationLoader loader = YamlConfigurationLoader.builder().path(Path.of(HMCCosmeticsPlugin.getInstance().getDataFolder() + "/menus/functional/internal_dye_menu.yml")).build();
        CommentedConfigurationNode config;
        try {
            config = loader.load();
        } catch (ConfigurateException e) {
            throw new RuntimeException(e);
        }


        try {
            FORMAT = config.node("format").getList(String.class);
        } catch (SerializationException e) {
            e.printStackTrace();
        }
        if (FORMAT == null) {
            MessagesUtil.sendDebugMessages("Format for the internal dye menu is invalid!", Level.WARNING);
            throw new RuntimeException();
        }

        ROWS = FORMAT.size();
        StringBuilder builder = new StringBuilder();
        for (String row : FORMAT) {
            builder.append(row);
        }
        final String formatString = builder.toString();

        PRIMARY_COLORS_SLOTS.clear();
        SECONDARY_COLORS_SLOTS.clear();
        // Bounded by the format's own length so a row typed with fewer than 9 cells warns instead of throwing.
        int cells = Math.min(ROWS * 9, formatString.length());
        for (int i = 0; i < cells; i++) {
            char character = formatString.charAt(i);
            switch (character) {
                case '$' -> {
                    PRIMARY_COLORS_SLOTS.add(i);
                }
                case '%' -> {
                    SECONDARY_COLORS_SLOTS.add(i);
                }
            }
        }

        if (!config.node("primary-color-item").virtual()) {
            try {
                PRIMARY_COLOR_ITEM = ItemSerializer.INSTANCE.deserialize(ItemStack.class, config.node("primary-color-item"));
                if (PRIMARY_COLOR_ITEM.getType() == Material.AIR) {
                    MessagesUtil.sendDebugMessages("Internal Dye Menu Primary Color Item has returned AIR, defaulting to use the cosmetic item itself", Level.WARNING);
                    PRIMARY_COLOR_ITEM = null;
                }
            } catch (SerializationException e) {
                e.printStackTrace();
            }
        }
        if (!config.node("secondary-color-item").virtual()) {
            try {
                SECONDARY_COLOR_ITEM = ItemSerializer.INSTANCE.deserialize(ItemStack.class, config.node("secondary-color-item"));
                if (SECONDARY_COLOR_ITEM.getType() == Material.AIR) {
                    MessagesUtil.sendDebugMessages("Internal Dye Menu Secondary Color Item has returned AIR, defaulting to use the cosmetic item itself", Level.WARNING);
                    SECONDARY_COLOR_ITEM = null;
                }
            } catch (SerializationException e) {
                e.printStackTrace();
            }
        }

        PRIMARY_COLORS = loadColorsFromConfig(config.node("colors"));

        BACK_BUTTON = loadHeaderButton(config.node("back-item"), 0);
        INFO_BUTTON = loadHeaderButton(config.node("info-item"), 8);

        INPUT_SLOT = Settings.getDyeMenuInputSlot();
        OUTPUT_SLOT = Settings.getDyeMenuOutputSlot();
    }

    /**
     * Wraps a stack for the menu, hiding the tooltip lines Minecraft writes by itself - "Dyed",
     * "When worn: +3 Armor", enchantments, trims - so a swatch shows only its own name. Also strips the
     * italics Minecraft applies to custom item names by default, since the swatches already set their
     * own decoration and the input/output preview should show the cosmetic's name upright too.
     */
    @NotNull
    private static GuiItem guiItem(@NotNull ItemStack itemStack) {
        itemStack.editMeta(itemMeta -> {
            itemMeta.addItemFlags(ItemFlag.values());
            Component name = itemMeta.displayName();
            if (name != null) itemMeta.displayName(name.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        });
        return new GuiItem(itemStack);
    }

    private @Nullable HeaderButton loadHeaderButton(@NotNull ConfigurationNode node, int defaultSlot) {
        if (node.virtual()) return null;

        try {
            ItemStack item = ItemSerializer.INSTANCE.deserialize(ItemStack.class, node);
            if (item == null || item.getType() == Material.AIR) {
                MessagesUtil.sendDebugMessages("Header button " + node.key() + " in the internal dye menu returned AIR, skipping it", Level.WARNING);
                return null;
            }

            List<String> actions = node.node("actions").getList(String.class);
            return new HeaderButton(node.node("slot").getInt(defaultSlot), item, actions == null ? List.of() : actions);
        } catch (SerializationException e) {
            MessagesUtil.sendDebugMessages("Unable to read header button " + node.key() + " in the internal dye menu: " + e.getMessage(), Level.WARNING);
            return null;
        }
    }

    @Override
    public void openMenu(@NotNull Player viewer, @NotNull CosmeticHolder cosmeticHolder, @NotNull Cosmetic cosmetic) {
        if (ROWS == 0 || ROWS >= 7) {
            MessagesUtil.sendDebugMessages("Internal Dye Menu formatting is not returning the correct amount of rows (Rows found: " + ROWS + "). Check your internal dye menu config.", Level.WARNING);
            cosmeticHolder.addCosmetic(cosmetic);
            return;
        }

        Component title = MiniMessage.miniMessage().deserialize(Hooks.processPlaceholders(viewer, Settings.getDyeMenuName()), nexoTags());
        // The default inventory provider legacy-serializes the title, which drops the font a glyph
        // background is drawn in, so build the inventory straight from the Component.
        Gui gui = new ChestGuiBuilder()
                .rows(ROWS)
                .title(title)
                .inventory((menuTitle, owner, size) -> Bukkit.createInventory(owner, size, menuTitle))
                .create();
        gui.setUpdating(true);
        gui.setDefaultClickAction(event -> {
            event.setCancelled(true);
        });
        gui.setDefaultTopClickAction(event -> {
            event.setCancelled(true);
            if (event.getSlot() == OUTPUT_SLOT) {
                gui.close(event.getWhoClicked());
                ItemStack outputItem = event.getInventory().getItem(OUTPUT_SLOT);
                Color color = NMSHandlers.getHandler().getUtilHandler().getColor(outputItem);
                if (color != null) cosmeticHolder.addCosmetic(cosmetic, color);
                else cosmeticHolder.addCosmetic(cosmetic);
            }
        });

        final ItemStack dyingItemStack = cosmetic.getItem();
        // The cosmetic's own configured color (its "item.color" in the cosmetics config) is what shows
        // here before the viewer has touched anything, so it doubles as the picker's initial selection.
        final Color currentColor = dyingItemStack.hasItemMeta() ? NMSHandlers.getHandler().getUtilHandler().getColor(dyingItemStack) : null;

        gui.setItem(INPUT_SLOT, guiItem(dyingItemStack));
        gui.setItem(OUTPUT_SLOT, guiItem(dyingItemStack));

        if (BACK_BUTTON != null) {
            GuiItem backItem = guiItem(BACK_BUTTON.item().clone());
            backItem.setAction(event -> {
                event.setCancelled(true);
                Actions.runActions(viewer, cosmeticHolder, BACK_BUTTON.actions());

                // Reopening runs a tick later, which also gets us out of this click event.
                Menu previous = Menus.getLastOpened(viewer.getUniqueId());
                if (previous != null) previous.openMenu(viewer, cosmeticHolder);
                else gui.close(viewer);
            });
            gui.setItem(BACK_BUTTON.slot(), backItem);
        }

        if (INFO_BUTTON != null) {
            gui.setItem(INFO_BUTTON.slot(), guiItem(INFO_BUTTON.item().clone()));
        }

        AtomicInteger ran = new AtomicInteger(0);
        PRIMARY_COLORS_SLOTS.forEach(i -> {
            ItemStack primaryColorItem = cosmetic.getItem();
            if (PRIMARY_COLOR_ITEM != null) primaryColorItem = PRIMARY_COLOR_ITEM;

            int pRan = ran.getAndAdd(1);
            if (pRan >= PRIMARY_COLORS.size()) {
                MessagesUtil.sendDebugMessages("There are less primary colors than slots for primary colors!", Level.WARNING);
                return;
            }
            PrimaryColor primaryColor = PRIMARY_COLORS.get(pRan);
            boolean isCurrentPrimary = matchesBucket(primaryColor, currentColor);

            primaryColorItem.setItemMeta(ColorBuilder.color(primaryColorItem.getItemMeta(), primaryColor.color));
            primaryColorItem.editMeta(itemMeta -> {
                itemMeta.displayName(MiniMessage.miniMessage().deserialize(primaryColor.name()).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            });
            GuiItem guiItem = guiItem(primaryColorItem);

            guiItem.setAction(event -> {
                event.setCancelled(true);
                selectPrimaryColor(gui, cosmetic, primaryColor, null);
            });
            gui.setItem(i, guiItem);

            // Pre-selects the bucket the cosmetic's current color already belongs to, so its secondary
            // row and the output preview are showing before the viewer clicks anything. The exact shade
            // is passed through so a default that's actually a subcolor (e.g. Azure under Blue) previews
            // as Azure, not the primary Blue it's filed under.
            if (isCurrentPrimary) selectPrimaryColor(gui, cosmetic, primaryColor, currentColor);
        });


        gui.open(viewer);
    }

    /**
     * Applies a primary color to the output preview and (re)populates the secondary-color row underneath it.
     * @param outputColor the exact shade to preview - the primary's own color when the viewer picked it by
     *                     hand ({@code null}), or the cosmetic's real current color when pre-selecting on
     *                     open, which may be one of this primary's subcolors rather than the primary itself.
     */
    private void selectPrimaryColor(@NotNull Gui gui, @NotNull Cosmetic cosmetic, @NotNull PrimaryColor primaryColor, @Nullable Color outputColor) {
        ItemStack cosmeticItem = cosmetic.getItem();
        cosmeticItem.setItemMeta(ColorBuilder.color(cosmeticItem.getItemMeta(), outputColor != null ? outputColor : primaryColor.color));
        gui.updateItem(OUTPUT_SLOT, guiItem(cosmeticItem));

        List<SecondaryColor> secondaryColors = primaryColor.secondaryColors();
        AtomicInteger secondaryRan = new AtomicInteger(0);
        SECONDARY_COLORS_SLOTS.forEach(slot -> {
            int sRan = secondaryRan.getAndAdd(1);
            if (sRan >= secondaryColors.size()) {
                MessagesUtil.sendDebugMessages("There are less secondary colors than slots for primary color " + primaryColor.name + "!", Level.WARNING);
                return;
            }
            SecondaryColor secondaryColor = secondaryColors.get(sRan);

            ItemStack secondaryItem = cosmetic.getItem();
            if (SECONDARY_COLOR_ITEM != null) secondaryItem = SECONDARY_COLOR_ITEM;
            secondaryItem.setItemMeta(ColorBuilder.color(secondaryItem.getItemMeta(), secondaryColor.color));
            secondaryItem.editMeta(itemMeta -> {
                itemMeta.displayName(MiniMessage.miniMessage().deserialize(secondaryColor.name()).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            });
            GuiItem secondaryGuiItem = guiItem(secondaryItem);
            secondaryGuiItem.setAction(secondaryEvent -> {
                ItemStack secondaryItemStack = cosmetic.getItem();
                secondaryItemStack.setItemMeta(ColorBuilder.color(secondaryItemStack.getItemMeta(), secondaryColor.color));
                gui.updateItem(OUTPUT_SLOT, guiItem(secondaryItemStack));
            });

            gui.updateItem(slot, secondaryGuiItem);
        });
    }

    private static boolean colorsEqual(@NotNull Color a, @NotNull Color b) {
        return a.asRGB() == b.asRGB();
    }

    /** Whether {@code color} is this primary's own shade or one of its secondary shades. */
    private static boolean matchesBucket(@NotNull PrimaryColor primaryColor, @Nullable Color color) {
        if (color == null) return false;
        if (colorsEqual(primaryColor.color(), color)) return true;
        for (SecondaryColor secondaryColor : primaryColor.secondaryColors()) {
            if (colorsEqual(secondaryColor.color(), color)) return true;
        }
        return false;
    }

    /**
     * Loads all primary colors from the configuration node
     * @param rootNode The root configuration node containing color definitions
     * @return List of PrimaryColor objects
     */
    public static List<PrimaryColor> loadColorsFromConfig(ConfigurationNode rootNode) {
        List<PrimaryColor> primaryColors = new ArrayList<>();

        // Iterate through all child nodes (each represents a primary color)
        Map<Object, ? extends ConfigurationNode> colorNodes = rootNode.childrenMap();

        for (Map.Entry<Object, ? extends ConfigurationNode> entry : colorNodes.entrySet()) {
            ConfigurationNode colorNode = entry.getValue();

            try {
                PrimaryColor primaryColor = parsePrimaryColor(colorNode);
                primaryColors.add(primaryColor);
            } catch (Exception e) {
                // Log error but continue processing other colors
                System.err.println("Failed to parse primary color: " + entry.getKey() + " - " + e.getMessage());
            }
        }

        return primaryColors;
    }

    /**
     * Parses a single primary color from a configuration node
     */
    private static PrimaryColor parsePrimaryColor(ConfigurationNode node) {
        // Kept as the raw MiniMessage string - swatch display names are built by re-deserializing this,
        // so stripping it down to plain text here would silently swallow the configured color.
        String nameWithFormatting = node.node("name").getString("");

        // Get primary color hex value
        String colorHex = node.node("color").getString("#FFFFFF");
        Color primaryColor = HMCCServerUtils.hex2Rgb(colorHex);

        // Parse secondary colors
        List<SecondaryColor> secondaryColors = new ArrayList<>();
        ConfigurationNode subcolorsNode = node.node("subcolors");

        if (!subcolorsNode.virtual() && subcolorsNode.isList()) {
            List<? extends ConfigurationNode> subcolorList = subcolorsNode.childrenList();

            for (ConfigurationNode subcolorNode : subcolorList) {
                try {
                    SecondaryColor secondaryColor = parseSecondaryColor(subcolorNode);
                    secondaryColors.add(secondaryColor);
                } catch (Exception e) {
                    System.err.println("Failed to parse secondary color: " + e.getMessage());
                }
            }
        }

        return new PrimaryColor(nameWithFormatting, primaryColor, secondaryColors);
    }

    /**
     * Parses a single secondary color from a configuration node
     */
    private static SecondaryColor parseSecondaryColor(ConfigurationNode node) {
        // Kept as the raw MiniMessage string, same reasoning as parsePrimaryColor above.
        String nameWithFormatting = node.node("name").getString("");

        // Get secondary color hex value
        String colorHex = node.node("color").getString("#FFFFFF");
        Color secondaryColor = HMCCServerUtils.hex2Rgb(colorHex);

        return new SecondaryColor(nameWithFormatting, secondaryColor);
    }


    public record PrimaryColor(String name, Color color, List<SecondaryColor> secondaryColors) {}

    public record SecondaryColor(String name, Color color) {}
}

