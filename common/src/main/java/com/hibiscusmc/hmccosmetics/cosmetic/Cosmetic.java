package com.hibiscusmc.hmccosmetics.cosmetic;

import com.hibiscusmc.hmccosmetics.cosmetic.behavior.CosmeticUpdateBehavior;
import com.hibiscusmc.hmccosmetics.user.CosmeticUser;
import com.hibiscusmc.hmccosmetics.util.MessagesUtil;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import me.lojosho.hibiscuscommons.config.serializer.ItemSerializer;
import me.lojosho.hibiscuscommons.util.AdventureUtils;
import me.lojosho.shaded.configurate.ConfigurationNode;
import me.lojosho.shaded.configurate.serialize.SerializationException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

@Getter
@Setter
public abstract class Cosmetic {
    private static final String LIMITED_GLYPH_ID = "cosmetic_limited";
    private static final String DYEABLE_GLYPH_ID = "cosmetic_dyeable";
    private static final int DESCRIPTION_WRAP_WIDTH = 35;

    protected static ItemStack UNDEFINED_DISPLAY_ITEM_STACK;

    static {
        UNDEFINED_DISPLAY_ITEM_STACK = new ItemStack(Material.BARRIER);

        ItemMeta meta = UNDEFINED_DISPLAY_ITEM_STACK.getItemMeta();
        if (meta != null) {
            // Legacy methods for Spigot >:(
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', "&cUndefined Item Display"));
            meta.setLore(List.of(
                    ChatColor.translateAlternateColorCodes('&', "&cPlease check your configurations & console to"),
                    ChatColor.translateAlternateColorCodes('&', "&censure there are no errors.")));
        }
        UNDEFINED_DISPLAY_ITEM_STACK.setItemMeta(meta);
    }

    /** Identifier of the cosmetic. */
    private String id;

    /** Permission to use the cosmetic. */
    private String permission;

    /** The display {@link ItemStack} of the cosmetic. */
    @Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
    private ItemStack item;

    /** The material string of the cosmetic. */
    private String material;

    /** The {@link CosmeticSlot} this cosmetic occupies. */
    private CosmeticSlot slot;

    /** Whether the cosmetic is dyeable or not. */
    private boolean dyeable;

    /** Whether the cosmetic is enabled. Disabled cosmetics are hidden from menus and cannot be equipped. */
    private boolean enabled;

    /** The rarity tier of the cosmetic, or null if it has none. */
    private Rarity rarity;

    /** Whether the cosmetic is a limited-time/limited-availability item; shown as a badge next to the rarity glyph. */
    private boolean limited;

    /** Short flavor text shown word-wrapped below the badge line, or null if it has none. */
    private String description;

    /** The config for the cosmetic */
    private ConfigurationNode config;

    protected Cosmetic(@NotNull String id, @NotNull ConfigurationNode config) {
        this.id = id;
        this.config = config;

        if (!config.node("permission").virtual()) {
            this.permission = config.node("permission").getString();
        } else {
            this.permission = null;
        }

        if (!config.node("item").virtual()) {
            this.material = config.node("item", "material").getString();
            try {
                this.item = generateItemStack(config.node("item"));
            } catch(Exception ex) {
                MessagesUtil.sendDebugMessages("Forcing %s to use undefined display".formatted(getId()));
                this.item = UNDEFINED_DISPLAY_ITEM_STACK;
            }
        }

        MessagesUtil.sendDebugMessages("Slot: " + config.node("slot").getString());
        this.slot = CosmeticSlot.valueOf(config.node("slot").getString());

        this.dyeable = config.node("dyeable").getBoolean(false);
        MessagesUtil.sendDebugMessages("Dyeable " + dyeable);

        this.enabled = config.node("enabled").getBoolean(true);
        MessagesUtil.sendDebugMessages("Enabled " + enabled);

        String rarityRaw = config.node("rarity").getString();
        this.rarity = Rarity.fromConfig(rarityRaw);
        if (rarityRaw != null && !rarityRaw.isBlank() && this.rarity == null) {
            MessagesUtil.sendDebugMessages("Invalid rarity '" + rarityRaw + "' for cosmetic " + getId());
        }

        this.limited = config.node("limited").getBoolean(false);

        this.description = config.node("description").getString();
    }

    protected Cosmetic(String id, String permission, ItemStack item, String material, CosmeticSlot slot, boolean dyeable) {
        this.id = id;
        this.permission = permission;
        this.item = item;
        this.material = material;
        this.slot = slot;
        this.dyeable = dyeable;
        this.enabled = true;
    }

    public boolean requiresPermission() {
        return permission != null;
    }

    /** Plain-text display name (formatting stripped), used for alphabetical menu sorting; falls back to {@link #id}. */
    public String getPlainName() {
        ItemStack display = getItem();
        if (display != null && display.hasItemMeta()) {
            Component name = display.getItemMeta().displayName();
            if (name != null) return PlainTextComponentSerializer.plainText().serialize(name);
        }
        return id;
    }

