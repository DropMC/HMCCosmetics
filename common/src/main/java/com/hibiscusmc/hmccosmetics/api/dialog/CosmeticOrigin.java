package com.hibiscusmc.hmccosmetics.api.dialog;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/**
 * Where a cosmetic that is not for sale comes from.
 *
 * @param label   the origin in a couple of words, short enough for a card
 * @param details what the cosmetic's page adds, at most two lines
 * @param button  the label of the page's button, used when {@code url} or {@code action} is set
 * @param url     an address the button opens in the player's browser
 * @param action  what the button does otherwise; the dialog is closed before it runs
 */
public record CosmeticOrigin(@NotNull String label, @NotNull List<String> details, @NotNull String button,
                             @Nullable String url, @Nullable Consumer<Player> action) {

    /** An origin that is only a name. */
    public static @NotNull CosmeticOrigin named(@NotNull String label) {
        return new CosmeticOrigin(label, List.of(), "", null, null);
    }

    public boolean hasAction() {
        return !button.isBlank() && (url != null || action != null);
    }
}
