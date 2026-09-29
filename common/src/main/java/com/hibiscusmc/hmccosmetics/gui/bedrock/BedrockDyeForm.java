package com.hibiscusmc.hmccosmetics.gui.bedrock;

import com.hibiscusmc.hmccosmetics.cosmetic.Cosmetic;
import com.hibiscusmc.hmccosmetics.gui.special.DyeMenuProvider;
import com.hibiscusmc.hmccosmetics.gui.special.impl.InternalDyeMenu;
import com.hibiscusmc.hmccosmetics.user.CosmeticUser;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Color;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.SimpleForm;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * The dye menu as Bedrock forms: the internal dye menu's colours as a list of buttons, each showing
 * the cosmetic's own icon painted in that colour, then the shades of the one picked.
 * <p>
 * The chest dye menu is not an option inside the wardrobe for the same reason the wardrobe menu is
 * not: a Bedrock client will not open a container while its camera is pinned. A colour's name alone
 * says little on Bedrock, whose text cannot show most of these colours, so every button carries the
 * preview instead (see {@link BedrockWardrobeForm#button}). It offers the same palette as the chest
 * menu and no free hex field, so what a Bedrock player can pick is exactly what a Java player can.
 * </p>
 */
final class BedrockDyeForm {

    private static final String TITLE = "Tingir";
    private static final String PICK_COLOR = "Escolha uma cor.";
    private static final String PICK_SHADE = "Escolha o tom.";
    private static final String BACK = "Voltar";
    private static final String SHADES_NOTE = "\n§8Ver tons";
    /**
     * Every colour name is drawn in dark grey, never in its own colour: Bedrock text has no RGB, so a
     * name painted in its colour would come out as whichever of the few text colours is nearest.
     */
    private static final String NAME_CODE = "§8";

    private BedrockDyeForm() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /** Whether there is a palette to pick from: the internal dye menu is the one in use, and it has colours. */
    static boolean available() {
        return DyeMenuProvider.canOpenDyeMenu()
                && DyeMenuProvider.menuProvider() instanceof InternalDyeMenu menu
                && !menu.palette().isEmpty();
    }

    /**
     * Opens the list of colours for a cosmetic, the way Java opens its dye menu when a dyeable one is
     * picked. Picking a colour without shades equips the cosmetic in it; one with shades opens those.
     *
     * @param back where the way back leads, with nothing equipped
     * @param done where the player goes once the cosmetic is on
     */
    static void open(@NotNull CosmeticUser user, @NotNull Cosmetic cosmetic, @NotNull Runnable back, @NotNull Runnable done) {
        final Player player = user.getPlayer();
        if (player == null || !(DyeMenuProvider.menuProvider() instanceof InternalDyeMenu menu)) return;

        final List<Runnable> actions = new ArrayList<>();
        final SimpleForm.Builder form = SimpleForm.builder()
                .title(TITLE + " " + cosmetic.getPlainName())
                .content(PICK_COLOR);

        form.button(BACK);
        actions.add(back);

        for (final InternalDyeMenu.PrimaryColor primary : menu.palette()) {
            final boolean hasShades = !primary.secondaryColors().isEmpty();
            String label = NAME_CODE + plain(primary.name());
            if (hasShades) label += SHADES_NOTE;

            BedrockWardrobeForm.button(form, label, cosmetic, primary.color(), null);
            actions.add(hasShades
                    ? () -> openShades(user, cosmetic, primary, back, done)
                    : () -> dye(user, cosmetic, primary.color(), done));
        }

        BedrockWardrobeForm.send(user, form, actions);
    }

    /** The primary itself and each of its shades, every one a finished pick. */
    private static void openShades(@NotNull CosmeticUser user, @NotNull Cosmetic cosmetic,
                                   @NotNull InternalDyeMenu.PrimaryColor primary, @NotNull Runnable back,
                                   @NotNull Runnable done) {
        final Player player = user.getPlayer();
        if (player == null) return;

        final String primaryName = plain(primary.name());
        final List<Runnable> actions = new ArrayList<>();
        final SimpleForm.Builder form = SimpleForm.builder()
                .title(primaryName)
                .content(PICK_SHADE);

        form.button(BACK);
        actions.add(() -> open(user, cosmetic, back, done));

        addShade(form, actions, user, cosmetic, NAME_CODE + primaryName, primary.color(), done);
        for (final InternalDyeMenu.SecondaryColor shade : primary.secondaryColors()) {
            addShade(form, actions, user, cosmetic, NAME_CODE + plain(shade.name()), shade.color(), done);
        }

        BedrockWardrobeForm.send(user, form, actions);
    }

    private static void addShade(@NotNull SimpleForm.Builder form, @NotNull List<Runnable> actions,
                                 @NotNull CosmeticUser user, @NotNull Cosmetic cosmetic, @NotNull String name,
                                 @NotNull Color color, @NotNull Runnable done) {
        BedrockWardrobeForm.button(form, name, cosmetic, color, null);
        actions.add(() -> dye(user, cosmetic, color, done));
    }

    /** A palette name without its MiniMessage formatting. */
    @NotNull
    private static String plain(@NotNull String miniMessage) {
        return PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(miniMessage));
    }

    private static void dye(@NotNull CosmeticUser user, @NotNull Cosmetic cosmetic, @NotNull Color color, @NotNull Runnable done) {
        user.addCosmetic(cosmetic, color);
        done.run();
    }
}
