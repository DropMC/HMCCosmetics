package com.hibiscusmc.hmccosmetics.gui.bedrock;

import com.hibiscusmc.hmccosmetics.HMCCosmeticsPlugin;
import com.hibiscusmc.hmccosmetics.config.WardrobeSettings;
import com.hibiscusmc.hmccosmetics.cosmetic.Cosmetic;
import com.hibiscusmc.hmccosmetics.cosmetic.CosmeticSlot;
import com.hibiscusmc.hmccosmetics.cosmetic.Cosmetics;
import com.hibiscusmc.hmccosmetics.cosmetic.Rarity;
import com.hibiscusmc.hmccosmetics.cosmetic.types.CosmeticAuraType;
import com.hibiscusmc.hmccosmetics.gui.Menu;
import com.hibiscusmc.hmccosmetics.gui.Menus;
import com.hibiscusmc.hmccosmetics.hooks.misc.HookCommonsSkins;
import com.hibiscusmc.hmccosmetics.user.CosmeticUser;
import com.hibiscusmc.hmccosmetics.util.BedrockIcons;
import com.hibiscusmc.hmccosmetics.util.BedrockText;
import com.hibiscusmc.hmccosmetics.util.MessagesUtil;
import me.lojosho.hibiscuscommons.nms.NMSHandlers;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.util.FormImage;
import org.geysermc.floodgate.api.FloodgateApi;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The wardrobe menu as a Bedrock form, for players whose camera is pinned to the mannequin.
 * <p>
 * A Bedrock client will not open a container while a camera instruction is in effect, so the chest
 * menu everyone else gets does not reliably appear there however often the server opens it. A form
 * does, and a pinned camera still lets the player touch one, which makes it the way in on Bedrock.
 * </p>
 * Each button carries the cosmetic's own icon out of the converted pack ({@link BedrockIcons}), with
 * the rarity and badges under the name. The client does not tint a form image, so when
 * {@code wardrobe.bedrock-api-url} is set a dyeable icon is fetched already painted in its colour, and
 * an aura is shown as the player's own skin outlined in the aura's colour ({@link HookCommonsSkins}).
 * A dyeable cosmetic is put on through a {@link BedrockDyeForm}, the way Java opens its dye menu.
 */
public final class BedrockWardrobeForm {

    private static final String TITLE = "Cosméticos";
    private static final String PICK_CATEGORY = "Escolha uma categoria.";
    private static final String PICK_COSMETIC = "Toque em um cosmético para equipar ou remover. "
            + "Os que você não possui podem ser experimentados no manequim.";
    private static final String NOTHING_AVAILABLE = "Nenhum cosmético aqui ainda.";
    private static final String REMOVE = "§cRemover";
    private static final String BACK = "Voltar";
    private static final String LEAVE = "§cSair do provador";

    /** What an icon name may be to go into a URL as is, which every name Scaffolding writes already is. */
    private static final Pattern URL_SAFE_ICON = Pattern.compile("[A-Za-z0-9_.-]{1,128}");

    /** The state note after the name, in the colours the Java menu tints the slot with. */
    private static final String EQUIPPED_NOTE = "  §aEquipado";
    private static final String OWNED_NOTE = "  §ePossui";
    private static final String LOCKED_NOTE = "  §cNão possui";
    private static final String PREVIEWING_NOTE = "  §cExperimentando";

    /**
     * The section code each rarity is drawn with, written out rather than downsampled from
     * {@link Rarity#color()}.
     * <p>
     * Two reasons, and the second is the one that matters: Bedrock draws section codes and nothing
     * else, and the nearest code to a rarity is often one of the dark ones, which on a Bedrock
     * button is all but unreadable. These are the bright neighbours of the same hue, except common,
     * which goes the other way: a Bedrock button is light grey, so the dark grey is what stands out
     * on it and the mid grey is the one that disappears.
     * </p>
     */
    private static final Map<Rarity, String> RARITY_CODES = Map.of(
            Rarity.COMMON, "§8",
            Rarity.UNCOMMON, "§a",
            Rarity.RARE, "§9",
            Rarity.EPIC, "§d",
            Rarity.LEGENDARY, "§6");

