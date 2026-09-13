package com.hibiscusmc.hmccosmetics.gui.bedrock;

import com.hibiscusmc.hmccosmetics.HMCCosmeticsPlugin;
import com.hibiscusmc.hmccosmetics.cosmetic.Cosmetic;
import com.hibiscusmc.hmccosmetics.cosmetic.CosmeticSlot;
import com.hibiscusmc.hmccosmetics.cosmetic.Cosmetics;
import com.hibiscusmc.hmccosmetics.cosmetic.Rarity;
import com.hibiscusmc.hmccosmetics.user.CosmeticUser;
import com.hibiscusmc.hmccosmetics.util.BedrockIcons;
import com.hibiscusmc.hmccosmetics.util.BedrockText;
import com.hibiscusmc.hmccosmetics.util.MessagesUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.util.FormImage;
import org.geysermc.floodgate.api.FloodgateApi;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The wardrobe menu as a Bedrock form, for players whose camera is pinned to the mannequin.
 * <p>
 * A Bedrock client will not open a container while a camera instruction is in effect, so the chest
 * menu everyone else gets does not reliably appear there however often the server opens it. A form
 * does, and a pinned camera still lets the player touch one, which makes it the way in on Bedrock.
 * </p>
 * Each button carries the cosmetic's own icon out of the converted pack ({@link BedrockIcons}), the
 * rarity and badges as words rather than as the glyphs the chest menu draws them with, and the
 * description. What Bedrock cannot show at all is said on the button rather than left to surprise
 * the player: an aura has no effect there, and a dye keeps none of its colour.
 */
public final class BedrockWardrobeForm {

    private static final String TITLE = "Cosméticos";
    private static final String PICK_CATEGORY = "Escolha uma categoria.";
    private static final String PICK_COSMETIC = "Toque em um cosmético para equipar ou remover.";
    private static final String NOTHING_AVAILABLE = "Você ainda não tem cosméticos aqui.";
    private static final String REMOVE = "§cRemover";
    private static final String BACK = "Voltar";
    private static final String LEAVE = "§cSair do provador";

    /** Said on the button and again inside, because it is the whole reason the category looks broken. */
    private static final String AURA_WARNING_KEY = "wardrobe-bedrock-aura";
    private static final String AURA_WARNING_FALLBACK = "§eNão aparecem no Bedrock.";
    private static final String DYE_WARNING_KEY = "wardrobe-bedrock-dye";
    private static final String DYE_WARNING_FALLBACK = "§eCosméticos tingíveis ficam brancos no Bedrock.";

    private static final String EQUIPPED_NOTE = "  §aEquipado";