    /**
     * Colors the item's display name by {@link #rarity} (if set) and prepends the badge line
     * (rarity + limited + dyeable glyphs, no spaces between them) and the word-wrapped
     * {@link #description} below it, in that order. No-op if none of those apply.
     */
    public void styleMeta(@NotNull ItemMeta meta) {
        if (rarity != null) {
            Component name = meta.displayName();
            if (name != null) {
                meta.displayName(name.color(rarity.color()).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            }
        }

        List<Component> tagLines = buildTagLines();
        if (!tagLines.isEmpty()) {
            List<Component> lore = new ArrayList<>(tagLines);
            if (meta.hasLore() && meta.lore() != null) lore.addAll(meta.lore());
            meta.lore(lore);
        }
    }

    private List<Component> buildTagLines() {
        List<Component> lines = new ArrayList<>();

        String badgeLine = buildBadgeLine();
        if (badgeLine != null) {
            lines.add(AdventureUtils.MINI_MESSAGE.deserialize(badgeLine, MessagesUtil.nexoTags())
                    .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
        }

        if (description != null && !description.isBlank()) {
            lines.addAll(wrapDescription(description));
        }

        return lines;
    }

    /** Rarity, limited and dyeable glyphs, in that order, {@code <shift:1>} apart and no other spacing. */
    public @Nullable String buildBadgeLine() {
        return buildBadgeLine(true);
    }

    /**
     * The badge line, optionally without the dyeable glyph.
     * <p>
     * Public so the Bedrock form can draw the same badges from this definition instead of from a
     * second copy of the glyph ids, which would drift away from this one.
     * </p>
     *
     * @param includeDyeable false for Bedrock, where nothing can be dyed at all, so the badge would
     *                       only advertise something that edition has no way to use
     */
    public @Nullable String buildBadgeLine(boolean includeDyeable) {
        List<String> glyphIds = new ArrayList<>();
        if (rarity != null) glyphIds.add(rarity.glyphId());
        if (limited) glyphIds.add(LIMITED_GLYPH_ID);
        if (dyeable && includeDyeable) glyphIds.add(DYEABLE_GLYPH_ID);
        if (glyphIds.isEmpty()) return null;

        StringBuilder line = new StringBuilder();
        for (int i = 0; i < glyphIds.size(); i++) {
            if (i > 0) line.append("<shift:1>");
            line.append("<glyph:").append(glyphIds.get(i)).append(">");
        }
        return line.toString();
    }

    /** Matches MiniMessage tags (e.g. {@code <blue>}, {@code <#ff00ff>}), so they don't count toward wrap width. */
    private static final java.util.regex.Pattern MINI_MESSAGE_TAG = java.util.regex.Pattern.compile("<[^<>]*>");

    private static int visibleLength(String text) {
        return MINI_MESSAGE_TAG.matcher(text).replaceAll("").length();
    }

    private static List<Component> wrapDescription(String text) {
        List<Component> wrapped = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (visibleLength(candidate) <= DESCRIPTION_WRAP_WIDTH) {
                line = new StringBuilder(candidate);
            } else {
                if (!line.isEmpty()) wrapped.add(deserializeDescriptionLine(line.toString()));
                line = new StringBuilder(word);
            }
        }
        if (!line.isEmpty()) wrapped.add(deserializeDescriptionLine(line.toString()));
        return wrapped;
    }

    private static Component deserializeDescriptionLine(String line) {
        return AdventureUtils.MINI_MESSAGE.deserialize(line, MessagesUtil.nexoTags())
                .colorIfAbsent(NamedTextColor.GRAY)
                .decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /**
     * Dispatched when an update is requested upon the cosmetic. Instead, you should use {@link CosmeticUser#updateCosmetic(CosmeticSlot)})}
     * @param user the user to preform the update against
     */
    @Deprecated(since = "2.8.2")
    public void update(CosmeticUser user) {
        if(this instanceof CosmeticUpdateBehavior behavior) {
            behavior.dispatchUpdate(user);
        }
    }

    /**
     * Action preformed on the update. Instead, you should use {@link CosmeticUser#updateCosmetic(CosmeticSlot)})}
     * @param user the user to preform the update against
     */
    @Deprecated(since = "2.8.2")
    protected void doUpdate(final CosmeticUser user) {
        // #update should be the preferred way of interacting with this api now.
        this.update(user);
    }

    @Nullable
    public ItemStack getItem() {
        if (item == null) return null;
        return item.clone();
    }

    /**
     * Generate an {@link ItemStack} from a {@link ConfigurationNode}.
     * @param config the configuration node
     * @return the {@link ItemStack}
     */
    protected ItemStack generateItemStack(ConfigurationNode config) {
        try {
            ItemStack item = ItemSerializer.INSTANCE.deserialize(ItemStack.class, config);
            if (item == null) {
                MessagesUtil.sendDebugMessages("Unable to create item for " + getId(), Level.SEVERE);
                return new ItemStack(Material.AIR);
            }
            return item;
        } catch (SerializationException e) {
            MessagesUtil.sendDebugMessages("Fatal error encountered for " + getId() + " regarding Serialization of item", Level.SEVERE);
            throw new RuntimeException(e);
        }
    }

    /**
     * While cosmetics registered in HMCC are made through a configuration, cosmetics registered from other plugins
     * may not and instead opt for {@link Cosmetic#Cosmetic(String, String, ItemStack, String, CosmeticSlot, boolean)}, which doesn't use a config.
     * This should be used only for reference.
     */
    @ApiStatus.Experimental
    public @Nullable ConfigurationNode getConfig() {
        return config;
    }
}
