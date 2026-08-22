package com.hibiscusmc.hmccosmetics.gui;

import com.hibiscusmc.hmccosmetics.HMCCosmeticsPlugin;
import com.hibiscusmc.hmccosmetics.api.events.PlayerMenuCloseEvent;
import com.hibiscusmc.hmccosmetics.api.events.PlayerMenuOpenEvent;
import com.hibiscusmc.hmccosmetics.config.Settings;
import com.hibiscusmc.hmccosmetics.cosmetic.Cosmetic;
import com.hibiscusmc.hmccosmetics.cosmetic.CosmeticHolder;
import com.hibiscusmc.hmccosmetics.cosmetic.Cosmetics;
import com.hibiscusmc.hmccosmetics.cosmetic.Rarity;
import com.hibiscusmc.hmccosmetics.gui.action.Actions;
import com.hibiscusmc.hmccosmetics.gui.type.ShadingType;
import com.hibiscusmc.hmccosmetics.gui.type.Type;
import com.hibiscusmc.hmccosmetics.gui.type.Types;
import com.hibiscusmc.hmccosmetics.gui.type.types.TypeCosmetic;
import com.hibiscusmc.hmccosmetics.user.CosmeticUser;
import com.hibiscusmc.hmccosmetics.user.manager.UserWardrobeManager;
import com.hibiscusmc.hmccosmetics.util.MessagesUtil;
import dev.triumphteam.gui.builder.item.ItemBuilder;
import dev.triumphteam.gui.guis.Gui;
import dev.triumphteam.gui.guis.GuiItem;
import io.papermc.paper.datacomponent.item.CustomModelData;
import lombok.Getter;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.DyedItemColor;
import me.lojosho.hibiscuscommons.config.serializer.ItemSerializer;
import me.lojosho.hibiscuscommons.hooks.Hooks;
import me.lojosho.hibiscuscommons.util.AdventureUtils;
import me.lojosho.shaded.configurate.ConfigurationNode;
import me.lojosho.shaded.configurate.serialize.SerializationException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.Collator;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class Menu {

    /** Locale-aware comparator for {@link Cosmetic#getPlainName()}, used to alphabetize paginated cosmetics. */
    private static final Collator NAME_COLLATOR = Collator.getInstance(new Locale("pt", "BR"));
    static {
        NAME_COLLATOR.setStrength(Collator.PRIMARY);
    }

    @Getter
    private final String id;
    @Getter
    private final String title;
    @Getter
    private final int rows;
    @Getter
    private final Long cooldown;
    @Getter
    private final ConfigurationNode config;
    @Getter
    private final String permissionNode;
    private final HashMap<Integer, List<MenuItem>> items;
    /** Items declared with {@code paginated: true}, in config order; empty when the menu has no pagination. */
    private final List<MenuItem> paginatedItems;
    private final Pagination pagination;
    @Getter
    private final int refreshRate;

    public Menu(String id, @NotNull ConfigurationNode config) {
        this.id = config.node("id").getString(id);
        this.config = config;

        title = config.node("title").getString("chest");
        rows = config.node("rows").getInt(1);
        cooldown = config.node("click-cooldown").getLong(Settings.getDefaultMenuCooldown());
        permissionNode = config.node("permission").getString("");
        refreshRate = config.node("refresh-rate").getInt(-1);

        items = new HashMap<>();
        paginatedItems = new ArrayList<>();
        pagination = parsePagination();
        setupItems();

        Menus.addMenu(this);
    }

    private void setupItems() {
        for (ConfigurationNode config : config.node("items").childrenMap().values()) {
            // "for-each" turns one item block into a template: {id} is substituted with each entry of
            // the list below, so a whole category of near-identical cosmetics (same lore/type shape,
            // only the id differing) can be declared once instead of copy-pasted per cosmetic.
            if (!config.node("for-each").virtual()) {
                List<String> ids;
                try {
                    ids = config.node("for-each").getList(String.class);
                } catch (SerializationException e) {
                    MessagesUtil.sendDebugMessages("Unable to read for-each list for " + config.key());
                    continue;
                }
                if (ids == null) continue;

                for (String id : ids) {
                    ConfigurationNode templated = config.copy();
                    substituteId(templated, id);
                    setupItem(templated);
                }
                continue;
            }

            setupItem(config);
        }
    }

    /** Replaces every occurrence of the literal {@code {id}} in {@code node}'s string values, recursively. */
    private void substituteId(ConfigurationNode node, String id) {
        if (node.isMap()) {
            for (ConfigurationNode child : node.childrenMap().values()) substituteId(child, id);
        } else if (node.isList()) {
            for (ConfigurationNode child : node.childrenList()) substituteId(child, id);
        } else if (node.raw() instanceof String raw && raw.contains("{id}")) {
            try {
                node.set(raw.replace("{id}", id));
            } catch (SerializationException e) {
                MessagesUtil.sendDebugMessages("Failed to substitute {id} for " + id);
            }
        }
    }

    private void setupItem(ConfigurationNode config) {
        // Paginated items are placed by page rather than by a fixed slot, so they carry no "slots".
        boolean paginated = pagination != null && config.node("paginated").getBoolean(false);

        List<Integer> slots = List.of();
        if (!paginated) {
            List<String> slotString;
            try {
                slotString = config.node("slots").getList(String.class);
            } catch (SerializationException e) {
                return;
            }
            if (slotString == null) {
                MessagesUtil.sendDebugMessages("Unable to get valid slot for " + config.key().toString());
                return;
            }

            slots = getSlots(slotString);

            if (slots.isEmpty()) {
                MessagesUtil.sendDebugMessages("Slot is empty for " + config.key().toString());
                return;
            }
        }

        ItemStack item;
        try {
            item = ItemSerializer.INSTANCE.deserialize(ItemStack.class, config.node("item"));
        } catch (SerializationException e) {
            MessagesUtil.sendDebugMessages("Unable to get valid item for " + config.key().toString() + " " + e.getMessage());
            return;
        }

        if (item == null) {
            MessagesUtil.sendDebugMessages("Something went wrong with the item creation for " + config.key().toString());
            return;
        }

        int priority = config.node("priority").getInt(1);

        Type type = Types.getDefaultType();
        if (!config.node("type").virtual()) {
            String typeId = config.node("type").getString("");
            if (Types.isType(typeId)) type = Types.getType(typeId);
        }

        if (type instanceof TypeCosmetic) {
            Cosmetic itemCosmetic = Cosmetics.getCosmetic(config.node("cosmetic").getString(""));
            if (itemCosmetic != null && !itemCosmetic.isEnabled()) {
                MessagesUtil.sendDebugMessages("Skipping disabled cosmetic in menu item " + config.key());
                return;
            }
        }

        if (paginated) {
            paginatedItems.add(new MenuItem(slots, item, type, priority, config));
            return;
        }

        for (Integer slot : slots) {
            MenuItem menuItem = new MenuItem(slots, item, type, priority, config);
            if (items.containsKey(slot)) {
                List<MenuItem> menuItems = items.get(slot);
                menuItems.add(menuItem);
                menuItems.sort(priorityCompare);
                items.put(slot, menuItems);
            } else {
                items.put(slot, new ArrayList<>(List.of(menuItem)));
            }
        }
    }

    /**
     * The optional {@code pagination} block of a menu. The arrows and the bar the page counter sits in
     * are painted into the background glyph, so the config supplies the title fragments that light them
     * up rather than any item art: {@code titleReset} rewinds the title cursor back to the left edge of
     * the background, and the previous/next fragments are the shifts and glyphs for the enabled and
     * disabled states of each arrow.
     */
    private record Pagination(@NotNull List<Integer> slots, @NotNull List<Integer> previousSlots,
                              @NotNull List<Integer> nextSlots, @NotNull String titleReset,
                              @NotNull String previous, @NotNull String previousDisabled,
                              @NotNull String next, @NotNull String nextDisabled,
                              @NotNull String indicator, int indicatorOffset,
                              @Nullable ItemStack previousButton, @Nullable ItemStack nextButton,
                              @NotNull List<String> actions) {}

    /**
     * The page a single viewer is on, plus the title last pushed to them. One instance per open, since
     * a {@link Menu} is a single shared object that every viewer of that config file looks at.
     */
    private static final class PageState {
        private int page = 1;
        private String title;
    }

    private @Nullable Pagination parsePagination() {
        ConfigurationNode node = config.node("pagination");
        if (node.virtual()) return null;

        List<Integer> slots = getSlots(getStringList(node.node("slots")));
        if (slots.isEmpty()) {
            MessagesUtil.sendDebugMessages("Pagination for menu " + id + " has no slots, ignoring it");
            return null;
        }

        return new Pagination(
                slots,
                getSlots(getStringList(node.node("previous-slots"))),
                getSlots(getStringList(node.node("next-slots"))),
                node.node("title-reset").getString(""),
                node.node("previous").getString(""),
                node.node("previous-disabled").getString(""),
                node.node("next").getString(""),
                node.node("next-disabled").getString(""),
                node.node("indicator").getString(""),
                node.node("indicator-offset").getInt(0),
                getPaginationButton(node.node("previous-item")),
                getPaginationButton(node.node("next-item")),
                getStringList(node.node("actions")));
    }

    @Nullable
    private ItemStack getPaginationButton(@NotNull ConfigurationNode node) {
        if (node.virtual()) return null;

        try {
            return ItemSerializer.INSTANCE.deserialize(ItemStack.class, node);
        } catch (SerializationException e) {
            MessagesUtil.sendDebugMessages("Unable to get a valid pagination item for " + id + " " + e.getMessage());
            return null;
        }
    }

    @NotNull
    private List<String> getStringList(@NotNull ConfigurationNode node) {
        try {
            List<String> list = node.getList(String.class);
            return list == null ? List.of() : list;
        } catch (SerializationException e) {
            MessagesUtil.sendDebugMessages("Unable to read " + node.key() + " as a list of strings in menu " + id);
            return List.of();
        }
    }

    public void openMenu(CosmeticUser user) {
        openMenu(user, false);
    }

    public void openMenu(@NotNull CosmeticUser user, boolean ignorePermission) {
        Player player = user.getPlayer();
        if (player == null) return;
        openMenu(player, user, ignorePermission);
    }

    public void openMenu(@NotNull Player viewer, @NotNull CosmeticHolder cosmeticHolder) {
        openMenu(viewer, cosmeticHolder, false);
    }

    public void openMenu(@NotNull Player viewer, @NotNull CosmeticHolder cosmeticHolder, boolean ignorePermission) {
        if (!ignorePermission && !permissionNode.isEmpty()) {
            if (!viewer.hasPermission(permissionNode) && !viewer.isOp()) {
                MessagesUtil.sendMessage(viewer, "no-permission");
                return;
            }
        }
        Menus.setLastOpened(viewer.getUniqueId(), this);

        // The page arrows and counter live in the title, so the first page's title is composed here
        // rather than pushed later: updating a title replaces the inventory, which cannot be done
        // while the menu is still opening.
        PageState state = new PageState();
        state.page = wardrobeEntryPage(cosmeticHolder);
        state.title = pagination == null ? this.title : this.title + paginationTitle(state);
        // paginationTitle clamps the page it was handed, which matters when the remembered page no
        // longer exists because the menu got shorter (a cosmetic disabled, a permission lost).
        rememberWardrobePage(cosmeticHolder, state.page);

        // Deliberately not .type(GuiType.CHEST): that swaps the chest builder for a typed one, whose
        // container reports a single row, so every slot from 9 up fails validateSlot(). The custom
        // inventory provider is still needed because the default one legacy-serializes the title,
        // which drops the font the background glyph is drawn in.
        Gui gui = Gui.gui()
                .title(deserializeTitle(viewer, state.title))
                .rows(rows)
                .inventory((title, owner, size) -> Bukkit.createInventory(owner, size, title))
                .create();

        gui.setDefaultClickAction(event -> event.setCancelled(true));

        AtomicInteger taskid = new AtomicInteger(-1);
        gui.setOpenGuiAction(event -> {
            Runnable run = () -> {
                if (gui.getInventory().getViewers().isEmpty() && taskid.get() != -1) {
                    Bukkit.getScheduler().cancelTask(taskid.get());
                }

                updateMenu(viewer, cosmeticHolder, gui, state);
            };

            if (refreshRate != -1) {
                taskid.set(Bukkit.getScheduler().scheduleSyncRepeatingTask(HMCCosmeticsPlugin.getInstance(), run, 0, refreshRate));
            } else {
                run.run();
            }
        });

        gui.setCloseGuiAction(event -> {
            if (cosmeticHolder instanceof CosmeticUser user) {
                PlayerMenuCloseEvent closeEvent = new PlayerMenuCloseEvent(user, this, event.getReason());
                Bukkit.getScheduler().runTask(HMCCosmeticsPlugin.getInstance(), () -> Bukkit.getPluginManager().callEvent(closeEvent));
            }

            if (taskid.get() != -1) Bukkit.getScheduler().cancelTask(taskid.get());
        });

        Runnable openGuiTask = () -> {
            gui.open(viewer);
            updateMenu(viewer, cosmeticHolder, gui, state); // fixes shading? I know I do this twice but it's easier than writing a whole new class to deal with this shit
        };

        // API
        if (cosmeticHolder instanceof CosmeticUser user) {
            PlayerMenuOpenEvent event = new PlayerMenuOpenEvent(user, this);
            Bukkit.getScheduler().runTask(HMCCosmeticsPlugin.getInstance(), () -> {
                Bukkit.getPluginManager().callEvent(event);
                if (!event.isCancelled()) {
                    openGuiTask.run();
                }
            });
        }
        // Internal
        else {
            Bukkit.getScheduler().runTask(HMCCosmeticsPlugin.getInstance(), openGuiTask);
        }
    }

    private void updateMenu(Player viewer, CosmeticHolder cosmeticHolder, Gui gui, PageState state) {
        StringBuilder title = new StringBuilder(this.title);

        int row = 0;
        switch (Settings.getShadingType()) {
            case TEXT -> {
                for (int i = 0; i < gui.getInventory().getSize(); i++) {
                    // Handles the title
                    if (i % 9 == 0) {
                        if (row == 0) {
                            title.append(Settings.getFirstRowShift()); // Goes back to the start of the gui
                        } else {
                            title.append(Settings.getSequentRowShift());
                        }
                        row += 1;
                    } else {
                        title.append(Settings.getIndividualColumnShift()); // Goes to the next slot
                    }

                    boolean occupied = false;

                    if (items.containsKey(i)) {
                        // Handles the items
                        List<MenuItem> menuItems = items.get(i);
                        MenuItem item = menuItems.getFirst();
                        updateItem(viewer, cosmeticHolder, gui, i, state);

                        if (item.type() instanceof TypeCosmetic) {
                            Cosmetic cosmetic = Cosmetics.getCosmetic(item.itemConfig().node("cosmetic").getString(""));
                            if (cosmetic == null) continue;
                            if (cosmeticHolder.hasCosmeticInSlot(cosmetic)) {
                                title.append(Settings.getEquippedCosmeticColor());
                            } else {
                                if (cosmeticHolder.canEquipCosmetic(cosmetic, true)) {
                                    title.append(Settings.getEquipableCosmeticColor());
                                } else {
                                    title.append(Settings.getLockedCosmeticColor());
                                }
                            }
                            occupied = true;
                        }
                    }
                    if (occupied) {
                        title.append(Settings.getBackground().replaceAll("<row>", String.valueOf(row)));
                    } else {
                        title.append(Settings.getClearBackground().replaceAll("<row>", String.valueOf(row)));
                    }
                }
            }
            case MODERN -> {
                TextColor equippableColor = resolveStateColor(Settings.getEquipableCosmeticColor());
                TextColor equippedColor = resolveStateColor(Settings.getEquippedCosmeticColor());
                TextColor lockedColor = resolveStateColor(Settings.getLockedCosmeticColor());

                for (int i = 0; i < gui.getInventory().getSize(); i++) {
                    if (items.containsKey(i)) {
                        List<MenuItem> menuItems = items.get(i);
                        MenuItem item = menuItems.getFirst();
                        updateItem(viewer, cosmeticHolder, gui, i, (itemStack -> {
                            // Handles the items
                            if (item.type() instanceof TypeCosmetic) {
                                Cosmetic cosmetic = Cosmetics.getCosmetic(item.itemConfig().node("cosmetic").getString(""));
                                if (cosmetic == null) return;

                                Key itemKey = Key.key("hmccosmetics", cosmetic.getId() + "_shading");
                                itemStack.setData(DataComponentTypes.ITEM_MODEL, itemKey);



                                CustomModelData.Builder builder = CustomModelData.customModelData();
                                if (cosmeticHolder.hasCosmeticInSlot(cosmetic)) builder.addColor(Color.fromRGB(equippedColor.value()));
                                else if (cosmeticHolder.canEquipCosmetic(cosmetic, true)) builder.addColor(Color.fromRGB(equippableColor.value()));
                                else builder.addColor(Color.fromRGB(lockedColor.value()));

                                CustomModelData cmd = itemStack.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
                                if (cmd != null) builder.addFlags(cmd.flags()).addFloats(cmd.floats()).addStrings(cmd.strings());

                                itemStack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, builder.build());
                            }
                        }), state);
                    }
                }
            }
            case null, default -> {
                for (int i = 0; i < gui.getInventory().getSize(); i++) {
                    if (items.containsKey(i)) {
                        updateItem(viewer, cosmeticHolder, gui, i, state);
                    }
                }
            }
        }

        // Pagination fills its own slots and contributes the arrows and the page counter to the title,
        // so it runs for every shading type rather than only the one that rewrites the title.
        if (pagination != null) {
            placePaginatedItems(viewer, cosmeticHolder, gui, state);
            title.append(paginationTitle(state));
        }

        if (Settings.getShadingType() != ShadingType.TEXT && pagination == null) return;

        // Only push a title that actually changed: updateTitle() reopens the inventory for everyone
        // looking at it, so pushing on every click would flicker the whole menu.
        String rendered = title.toString();
        if (rendered.equals(state.title)) return;

        // Pushing a title swaps the Gui's inventory for a fresh one and reopens it for each viewer.
        // While the menu is still opening the player is not a viewer yet, so the swap would leave them
        // looking at an inventory the Gui no longer tracks and every click would be ignored. Leave
        // state.title alone so the push is retried once someone is actually watching.
        if (gui.getInventory().getViewers().isEmpty()) return;

        state.title = rendered;
        MessagesUtil.sendDebugMessages("Updated menu with title " + rendered);
        gui.updateTitle(deserializeTitle(viewer, rendered));
    }

    private int pageCount() {
        return Math.max(1, (int) Math.ceil((double) paginatedItems.size() / pagination.slots().size()));
    }

    /**
     * The fragment appended to the title for the current page: each arrow in its enabled or disabled
     * form, then the page counter centred in the bar painted into the background.
     */
    @NotNull
    private String paginationTitle(PageState state) {
        int pages = pageCount();
        state.page = Math.clamp(state.page, 1, pages);

        String counter = state.page + "/" + pages;
        return pagination.titleReset()
                + (state.page > 1 ? pagination.previous() : pagination.previousDisabled())
                + (state.page < pages ? pagination.next() : pagination.nextDisabled())
                + "<shift:" + (-textWidth(counter) / 2 + pagination.indicatorOffset()) + ">"
                + pagination.indicator().replace("%page%", String.valueOf(state.page)).replace("%pages%", String.valueOf(pages));
    }

    /** Places the current page's items, clearing any slot the page does not reach, plus both arrows. */
    private void placePaginatedItems(Player viewer, CosmeticHolder cosmeticHolder, Gui gui, PageState state) {
        List<Integer> slots = pagination.slots();
        int pages = pageCount();
        state.page = Math.clamp(state.page, 1, pages);

        List<MenuItem> sortedItems = sortPaginatedItems(cosmeticHolder);
        int start = (state.page - 1) * slots.size();
        for (int i = 0; i < slots.size(); i++) {
            int slot = slots.get(i);
            int index = start + i;
            if (index >= sortedItems.size()
                    || !placeItem(viewer, cosmeticHolder, gui, slot, sortedItems.get(index), null, state)) {
                gui.removeItem(slot);
            }
        }

        placePageButtons(viewer, cosmeticHolder, gui, state, pagination.previousSlots(), pagination.previousButton(), -1, state.page > 1);
        placePageButtons(viewer, cosmeticHolder, gui, state, pagination.nextSlots(), pagination.nextButton(), 1, state.page < pages);
    }

    /**
     * {@link #paginatedItems} sorted for {@code cosmeticHolder}: cosmetics the holder can already equip
     * come first, then descending by {@link Rarity}, then alphabetically by name. Items whose cosmetic
     * can't be resolved (or aren't cosmetics at all) sort as if unranked and keep their relative order.
     */
    @NotNull
    private List<MenuItem> sortPaginatedItems(@NotNull CosmeticHolder cosmeticHolder) {
        List<MenuItem> sorted = new ArrayList<>(paginatedItems);
        sorted.sort(Comparator
                .comparing((MenuItem item) -> !ownsPaginatedCosmetic(item, cosmeticHolder))
                .thenComparing(Menu::paginatedRarityRank, Comparator.reverseOrder())
                .thenComparing(Menu::paginatedItemName, NAME_COLLATOR::compare));
        return sorted;
    }

    private static boolean ownsPaginatedCosmetic(@NotNull MenuItem item, @NotNull CosmeticHolder cosmeticHolder) {
        Cosmetic cosmetic = paginatedCosmetic(item);
        return cosmetic == null || cosmeticHolder.canEquipCosmetic(cosmetic, true);
    }

    private static int paginatedRarityRank(@NotNull MenuItem item) {
        Cosmetic cosmetic = paginatedCosmetic(item);
        Rarity rarity = cosmetic == null ? null : cosmetic.getRarity();
        return rarity == null ? -1 : rarity.ordinal();
    }

    @NotNull
    private static String paginatedItemName(@NotNull MenuItem item) {
        Cosmetic cosmetic = paginatedCosmetic(item);
        return cosmetic == null ? "" : cosmetic.getPlainName();
    }

    @Nullable
    private static Cosmetic paginatedCosmetic(@NotNull MenuItem item) {
        if (!(item.type() instanceof TypeCosmetic)) return null;
        return Cosmetics.getCosmetic(item.itemConfig().node("cosmetic").getString(""));
    }

    /**
     * Marks this menu as the one to reopen inside the wardrobe and answers the page to open it at: the
     * page it was left on when it is the same menu, otherwise the first. Menus opened outside a
     * wardrobe keep no such memory and always start at page one.
     */
    private int wardrobeEntryPage(@NotNull CosmeticHolder cosmeticHolder) {
        if (!(cosmeticHolder instanceof CosmeticUser user) || !user.isInWardrobe()) return 1;

        UserWardrobeManager wardrobe = user.getWardrobeManager();
        int page = wardrobe.getLastOpenMenu() == this ? wardrobe.getLastOpenPage() : 1;
        wardrobe.setLastOpenMenu(this);
        wardrobe.setLastOpenPage(page);
        return page;
    }

    private void rememberWardrobePage(@NotNull CosmeticHolder cosmeticHolder, int page) {
        if (!(cosmeticHolder instanceof CosmeticUser user) || !user.isInWardrobe()) return;
        user.getWardrobeManager().setLastOpenPage(page);
    }

    private void placePageButtons(Player viewer, CosmeticHolder cosmeticHolder, Gui gui, PageState state,
                                  @NotNull List<Integer> slots, @Nullable ItemStack button, int step, boolean enabled) {
        if (button == null) return;

        for (int slot : slots) {
            GuiItem guiItem = ItemBuilder.from(button.clone()).asGuiItem();
            guiItem.setAction(event -> {
                if (!enabled) return;
                state.page += step;
                rememberWardrobePage(cosmeticHolder, state.page);
                Actions.runActions(viewer, cosmeticHolder, pagination.actions());
                updateMenu(viewer, cosmeticHolder, gui, state);
            });
            gui.updateItem(slot, guiItem);
        }
    }

    /**
     * Width in pixels of {@code text} in the retro pixel fonts the page counter is drawn with, so it can
     * be centred in the bar. Every glyph is followed by a one pixel gap.
     */
    private static int textWidth(@NotNull String text) {
        if (text.isEmpty()) return 0;

        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += switch (text.charAt(i)) {
                case '\'', ',', '.', ':', ';', '!', '|' -> 1;
                case '(', ')' -> 2;
                case '1', '"', '{', '}', ' ', '[', ']' -> 3;
                case '+', '-', '=', '<', '>' -> 4;
                default -> 5;
            };
        }

        return width + text.length() - 1;
    }

    @NotNull
    private Component deserializeTitle(Player viewer, @NotNull String title) {
        return AdventureUtils.MINI_MESSAGE.deserialize(Hooks.processPlaceholders(viewer, title), MessagesUtil.nexoTags());
    }

    /**
     * Resolves a MiniMessage-formatted color string into a {@link TextColor}, falling back to white
     * if the string is null or carries no explicit color (avoids an NPE when used for MODERN shading dye).
     */
    private static TextColor resolveStateColor(String miniMessage) {
        if (miniMessage == null) return NamedTextColor.WHITE;
        TextColor color = MiniMessage.miniMessage().deserialize(miniMessage).color();
        return color != null ? color : NamedTextColor.WHITE;
    }

    private void updateItem(Player viewer, CosmeticHolder cosmeticHolder, Gui gui, int slot, PageState state) {
        updateItem(viewer, cosmeticHolder, gui, slot, null, state);
    }

    private void updateItem(Player viewer, CosmeticHolder cosmeticHolder, Gui gui, int slot, @Nullable Consumer<ItemStack> consumer, PageState state) {
        if (!items.containsKey(slot)) return;
        List<MenuItem> menuItems = items.get(slot);
        if (menuItems.isEmpty()) return;

        for (MenuItem item : menuItems) {
            if (placeItem(viewer, cosmeticHolder, gui, slot, item, consumer, state)) break;
        }
    }

    /**
     * Builds {@code item} for {@code viewer} and puts it in {@code slot}.
     *
     * @return whether the item was placed; an item that resolves to air is skipped so the next
     *         candidate for the slot gets its turn.
     */
    private boolean placeItem(Player viewer, CosmeticHolder cosmeticHolder, Gui gui, int slot, @NotNull MenuItem item,
                              @Nullable Consumer<ItemStack> consumer, PageState state) {
        Type type = item.type();
        ItemStack itemStack = item.item().clone();
        ItemStack modifiedItem = getMenuItem(viewer, cosmeticHolder, type, item.itemConfig(), itemStack, slot);
        if (consumer != null) {
            // Shading decoration is best-effort: a failure here must never stop the item from
            // being placed, otherwise the whole menu renders empty.
            try {
                consumer.accept(modifiedItem);
            } catch (Exception e) {
                MessagesUtil.sendDebugMessages("Failed to apply shading to menu item in slot " + slot + ": " + e.getMessage());
            }
        }
        if (modifiedItem.getType().isAir()) return false;

        GuiItem guiItem = ItemBuilder.from(modifiedItem).asGuiItem();
        guiItem.setAction(event -> {
            UUID uuid = viewer.getUniqueId();
            if (Settings.isMenuClickCooldown()) {
                Long userCooldown = Menus.getCooldown(uuid);
                if (userCooldown != 0 && (System.currentTimeMillis() - Menus.getCooldown(uuid) <= getCooldown())) {
                    MessagesUtil.sendDebugMessages("Cooldown for " + viewer.getUniqueId() + " System time: " + System.currentTimeMillis() + " Cooldown: " + Menus.getCooldown(viewer.getUniqueId()) + " Difference: " + (System.currentTimeMillis() - Menus.getCooldown(viewer.getUniqueId())));
                    MessagesUtil.sendMessage(viewer, "on-click-cooldown");
                    return;
                } else {
                    Menus.addCooldown(uuid, System.currentTimeMillis());
                }
            }
            MessagesUtil.sendDebugMessages("Updated Menu Item in slot number " + slot);
            final ClickType clickType = event.getClick();
            if (type != null) type.run(viewer, cosmeticHolder, item.itemConfig(), clickType);
            updateMenu(viewer, cosmeticHolder, gui, state);
        });

        MessagesUtil.sendDebugMessages("Set an item in slot " + slot + " in the menu of " + getId());
        gui.updateItem(slot, guiItem);
        return true;
    }

    @NotNull
    private List<Integer> getSlots(@NotNull List<String> slotString) {
        List<Integer> slots = new ArrayList<>();

        for (String a : slotString) {
            if (a.contains("-")) {
                String[] split = a.split("-");
                int min = Integer.parseInt(split[0]);
                int max = Integer.parseInt(split[1]);
                slots.addAll(getSlots(min, max));
            } else {
                slots.add(Integer.valueOf(a));
            }
        }

        return slots;
    }

    @NotNull
    private List<Integer> getSlots(int small, int max) {
        List<Integer> slots = new ArrayList<>();

        for (int i = small; i <= max; i++) slots.add(i);
        return slots;
    }

    @Contract("_, _, _, _, _, _ -> param4")
    @NotNull
    private ItemStack getMenuItem(Player viewer, CosmeticHolder cosmeticHolder, Type type, ConfigurationNode config, ItemStack itemStack, int slot) {
        if (!itemStack.hasItemMeta()) return itemStack;
        return type.setItem(viewer, cosmeticHolder, config, itemStack, slot);
    }

    public boolean canOpen(Player player) {
        if (permissionNode.isEmpty()) return true;
        return player.isOp() || player.hasPermission(permissionNode);
    }

    public static Comparator<MenuItem> priorityCompare = Comparator.comparing(MenuItem::priority).reversed();
}
