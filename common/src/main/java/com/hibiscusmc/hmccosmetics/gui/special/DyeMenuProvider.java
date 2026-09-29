package com.hibiscusmc.hmccosmetics.gui.special;

import com.hibiscusmc.hmccosmetics.config.Settings;
import com.hibiscusmc.hmccosmetics.cosmetic.Cosmetic;
import com.hibiscusmc.hmccosmetics.cosmetic.CosmeticHolder;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class DyeMenuProvider {

    private static DyeMenu instance = null;

    /**
     * Sets the provider of the DyeMenu. Check {@link DyeMenuProvider#hasMenuProvider()} before attempting to set this,
     * it is locked once a dye menu has been set!
     * @param dyeMenu The dye menu implementation that wishes to be the provider.
     * @throws IllegalStateException IllegalStateException will be thrown if a dye menu provider already is set by another plugin.
     */
    public static void setDyeMenuProvider(@NotNull DyeMenu dyeMenu) throws IllegalStateException {
        if (instance != null) {
            throw new IllegalStateException("DyeMenu Implementation has already been set by another plugin.");
        }
        instance = dyeMenu;
    }

    /**
     * Called when wishing to dye a cosmetic to hand it off to the dye menu implementation
     * @param viewer The viewer of the menu
     * @param cosmeticHolder The cosmetic holder that the player viewer wishing to modify (could be themselves or another CosmeticHolder)
     * @param cosmetic The cosmetic the user wishes to dye
     * @throws IllegalStateException IllegalStateException will be thrown if the dye menu instance is null (Check {@link DyeMenuProvider#hasMenuProvider()} before calling)
     */
    public static void openMenu(@NotNull Player viewer, @NotNull CosmeticHolder cosmeticHolder, @NotNull Cosmetic cosmetic) throws IllegalStateException {
        if (instance == null) {
            throw new IllegalStateException("Unable to open a dye menu without instance of it.");
        }
        instance.openMenu(viewer, cosmeticHolder, cosmetic);
    }

    /**
     * The dye menu implementation in charge, or null before one is set.
     * @return The current dye menu provider.
     */
    public static @Nullable DyeMenu menuProvider() {
        return instance;
    }

    /**
     * Does HMCC have a dye menu provider already set?
     * @return True if it is already set and locked; false if none have been set.
     */
    public static boolean hasMenuProvider() {
        return instance != null;
    }

    public static boolean canOpenDyeMenu() {
        return hasMenuProvider() && Settings.isDyeMenuEnabled();
    }

    /**
     * Runs the reload logic for the dye menu implementations.
     */
    public static void reload() {
        if (instance != null) instance.reload();
    }
}
