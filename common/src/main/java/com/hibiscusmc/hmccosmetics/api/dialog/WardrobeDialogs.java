package com.hibiscusmc.hmccosmetics.api.dialog;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What other plugins plug into the wardrobe dialog: who sells cosmetics, and who else gives them.
 * <p>
 * Nothing here names a class of the dialog itself, so a plugin registers without caring whether
 * the dialog can run on this server.
 * </p>
 */
public final class WardrobeDialogs {

    private static volatile @Nullable CosmeticMarket market;
    private static final List<CosmeticOriginProvider> ORIGINS = new CopyOnWriteArrayList<>();

    private WardrobeDialogs() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /** Sets the market, or clears it with null when the selling plugin disables. */
    public static void market(@Nullable CosmeticMarket market) {
        WardrobeDialogs.market = market;
    }

    public static @Nullable CosmeticMarket market() {
        return market;
    }

    /** Adds a source of origins. The first registered that answers for a cosmetic is the one shown. */
    public static void origins(@NotNull CosmeticOriginProvider provider) {
        ORIGINS.add(provider);
    }

    public static void removeOrigins(@NotNull CosmeticOriginProvider provider) {
        ORIGINS.remove(provider);
    }

    public static @NotNull List<CosmeticOriginProvider> origins() {
        return ORIGINS;
    }
}
