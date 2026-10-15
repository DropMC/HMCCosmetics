package com.hibiscusmc.hmccosmetics.gui.dialog;

import com.hibiscusmc.hmccosmetics.api.dialog.CosmeticMarket;
import com.hibiscusmc.hmccosmetics.api.dialog.CosmeticOrigin;
import com.hibiscusmc.hmccosmetics.api.dialog.CosmeticOriginProvider;
import com.hibiscusmc.hmccosmetics.api.dialog.WardrobeDialogs;
import com.hibiscusmc.hmccosmetics.cosmetic.Cosmetic;
import com.hibiscusmc.hmccosmetics.cosmetic.CosmeticSlot;
import com.hibiscusmc.hmccosmetics.cosmetic.Cosmetics;
import com.hibiscusmc.hmccosmetics.cosmetic.Rarity;
import com.hibiscusmc.hmccosmetics.cosmetic.types.CosmeticAuraType;
import com.hibiscusmc.hmccosmetics.user.CosmeticUser;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What one player sees of the cosmetics: what they own and wear, what each piece costs and which
 * set it belongs to. Built fresh for every screen, so nothing here outlives a click.
 */
final class DialogCatalog {

    /** The slots the dialog has a tab for, in the tabs' order. */
    static final List<CosmeticSlot> SLOTS =
            List.of(CosmeticSlot.HELMET, CosmeticSlot.BACKPACK, CosmeticSlot.OFFHAND, CosmeticSlot.AURA);

    /**
     * Cosmetics a player has just paid for. A market grants the permission through the permissions
     * plugin, which lands a moment after the purchase returns, and the screen drawn in between must
     * already show the piece as owned.
     */
    private static final Map<UUID, Set<String>> JUST_BOUGHT = new ConcurrentHashMap<>();

    enum State { EQUIPPED, OWNED, FOR_SALE, UNAVAILABLE }

    record CosmeticSet(@NotNull String id, @NotNull String name, @NotNull List<Cosmetic> pieces) {

        boolean isLimited() {
            return pieces.stream().anyMatch(Cosmetic::isLimited);
        }

        boolean isDyeable() {
            return pieces.stream().anyMatch(Cosmetic::isDyeable);
        }

        /** The rarest piece's rarity, which is the one the set is shown under. */
        @Nullable Rarity rarity() {
            return pieces.stream().map(Cosmetic::getRarity).filter(Objects::nonNull).max(Rarity::compareTo).orElse(null);
        }
    }

    private final Player player;
    private final CosmeticUser user;
    private final DialogConfig config = DialogConfig.get();
    private final @Nullable CosmeticMarket market = WardrobeDialogs.market();
    private final Map<String, CosmeticSet> setByPiece = new HashMap<>();
    private final List<CosmeticSet> sets = new ArrayList<>();

    DialogCatalog(@NotNull Player player, @NotNull CosmeticUser user) {
        this.player = player;
        this.user = user;
        for (DialogConfig.SetEntry entry : config.sets()) {
            List<Cosmetic> pieces = entry.pieces().stream()
                    .map(Cosmetics::getCosmetic)
                    .filter(Objects::nonNull)
                    .filter(this::isListed)
                    .toList();
            if (pieces.isEmpty()) continue;
            CosmeticSet set = new CosmeticSet(entry.id(), entry.name(), pieces);
            sets.add(set);
            pieces.forEach(piece -> setByPiece.put(piece.getId(), set));
        }
    }

    static void markBought(@NotNull UUID uuid, @NotNull List<Cosmetic> cosmetics) {
        Set<String> bought = JUST_BOUGHT.computeIfAbsent(uuid, ignored -> ConcurrentHashMap.newKeySet());
        cosmetics.forEach(cosmetic -> bought.add(cosmetic.getId()));
    }

    static void forget(@NotNull UUID uuid) {
        JUST_BOUGHT.remove(uuid);
    }

    @NotNull Player player() {
        return player;
    }

    @NotNull CosmeticUser user() {
        return user;
    }

    @Nullable CosmeticMarket market() {
        return market;
    }

    @NotNull DialogConfig config() {
        return config;
    }

    int balance() {
        return market == null ? 0 : market.balance(player);
    }

    @NotNull State state(@NotNull Cosmetic cosmetic) {
        if (isEquipped(cosmetic)) return State.EQUIPPED;
        if (owns(cosmetic)) return State.OWNED;
        return priceOf(cosmetic).isPresent() ? State.FOR_SALE : State.UNAVAILABLE;
    }

    boolean owns(@NotNull Cosmetic cosmetic) {
        if (!cosmetic.requiresPermission() || player.hasPermission(cosmetic.getPermission())) return true;
        Set<String> bought = JUST_BOUGHT.get(player.getUniqueId());
        return bought != null && bought.contains(cosmetic.getId());
    }

    boolean isEquipped(@NotNull Cosmetic cosmetic) {
        Cosmetic worn = user.getCosmetic(cosmetic.getSlot());
        return worn != null && worn.getId().equals(cosmetic.getId());
    }

    @Nullable Cosmetic worn(@NotNull CosmeticSlot slot) {
        return user.getCosmetic(slot);
    }