    /**
     * The section code each rarity is drawn with, written out rather than downsampled from
     * {@link Rarity#color()}.
     * <p>
     * Two reasons, and the second is the one that matters: Bedrock draws section codes and nothing
     * else, and the nearest code to a rarity is often one of the dark ones, which on a Bedrock
     * button is all but unreadable. These are the bright neighbours of the same hue.
     * </p>
     */
    private static final Map<Rarity, String> RARITY_CODES = Map.of(
            Rarity.COMMON, "§7",
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

        final List<CosmeticSlot> slots = slotsWithCosmetics(user);
        final List<Runnable> actions = new ArrayList<>();
        final SimpleForm.Builder form = SimpleForm.builder()
                .title(TITLE)
                .content(slots.isEmpty() ? NOTHING_AVAILABLE : PICK_CATEGORY);

        for (final CosmeticSlot slot : slots) {
            final Cosmetic equipped = user.getCosmetic(slot);
            // The category wears the icon of what is equipped in it, which is the quickest way to
            // see the whole outfit without opening anything.
            button(form, categoryLabel(player, slot, equipped), equipped);
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

        final List<Cosmetic> cosmetics = cosmeticsFor(user, slot);
        final Cosmetic equipped = user.getCosmetic(slot);
        final List<Runnable> actions = new ArrayList<>();
        final SimpleForm.Builder form = SimpleForm.builder()
                .title(slotName(slot))
                .content(slotContent(player, slot, cosmetics));

        form.button(BACK);
        actions.add(() -> open(user));

        if (equipped != null) {
            form.button(REMOVE);
            actions.add(() -> {
                user.removeCosmeticSlot(slot);
                open(user);
            });
        }

        for (final Cosmetic cosmetic : cosmetics) {
            button(form, cosmeticLabel(player, cosmetic, cosmetic.equals(equipped)), cosmetic);
            actions.add(() -> {
                // Read the slot again rather than trusting the capture: the form has been on screen
                // for a while, and a reward or a lost permission may have changed it meanwhile.
                if (cosmetic.equals(user.getCosmetic(slot))) user.removeCosmeticSlot(cosmetic);
                else user.addCosmetic(cosmetic);
                // Back to the categories rather than to this list: picking one is usually the end of
                // what the player came to do, and the mannequin is a tap away from there.
                open(user);
            });
        }

        send(user, form, actions);
    }

    /** The category name, plus what is worn in it and anything Bedrock will not show about it. */
    @NotNull
    private static String categoryLabel(@NotNull Player player, @NotNull CosmeticSlot slot, @Nullable Cosmetic equipped) {
        StringBuilder label = new StringBuilder(slotName(slot));
        if (equipped != null) label.append('\n').append(coloured(equipped));
        if (CosmeticSlot.AURA.equals(slot)) label.append('\n').append(auraWarning(player));
        return label.toString();
    }

    /**
     * The name, with the badges under it: the same glyphs the chest menu draws on the item, out of
     * the cosmetic's own definition, minus the dyeable one. Nothing is dyeable on Bedrock.
     */
    @NotNull
    private static String cosmeticLabel(@NotNull Player player, @NotNull Cosmetic cosmetic, boolean equipped) {
        StringBuilder label = new StringBuilder(coloured(cosmetic));
        if (equipped) label.append(EQUIPPED_NOTE);

        String badges = cosmetic.buildBadgeLine(false);
        if (badges != null) label.append('\n').append(BedrockText.fromMiniMessage(player, badges));

        return label.toString();
    }

    /** The form's header, carrying whatever warning this category needs. */
    @NotNull
    private static String slotContent(@NotNull Player player, @NotNull CosmeticSlot slot, @NotNull List<Cosmetic> cosmetics) {
        if (cosmetics.isEmpty()) return NOTHING_AVAILABLE;
        if (CosmeticSlot.AURA.equals(slot)) return auraWarning(player);
        if (cosmetics.stream().anyMatch(Cosmetic::isDyeable)) {
            return PICK_COSMETIC + "\n" + BedrockText.fromKey(player, DYE_WARNING_KEY, DYE_WARNING_FALLBACK);
        }
        return PICK_COSMETIC;
    }

    @NotNull
    private static String auraWarning(@NotNull Player player) {
        return BedrockText.fromKey(player, AURA_WARNING_KEY, AURA_WARNING_FALLBACK);
    }

    /**
     * Adds a button showing the cosmetic's icon, or a plain one when the pack has no icon for it
     * (Scaffolding missing, or a cosmetic added since it last generated).
     */
    private static void button(@NotNull SimpleForm.Builder form, @NotNull String label, @Nullable Cosmetic cosmetic) {
        final String texture = cosmetic == null
                ? null
                : BedrockIcons.texturePath(cosmetic.getMaterial(), cosmetic.getItem());
        if (texture == null) form.button(label);
        else form.button(label, FormImage.Type.PATH, texture);
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
    private static void send(@NotNull CosmeticUser user, @NotNull SimpleForm.Builder form, @NotNull List<Runnable> actions) {
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

    @NotNull
    private static List<Cosmetic> cosmeticsFor(@NotNull CosmeticUser user, @NotNull CosmeticSlot slot) {
        return Cosmetics.values().stream()
                .filter(cosmetic -> slot.equals(cosmetic.getSlot()))
                .filter(user::canEquipCosmetic)
                .sorted(Comparator.comparing(Cosmetic::getPlainName, String.CASE_INSENSITIVE_ORDER))
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
