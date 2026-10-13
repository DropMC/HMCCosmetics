package com.hibiscusmc.hmccosmetics.api.dialog;

import com.hibiscusmc.hmccosmetics.cosmetic.Cosmetic;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A plugin that hands out cosmetics some other way than selling them, such as a battle pass, and
 * can say so for the ones it hands out.
 */
@FunctionalInterface
public interface CosmeticOriginProvider {

    /** Where {@code player} gets {@code cosmetic} from this plugin, or null when it does not give it. */
    @Nullable CosmeticOrigin origin(@NotNull Player player, @NotNull Cosmetic cosmetic);
}