    private OptionalInt priceOf(Cosmetic cosmetic) {
        return market == null || !cosmetic.requiresPermission() ? OptionalInt.empty() : market.price(cosmetic);
    }

    int price(@NotNull Cosmetic cosmetic) {
        return priceOf(cosmetic).orElse(0);
    }

    /** What buying {@code cosmetics} costs: pieces sold together are one purchase, paid once. */
    int price(@NotNull List<Cosmetic> cosmetics) {
        Map<String, Integer> priceByOffer = new HashMap<>();
        for (Cosmetic cosmetic : cosmetics) priceByOffer.putIfAbsent(offer(cosmetic), price(cosmetic));
        return priceByOffer.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Whether {@code cosmetic} is only sold together with other cosmetics, as one purchase. */
    boolean isSoldTogether(@NotNull Cosmetic cosmetic) {
        return soldWith(cosmetic).size() > 1;
    }

    /**
     * The pieces of {@code cosmetics} the player lacks and can buy right now, with the pieces sold
     * together with them: buying one of those buys them all.
     */
    @NotNull List<Cosmetic> buyable(@NotNull List<Cosmetic> cosmetics) {
        List<Cosmetic> buyable = new ArrayList<>();
        for (Cosmetic cosmetic : cosmetics) {
            if (state(cosmetic) != State.FOR_SALE || buyable.contains(cosmetic)) continue;
            buyable.add(cosmetic);
            for (Cosmetic piece : soldWith(cosmetic)) {
                if (!buyable.contains(piece) && state(piece) == State.FOR_SALE) buyable.add(piece);
            }
        }
        return buyable;
    }

    private String offer(Cosmetic cosmetic) {
        return market == null ? cosmetic.getId() : market.offer(cosmetic);
    }

    /** The listed cosmetics sold in the same purchase as {@code cosmetic}, itself included. */
    private List<Cosmetic> soldWith(Cosmetic cosmetic) {
        if (market == null || !cosmetic.requiresPermission()) return List.of(cosmetic);
        String offer = market.offer(cosmetic);
        return Cosmetics.values().stream()
                .filter(this::isListed)
                .filter(other -> other.requiresPermission() && market.offer(other).equals(offer))
                .toList();
    }

    /**
     * Where a cosmetic that is not for sale comes from. The plugins that hand cosmetics out speak
     * first, because they know: a line in {@code sources} about the same cosmetic is only somebody's
     * memory of it.
     */
    @NotNull CosmeticOrigin origin(@NotNull Cosmetic cosmetic) {
        for (CosmeticOriginProvider provider : WardrobeDialogs.origins()) {
            CosmeticOrigin origin = provider.origin(player, cosmetic);
            if (origin != null) return origin;
        }
        String label = config.sources().get(cosmetic.getId());
        if (label == null) return CosmeticOrigin.named(config.unavailable());
        DialogConfig.SourceDetail detail = config.sourceDetails().get(label);
        if (detail == null) return CosmeticOrigin.named(label);
        String command = detail.command();
        return new CosmeticOrigin(label, detail.description(), detail.button(),
                detail.url().isBlank() ? null : detail.url(),
                command.isBlank() ? null : clicker -> clicker.performCommand(command));
    }

    @Nullable CosmeticSet setOf(@NotNull Cosmetic cosmetic) {
        return setByPiece.get(cosmetic.getId());
    }

    @Nullable CosmeticSet set(@NotNull String id) {
        return sets.stream().filter(set -> set.id().equals(id)).findFirst().orElse(null);
    }

    @NotNull List<CosmeticSet> sets() {
        return sets;
    }

    @NotNull List<Cosmetic> cosmetics(@NotNull CosmeticSlot slot) {
        List<Cosmetic> cosmetics = new ArrayList<>();
        for (Cosmetic cosmetic : Cosmetics.values()) {
            if (cosmetic.getSlot().equals(slot) && isListed(cosmetic)) cosmetics.add(cosmetic);
        }
        return cosmetics;
    }

    /** The cosmetics of {@code ids} the dialog lists, in that order. */
    @NotNull List<Cosmetic> listed(@NotNull List<String> ids) {
        return ids.stream().map(Cosmetics::getCosmetic).filter(Objects::nonNull).filter(this::isListed).toList();
    }

    /** The colour the player wears this cosmetic in, or the one its configuration gives it. */
    @NotNull TextColor dye(@NotNull Cosmetic cosmetic) {
        if (isEquipped(cosmetic)) {
            Color worn = user.getCosmeticColor(cosmetic.getSlot());
            if (worn != null) return TextColor.color(worn.asRGB());
        }
        if (cosmetic instanceof CosmeticAuraType aura) {
            TextColor named = NamedTextColor.NAMES.value(aura.getColor().name().toLowerCase(Locale.ROOT));
            return named == null ? NamedTextColor.WHITE : named;
        }
        ItemStack item = cosmetic.getItem();
        if (item != null && item.getItemMeta() instanceof LeatherArmorMeta leather) {
            return TextColor.color(leather.getColor().asRGB());
        }
        return NamedTextColor.WHITE;
    }

    private boolean isListed(Cosmetic cosmetic) {
        return cosmetic.isEnabled() && !config.hidden().contains(cosmetic.getId()) && SLOTS.contains(cosmetic.getSlot());
    }
}