    /**
     * The order the categories are listed in. A slot registered by another plugin is not in here and
     * lands after these, which is the only reason this is a lookup rather than the listing itself.
     */
    private static final List<CosmeticSlot> SLOT_ORDER = List.of(
            CosmeticSlot.HELMET, CosmeticSlot.BACKPACK, CosmeticSlot.MAINHAND, CosmeticSlot.OFFHAND,
            CosmeticSlot.BALLOON, CosmeticSlot.AURA, CosmeticSlot.CHESTPLATE, CosmeticSlot.LEGGINGS,
            CosmeticSlot.BOOTS);

    private BedrockWardrobeForm() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /** Opens the category list, each one showing what is worn in it right now. */
    public static void open(@NotNull CosmeticUser user) {
        final Player player = user.getPlayer();
        if (player == null) return;

        withSkin(player, skin -> openCategories(user, skin));
    }

    private static void openCategories(@NotNull CosmeticUser user, @Nullable HookCommonsSkins.Skin skin) {
        final List<CosmeticSlot> slots = slotsWithCosmetics(user);
        final List<Runnable> actions = new ArrayList<>();
        final SimpleForm.Builder form = SimpleForm.builder()
                .title(TITLE)
                .content(slots.isEmpty() ? NOTHING_AVAILABLE : PICK_CATEGORY);

        for (final CosmeticSlot slot : slots) {
            final Cosmetic equipped = user.getCosmetic(slot);
            // The category wears the icon of what is equipped in it, which is the quickest way to
            // see the whole outfit without opening anything.
            button(form, categoryLabel(slot, equipped), equipped, colorOf(user, equipped), skin);
            actions.add(() -> openSlot(user, slot));
        }

        // Sneaking leaves too, but a form button is the control a phone player is sure to find.
        form.button(LEAVE);
        actions.add(() -> user.leaveWardrobe(false));

        send(user, form, actions);
    }

    /**
     * Opens one category, with the way back and the way out first: everything under them is a list
     * that can run for pages, and a control at the bottom of that is a control nobody finds.
     */
    private static void openSlot(@NotNull CosmeticUser user, @NotNull CosmeticSlot slot) {
        final Player player = user.getPlayer();
        if (player == null) return;

        withSkin(player, skin -> openSlot(user, player, slot, skin));
    }

    private static void openSlot(@NotNull CosmeticUser user, @NotNull Player player, @NotNull CosmeticSlot slot,
                                 @Nullable HookCommonsSkins.Skin skin) {
        final List<Cosmetic> cosmetics = cosmeticsFor(user, slot);
        final Cosmetic equipped = user.getCosmetic(slot);
        final List<Runnable> actions = new ArrayList<>();
        final SimpleForm.Builder form = SimpleForm.builder()
                .title(slotName(slot))
                .content(cosmetics.isEmpty() ? NOTHING_AVAILABLE : PICK_COSMETIC);

        form.button(BACK);
        actions.add(() -> open(user));

        if (equipped != null) {
            form.button(REMOVE);
            actions.add(() -> {
                Cosmetic worn = user.getCosmetic(slot);
                if (worn != null && user.isPreviewing(worn)) user.endPreview(slot);
                else user.removeCosmeticSlot(slot);
                open(user);
            });
        }

        for (final Cosmetic cosmetic : cosmetics) {
            button(form, cosmeticLabel(user, player, cosmetic), cosmetic, colorOf(user, cosmetic), skin);
            actions.add(() -> {
                // Read the slot again rather than trusting the capture: the form has been on screen
                // for a while, and a reward or a lost permission may have changed it meanwhile.
                if (!user.canEquipCosmetic(cosmetic)) {
                    // Not theirs: it goes on the mannequin as a preview, and the list stays open so
                    // the next one is a tap away.
                    if (user.canPreview()) user.togglePreview(cosmetic);
                    openSlot(user, slot);
                    return;
                } else if (cosmetic.equals(user.getCosmetic(slot))) {
                    user.removeCosmeticSlot(cosmetic);
                } else if (cosmetic.isDyeable() && BedrockDyeForm.available()) {
                    // As on Java, a dyeable cosmetic is put on through the colour it is put on in.
                    BedrockDyeForm.open(user, cosmetic, () -> openSlot(user, slot), () -> open(user));
                    return;
                } else {
                    user.addCosmetic(cosmetic);
                }
                // Back to the categories rather than to this list: picking one is usually the end of
                // what the player came to do, and the mannequin is a tap away from there.
                open(user);
            });
        }

        send(user, form, actions);
    }

