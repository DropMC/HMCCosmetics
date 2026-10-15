package com.hibiscusmc.hmccosmetics.api.dialog;

import com.hibiscusmc.hmccosmetics.cosmetic.Cosmetic;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.OptionalInt;

/**
 * Whoever sells cosmetics on this server. The wardrobe dialog knows nothing about currencies or
 * orders: it asks the market what a cosmetic costs and hands it the purchase.
 * <p>
 * Registered through {@link WardrobeDialogs#market(CosmeticMarket)}. Without one the dialog still
 * works, and nothing in it is for sale.
 * </p>
 */
public interface CosmeticMarket {

    /**
     * What {@code cosmetic} costs, or empty when it is not for sale. A cosmetic sold only with others
     * (see {@link #offer(Cosmetic)}) costs what the whole offer does.
     */
    @NotNull OptionalInt price(@NotNull Cosmetic cosmetic);

    /**
     * The purchase that sells {@code cosmetic}. Cosmetics that share one are only sold together, for
     * the price of one of them, and buying any of them buys them all. By default each cosmetic is
     * its own purchase.
     */
    default @NotNull String offer(@NotNull Cosmetic cosmetic) {
        return cosmetic.getId();
    }

    /** How much of the currency {@code player} has. */
    int balance(@NotNull Player player);

    /**
     * Charges {@code player} for {@code cosmetics} and grants them, all or nothing.
     *
     * @return whether the purchase went through; false leaves the player as they were
     */
    boolean buy(@NotNull Player player, @NotNull List<Cosmetic> cosmetics);

    /** Takes {@code player} to wherever more of the currency is bought. The dialog is already closed. */
    void openTopUp(@NotNull Player player);
}