    /** The category name, plus what is worn in it. */
    @NotNull
    private static String categoryLabel(@NotNull CosmeticSlot slot, @Nullable Cosmetic equipped) {
        return equipped == null ? slotName(slot) : slotName(slot) + '\n' + coloured(equipped);
    }

    /**
     * The name and its state (equipped, owned, not owned or being tried on), with the badges under
     * it: the same glyphs the chest menu draws on the item.
     */
    @NotNull
    private static String cosmeticLabel(@NotNull CosmeticUser user, @NotNull Player player, @NotNull Cosmetic cosmetic) {
        StringBuilder label = new StringBuilder(coloured(cosmetic));
        if (user.isPreviewing(cosmetic)) label.append(PREVIEWING_NOTE);
        else if (cosmetic.equals(user.getCosmetic(cosmetic.getSlot()))) label.append(EQUIPPED_NOTE);
        else if (user.canEquipCosmetic(cosmetic, true)) label.append(OWNED_NOTE);
        else label.append(LOCKED_NOTE);

        String badges = cosmetic.buildBadgeLine();
        if (badges != null) label.append('\n').append(BedrockText.fromMiniMessage(player, badges));

        return label.toString();
    }

    /**
     * Runs {@code then} with the skin this player shows, or null without one. Right away when it is
     * already known, which for a player wearing it is always; otherwise on the main thread once it
     * resolves. Nothing is asked when there is no API to draw it with.
     */
    private static void withSkin(@NotNull Player player, @NotNull Consumer<HookCommonsSkins.Skin> then) {
        if (WardrobeSettings.getBedrockApiUrl().isEmpty()) {
            then.accept(null);
            return;
        }

        final CompletableFuture<Optional<HookCommonsSkins.Skin>> skin = HookCommonsSkins.skin(player);
        if (skin.isDone()) {
            then.accept(skin.join().orElse(null));
            return;
        }
        skin.thenAccept(found -> Bukkit.getScheduler().runTask(HMCCosmeticsPlugin.getInstance(),
                () -> then.accept(found.orElse(null))));
    }

    /**
     * Adds a button showing the cosmetic's icon, or a plain one when the pack has no icon for it
     * (Scaffolding missing, or a cosmetic added since it last generated).
     * <p>
     * The client shows a form image exactly as it gets it, so two kinds come from the API instead:
     * an aura is the viewer's {@code skin} outlined in its colour, and a dyeable one with a colour is
     * its icon painted in it. Without the API URL (or a skin, for an aura) the pack's icon stays.
     * </p>
     */
    static void button(@NotNull SimpleForm.Builder form, @NotNull String label, @Nullable Cosmetic cosmetic,
                       @Nullable Color color, @Nullable HookCommonsSkins.Skin skin) {
        final String apiUrl = WardrobeSettings.getBedrockApiUrl();
        if (cosmetic instanceof CosmeticAuraType aura && skin != null && !apiUrl.isEmpty()) {
            final String model = skin.slim() ? "slim" : "classic";
            final int rgb = aura.getColor().asBungee().getColor().getRGB();
            form.button(label, FormImage.Type.URL,
                    apiUrl + "/auras/" + skin.texture() + "/" + model + "/" + String.format("%06x", rgb & 0xFFFFFF) + ".png");
            return;
        }

        final BedrockIcons.Icon icon = cosmetic == null ? null : BedrockIcons.icon(cosmetic.getMaterial(), cosmetic.getItem());
        if (icon == null) {
            form.button(label);
            return;
        }

        if (color != null && !apiUrl.isEmpty() && URL_SAFE_ICON.matcher(icon.name()).matches()) {
            form.button(label, FormImage.Type.URL, apiUrl + "/icons/" + icon.name() + "/" + hex(color) + ".png");
        } else {
            form.button(label, FormImage.Type.PATH, icon.path());
        }
    }

    /**
     * The colour a dyeable cosmetic shows in: the one this player dyed it, when it is the one they
     * wear, otherwise the colour its item comes in. Null for a cosmetic that is not dyeable.
     */
    @Nullable
    private static Color colorOf(@NotNull CosmeticUser user, @Nullable Cosmetic cosmetic) {
        if (cosmetic == null || !cosmetic.isDyeable()) return null;

        if (cosmetic.equals(user.getCosmetic(cosmetic.getSlot()))) {
            final Color dyed = user.getCosmeticColor(cosmetic.getSlot());
            if (dyed != null) return dyed;
        }

        final ItemStack item = cosmetic.getItem();
        return item == null || !item.hasItemMeta() ? null : NMSHandlers.getHandler().getUtilHandler().getColor(item);
    }

    @NotNull
    private static String hex(@NotNull Color color) {
        return String.format("%06x", color.asRGB());
    }

    /** The cosmetic's name, in the section code its rarity is drawn with. */
    @NotNull
    private static String coloured(@NotNull Cosmetic cosmetic) {
        String code = cosmetic.getRarity() == null ? "" : RARITY_CODES.getOrDefault(cosmetic.getRarity(), "");
        return code + cosmetic.getPlainName();
    }

    /**
     * Hands the form to Floodgate, with the button the player picks resolved against {@code actions}.
     * <p>
     * Closing the form is deliberately left alone: the player is still in the wardrobe, looking at
     * the mannequin, and punching or jumping brings this back.
     * </p>
     */
    static void send(@NotNull CosmeticUser user, @NotNull SimpleForm.Builder form, @NotNull List<Runnable> actions) {
        final Player player = user.getPlayer();
        if (player == null) return;

        form.validResultHandler(response -> {
            final int clicked = response.clickedButtonId();
            // Cumulus answers on a Floodgate thread, and everything an action touches is the world.
            Bukkit.getScheduler().runTask(HMCCosmeticsPlugin.getInstance(), () -> {
                if (clicked < 0 || clicked >= actions.size()) return;
                if (!user.isInWardrobe()) return;
                actions.get(clicked).run();
            });
        });

        if (!FloodgateApi.getInstance().sendForm(player.getUniqueId(), form)) {
            MessagesUtil.sendDebugMessages("Floodgate would not deliver the wardrobe form to " + player.getName());
        }
    }

    @NotNull
    private static List<CosmeticSlot> slotsWithCosmetics(@NotNull CosmeticUser user) {
        return CosmeticSlot.values().values().stream()
                .filter(slot -> !cosmeticsFor(user, slot).isEmpty())
                .sorted(Comparator.comparingInt(BedrockWardrobeForm::slotOrder))
                .toList();
    }

    /**
     * The cosmetics of one category: the ones the player owns, then the ones the Java menus offer that
     * they don't, which they can try on. A cosmetic no menu lists and the player doesn't own (an
     * internal one, like the moderation outfit) stays out.
     */
    @NotNull
    private static List<Cosmetic> cosmeticsFor(@NotNull CosmeticUser user, @NotNull CosmeticSlot slot) {
        final Set<Cosmetic> offered = new HashSet<>();
        for (final Menu menu : Menus.getMenu()) offered.addAll(menu.listedCosmetics());

        return Cosmetics.values().stream()
                .filter(cosmetic -> slot.equals(cosmetic.getSlot()))
                .filter(Cosmetic::isEnabled)
                .filter(cosmetic -> user.canEquipCosmetic(cosmetic, true) || offered.contains(cosmetic))
                .sorted(Comparator.comparing((Cosmetic cosmetic) -> !user.canEquipCosmetic(cosmetic, true))
                        .thenComparing(Cosmetic::getPlainName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static int slotOrder(@NotNull CosmeticSlot slot) {
        final int index = SLOT_ORDER.indexOf(slot);
        return index < 0 ? SLOT_ORDER.size() : index;
    }

    @NotNull
    private static String slotName(@NotNull CosmeticSlot slot) {
        return switch (slot.getName()) {
            case "HELMET" -> "Chapéus";
            case "CHESTPLATE" -> "Peitorais";
            case "LEGGINGS" -> "Calças";
            case "BOOTS" -> "Botas";
            case "MAINHAND" -> "Mão principal";
            case "OFFHAND" -> "Mão secundária";
            case "BACKPACK" -> "Mochilas";
            case "BALLOON" -> "Balões";
            case "AURA" -> "Auras";
            default -> slot.getName();
        };
    }
}
