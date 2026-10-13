package com.hibiscusmc.hmccosmetics.gui.dialog;

import com.hibiscusmc.hmccosmetics.api.HMCCosmeticsAPI;
import com.hibiscusmc.hmccosmetics.api.dialog.CosmeticMarket;
import com.hibiscusmc.hmccosmetics.api.dialog.CosmeticOrigin;
import com.hibiscusmc.hmccosmetics.config.section.Wardrobe;
import com.hibiscusmc.hmccosmetics.config.WardrobeSettings;
import com.hibiscusmc.hmccosmetics.cosmetic.Cosmetic;
import com.hibiscusmc.hmccosmetics.cosmetic.CosmeticSlot;
import com.hibiscusmc.hmccosmetics.cosmetic.Rarity;
import com.hibiscusmc.hmccosmetics.gui.dialog.DialogCatalog.CosmeticSet;
import com.hibiscusmc.hmccosmetics.gui.dialog.DialogCatalog.State;
import com.hibiscusmc.hmccosmetics.user.CosmeticUser;
import gg.dropmc.commons.bukkit.api.ui.element.Box;
import gg.dropmc.commons.bukkit.api.ui.element.Element;
import gg.dropmc.commons.bukkit.api.ui.element.Image;
import gg.dropmc.commons.bukkit.api.ui.element.Label;
import gg.dropmc.commons.bukkit.api.ui.icon.PackIcons;
import gg.dropmc.commons.bukkit.api.ui.canvas.Piece;
import gg.dropmc.commons.bukkit.api.ui.element.Row;
import gg.dropmc.commons.bukkit.api.ui.canvas.Target;
import gg.dropmc.commons.bukkit.api.ui.canvas.TextMeter;
import gg.dropmc.commons.bukkit.api.ui.UI;
import gg.dropmc.commons.bukkit.api.ui.UIScreen;
import gg.dropmc.commons.bukkit.api.ui.UISession;
import gg.dropmc.commons.bukkit.api.ui.kit.Banner;
import gg.dropmc.commons.bukkit.api.ui.kit.Buttons;
import gg.dropmc.commons.bukkit.api.ui.kit.Card;
import gg.dropmc.commons.bukkit.api.ui.kit.Kit;
import gg.dropmc.commons.bukkit.api.ui.kit.Palette;
import gg.dropmc.commons.bukkit.api.ui.kit.Price;
import gg.dropmc.commons.bukkit.api.ui.kit.input.Swatches;
import gg.dropmc.commons.bukkit.api.ui.kit.Tabs;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The wardrobe as a dialog: the home screen (sets, or one slot's cosmetics), a cosmetic's page, a
 * set's page and the purchase confirmation.
 * <p>
 * Every screen is drawn from what the player owns and wears at that moment, so a click only has to
 * change something: the screen is drawn again after it and shows the result.
 * </p>
 */
public final class WardrobeDialog {

    private static final String DEFAULT_WARDROBE = "default";
    private static final List<String> TABS = List.of("Conjuntos", "Chapéus", "Costas", "Mão", "Auras");
    private static final List<String> FILTERS = List.of("Todos", "Meus", "À venda", "Limitados");
    private static final int SETS_TAB = 0;
    private static final int ALL = 0;
    private static final int MINE = 1;
    private static final int FOR_SALE = 2;
    private static final int LIMITED = 3;

    private static final int WIDTH = Kit.SCREEN_WIDTH;
    private static final int TABS_Y = 27;
    private static final int FILTERS_Y = 48;
    private static final int CONTENT_Y = 63;
    private static final int DETAILS_X = 134;
    private static final int ACTIONS_Y = 135;
    private static final int BUTTON_ROW = 27;
    private static final int SECOND_COLUMN = WIDTH - 156;
    private static final int BIG_CARD_LIMIT = 3;
    private static final int DESCRIPTION_WRAP = 34;
    private static final int LINE = 9;

    /** Players with a purchase in flight, so a second click cannot start another. */
    private static final Set<UUID> PAYING = ConcurrentHashMap.newKeySet();

    private final Player player;
    private final CosmeticUser user;
    private UISession session;

    private WardrobeDialog(Player player, CosmeticUser user) {
        this.player = player;
        this.user = user;
    }

    /**
     * Opens the dialog on its first screen.
     *
     * @return false when it cannot be shown, and the caller has to open something else: the pack
     *         with the interface has not been generated yet
     */
    public static boolean open(@NotNull CosmeticUser user) {
        Player player = user.getPlayer();
        if (player == null || !UI.isReady()) return false;
        WardrobeDialog dialog = new WardrobeDialog(player, user);
        dialog.session = UI.open(player, WIDTH, screen -> dialog.drawHome(screen, SETS_TAB, ALL));
        return true;
    }

    private DialogCatalog catalog() {
        return new DialogCatalog(player, user);
    }

    // ==================== Home ====================

    private void openHome(int tab, int filter) {
        session.replace(screen -> drawHome(screen, tab, filter));
    }

    private void drawHome(UIScreen screen, int tab, int filter) {
        // The first screen is drawn before UI.open returns the session to keep.
        session = screen.session();
        DialogCatalog catalog = catalog();
        screen.title(Component.text("Cosméticos"));

        screen.add(header(catalog));
        screen.put(0, TABS_Y, Tabs.tabs(TABS, tab, chosen -> openHome(chosen, ALL)));
        screen.put(WIDTH - Kit.CLOSE.width(), TABS_Y,
                Box.of(Kit.CLOSE).onClick(session::close).tooltip(Component.text("Fechar")));
        screen.put(0, FILTERS_Y, Tabs.chips(FILTERS, filter, chosen -> openHome(tab, chosen)));
        screen.y(CONTENT_Y);

        int count = tab == SETS_TAB ? drawSets(screen, catalog, filter)
                : drawGrid(screen, catalog, DialogCatalog.SLOTS.get(tab - 1), filter);
        String unit = tab == SETS_TAB ? (count == 1 ? " conjunto" : " conjuntos") : (count == 1 ? " item" : " itens");
        screen.canvas().textRight(Component.text(count + unit, Palette.FAINT), WIDTH, FILTERS_Y + 3, null);
    }

    /** What the player is wearing, slot by slot, and their balance. */
    private Element header(DialogCatalog catalog) {
        Box bar = Box.of(Kit.TOP_BAR).put(6, 9, Label.of("Vestindo", Palette.MUTED));
        for (int index = 0; index < DialogCatalog.SLOTS.size(); index++) {
            Cosmetic worn = catalog.worn(DialogCatalog.SLOTS.get(index));
            Card slot = Card.slot().selected(worn != null);
            if (worn != null) {
                slot.icon(DialogPieces.icon(worn, PackIcons.Size.MINI, catalog.dye(worn), false))
                        .onClick(() -> openItem(worn))
                        .tooltip(tooltip(catalog, worn));
            }
            bar.put(56 + index * 23, 2, slot);
        }

        CosmeticMarket market = catalog.market();
        if (market != null) {
            Piece coin = DialogPieces.coin();
            bar.put(240, 4, Box.of(Kit.PILL)
                    .put(4, 4, coin == null ? null : Image.of(coin))
                    .put(17, 5, Label.of(format(catalog.balance()), Palette.GOLD))
                    .onClick(() -> {
                        session.close();
                        market.openTopUp(player);
                    })
                    .tooltip(Component.text("Comprar cash")));
        }
        return bar;
    }

    private int drawSets(UIScreen screen, DialogCatalog catalog, int filter) {
        DialogConfig.Promo promo = filter == ALL ? catalog.config().promo() : null;
        if (promo != null) screen.add(banner(catalog, promo));

        int count = 0;
        for (CosmeticSet set : catalog.sets()) {
            if (!matches(catalog, set, filter)) continue;
            screen.add(setRow(catalog, set));
            count++;
        }
        if (count == 0 && promo == null) drawEmpty(screen);
        return count;
    }

    private Element banner(DialogCatalog catalog, DialogConfig.Promo promo) {
        CosmeticSet set = catalog.set(promo.set());
        int filter = switch (promo.filter()) {
            case "mine" -> MINE;
            case "sale" -> FOR_SALE;
            case "lim" -> LIMITED;
            default -> ALL;
        };
        Banner banner = Banner.of(promo.colour(), promo.accent())
                .title(promo.title(), promo.titleColour())
                .subtitle(promo.subtitle())
                .onClick(() -> {
                    if (set != null) openSet(set);
                    else openHome(SETS_TAB, filter);
                });
        for (Cosmetic piece : catalog.listed(promo.pieces()).stream().limit(3).toList()) {
            Element icon = DialogPieces.icon(piece, PackIcons.Size.SMALL, catalog.dye(piece), false);
            if (icon != null) banner.picture(icon);
        }
        return banner;
    }

    /** A set as a row of the list: its name and progress on the left, its pieces on the right. */
    private Element setRow(DialogCatalog catalog, CosmeticSet set) {
        List<Cosmetic> pieces = set.pieces();
        int thumbsLeft = WIDTH - 6 - pieces.size() * 38 + 2;
        Label name = Label.of(set.name());
        Box row = Box.of(Kit.LIST_ROW)
                .put(1, 1, Image.of(Kit.LIST_ROW_BAR, rarityColour(set.rarity())))
                .put(8, 6, name)
                .put(8, 18, progress(catalog, set, List.of()))
                .put(8, 28, setButton(catalog, set));

        Piece limited = set.isLimited() ? DialogPieces.tag(DialogPieces.LIMITED) : null;
        int limitedX = 8 + name.width() + 4;
        if (limited != null && limitedX + limited.width() <= thumbsLeft - 4) row.put(limitedX, 6, Image.of(limited));

        for (int index = 0; index < pieces.size(); index++) {
            row.put(thumbsLeft + index * 38, 3, thumb(catalog, pieces.get(index)));
        }
        return row.onClick(() -> openSet(set));
    }

    /** The set's pips and count, followed by the pack's tags named in {@code tags}. */
    private Element progress(DialogCatalog catalog, CosmeticSet set, List<String> tags) {
        List<Cosmetic> pieces = set.pieces();
        long owned = pieces.stream().filter(catalog::owns).count();
        Component count = Component.text(owned + "/" + pieces.size(), Palette.MUTED);
        int countX = pieces.size() * 6 + 3;
        Row tagRow = tags(tags);

        Box progress = Box.sized(countX + TextMeter.advance(count) + 4 + tagRow.width(), 8);
        for (int index = 0; index < pieces.size(); index++) {
            progress.put(index * 6, 1, Image.of(Kit.PIP, catalog.owns(pieces.get(index)) ? Palette.GREEN : Palette.INK));
        }
        return progress.put(countX, 0, Label.of(count)).put(countX + TextMeter.advance(count) + 4, 0, tagRow);
    }

    private Element setButton(DialogCatalog catalog, CosmeticSet set) {
        List<Cosmetic> buyable = catalog.buyable(set.pieces());
        boolean ownsAll = set.pieces().stream().allMatch(catalog::owns);
        boolean wearsAll = set.pieces().stream().allMatch(catalog::isEquipped);

        if (ownsAll && wearsAll) return Buttons.SMALL_OFF.label(Component.text("Em uso", Palette.GREEN));
        if (ownsAll) return Buttons.SMALL_LIGHT.label("Vestir").onClick(() -> wear(catalog, set));
        if (!buyable.isEmpty()) {
            return Buttons.SMALL_GREEN.content(price(catalog.price(buyable), Palette.WHITE))
                    .onClick(() -> openPurchase("Conjunto " + set.name(), set.pieces()))
                    .tooltip(Component.text("Comprar o que falta do conjunto"));
        }
        Cosmetic missing = set.pieces().stream().filter(piece -> !catalog.owns(piece)).findFirst().orElseThrow();
        return Buttons.SMALL_OFF.label(Component.text(catalog.origin(missing).label(), Palette.FAINT));
    }

    private Element thumb(DialogCatalog catalog, Cosmetic cosmetic) {
        State state = catalog.state(cosmetic);
        return Card.thumb()
                .selected(state == State.EQUIPPED)
                .icon(DialogPieces.icon(cosmetic, PackIcons.Size.SMALL, catalog.dye(cosmetic), state == State.UNAVAILABLE))
                .rightCorner(state == State.OWNED ? Kit.CHECK : state == State.UNAVAILABLE ? Kit.LOCK : null)
                .onClick(() -> openItem(cosmetic))
                .tooltip(tooltip(catalog, cosmetic));
    }

    private int drawGrid(UIScreen screen, DialogCatalog catalog, CosmeticSlot slot, int filter) {
        List<Cosmetic> mine = new ArrayList<>();
        List<Cosmetic> forSale = new ArrayList<>();
        List<Cosmetic> others = new ArrayList<>();
        for (Cosmetic cosmetic : catalog.cosmetics(slot)) {
            if (!matches(catalog, cosmetic, filter)) continue;
            switch (catalog.state(cosmetic)) {
                case EQUIPPED -> mine.addFirst(cosmetic);
                case OWNED -> mine.add(cosmetic);
                case FOR_SALE -> forSale.add(cosmetic);
                case UNAVAILABLE -> others.add(cosmetic);
            }
        }
        drawSection(screen, catalog, "SEUS", mine);
        drawSection(screen, catalog, "À VENDA", forSale);
        drawSection(screen, catalog, "OUTRAS ORIGENS", others);

        int count = mine.size() + forSale.size() + others.size();
        if (count == 0) drawEmpty(screen);
        return count;
    }

    private void drawSection(UIScreen screen, DialogCatalog catalog, String title, List<Cosmetic> cosmetics) {
        if (cosmetics.isEmpty()) return;
        screen.text(Component.text(title + " · " + cosmetics.size(), Palette.MUTED));
        screen.grid(6, 4, cosmetics, cosmetic -> card(catalog, cosmetic));
    }

    private Element card(DialogCatalog catalog, Cosmetic cosmetic) {
        State state = catalog.state(cosmetic);
        return Card.small()
                .selected(state == State.EQUIPPED)
                .icon(DialogPieces.icon(cosmetic, PackIcons.Size.SMALL, catalog.dye(cosmetic), state == State.UNAVAILABLE))
                .leftCorner(cosmetic.isLimited() ? DialogPieces.limitedMark() : null)
                .rightCorner(cosmetic.isDyeable() ? DialogPieces.dyeableMark() : null)
                .stripe(rarityColour(cosmetic.getRarity()))
                .footer(switch (state) {
                    case EQUIPPED -> Label.of("Em uso");
                    case OWNED -> Image.of(Kit.CHECK);
                    case FOR_SALE -> price(catalog.price(cosmetic), Palette.WHITE);
                    case UNAVAILABLE -> Image.of(Kit.LOCK);
                })
                .onClick(() -> openItem(cosmetic))
                .tooltip(tooltip(catalog, cosmetic));
    }

    private void drawEmpty(UIScreen screen) {
        screen.canvas().textCentred(Component.text("Nada por aqui.", Palette.MUTED), 0, WIDTH, CONTENT_Y + 2 * LINE, null);
    }

    private boolean matches(DialogCatalog catalog, Cosmetic cosmetic, int filter) {
        return switch (filter) {
            case MINE -> catalog.owns(cosmetic);
            case FOR_SALE -> catalog.state(cosmetic) == State.FOR_SALE;
            case LIMITED -> cosmetic.isLimited();
            default -> true;
        };
    }

    private boolean matches(DialogCatalog catalog, CosmeticSet set, int filter) {
        return switch (filter) {
            case MINE -> set.pieces().stream().anyMatch(catalog::owns);
            case FOR_SALE -> !catalog.buyable(set.pieces()).isEmpty();
            case LIMITED -> set.isLimited();
            default -> true;
        };
    }

    // ==================== A cosmetic ====================

    private void openItem(Cosmetic cosmetic) {
        session.show(screen -> drawItem(screen, cosmetic, null));
    }

    /** @param preview a colour picked on the page for a cosmetic that is not being worn, or null */
    private void drawItem(UIScreen screen, Cosmetic cosmetic, @Nullable TextColor preview) {
        DialogCatalog catalog = catalog();
        State state = catalog.state(cosmetic);
        TextColor dye = preview != null ? preview : catalog.dye(cosmetic);
        CosmeticSet set = catalog.setOf(cosmetic);
        CosmeticOrigin origin = state == State.UNAVAILABLE ? catalog.origin(cosmetic) : null;
        screen.title(Component.text("Cosméticos"));

        screen.put(0, 0, Image.of(Kit.WELL));
        screen.put(15, 15, DialogPieces.icon(cosmetic, PackIcons.Size.LARGE, dye, false));
        screen.put(DETAILS_X, 2, Label.of(cosmetic.getPlainName()));
        screen.put(DETAILS_X, 18, tags(tagsOf(cosmetic)));
        screen.put(DETAILS_X, 29, switch (state) {
            case EQUIPPED -> Label.of("Em uso", Palette.GREEN);
            case OWNED -> Label.of("Você tem");
            case FOR_SALE -> price(catalog.price(cosmetic), Palette.GOLD);
            case UNAVAILABLE -> Label.of(origin.label(), origin.details().isEmpty() ? Palette.RED : Palette.GOLD);
        });
        List<String> description = wrap(plain(cosmetic.getDescription()));
        for (int index = 0; index < description.size() && index < 3; index++) {
            screen.put(DETAILS_X, 48 + index * LINE, Label.of(description.get(index), Palette.MUTED));
        }
        if (set != null) drawItemSet(screen, catalog, set);

        screen.y(ACTIONS_Y);
        if (origin != null && !origin.details().isEmpty()) {
            for (int index = 0; index < origin.details().size() && index < 2; index++) {
                screen.text(Component.text(origin.details().get(index), index == 0 ? Palette.WHITE : Palette.MUTED));
            }
            screen.skip(1);
        }
        if (cosmetic.isDyeable()) {
            screen.add(Swatches.of(catalog.owns(cosmetic) ? "Cor" : "Prévia da cor", DyePalette.families(), dye,
                    colour -> {
                        // A colour picked for what the player is wearing is worn at once; for anything
                        // else it is only a preview on the page.
                        if (catalog.isEquipped(cosmetic)) equip(catalog, cosmetic, colour);
                        session.replace(next -> drawItem(next, cosmetic, colour));
                    }));
        }

        int actions = screen.y();
        screen.put(0, actions, itemButton(catalog, cosmetic, state, dye, origin));
        screen.put(SECOND_COLUMN, actions, Buttons.DARK.label("Experimentar").onClick(() -> preview(cosmetic)));
        List<Cosmetic> buyable = set == null ? List.of() : catalog.buyable(set.pieces());
        if (buyable.size() >= 2) {
            screen.put(0, actions + BUTTON_ROW, Buttons.GREEN
                    .content(Row.of(5, Label.of("Conjunto"), price(catalog.price(buyable), Palette.WHITE)))
                    .onClick(() -> openPurchase("Conjunto " + set.name(), set.pieces())));
        }
        screen.put(SECOND_COLUMN, actions + BUTTON_ROW, Buttons.DARK.label("Voltar").onClick(session::back));
    }

    private void drawItemSet(UIScreen screen, DialogCatalog catalog, CosmeticSet set) {
        long owned = set.pieces().stream().filter(catalog::owns).count();
        Component label = Component.text("Conjunto ", Palette.MUTED)
                .append(Component.text(set.name(), Palette.WHITE))
                .append(Component.text("  " + owned + "/" + set.pieces().size() + "  ver ›", Palette.MUTED));
        screen.put(DETAILS_X, 76, Label.of(label).target(Target.run(() -> openSet(set))));
        for (int index = 0; index < set.pieces().size(); index++) {
            screen.put(DETAILS_X + index * 37, 84, thumb(catalog, set.pieces().get(index)));
        }
    }

    /**
     * The main button of a cosmetic's page: wear it, take it off, buy it, or, for one that is not
     * sold, where its origin leads. An origin that leads nowhere is its name on a dead button.
     */
    private Element itemButton(DialogCatalog catalog, Cosmetic cosmetic, State state, TextColor dye,
                               @Nullable CosmeticOrigin origin) {
        return switch (state) {
            case EQUIPPED -> Buttons.LIGHT.label("Remover").onClick(() -> HMCCosmeticsAPI.unequipCosmetic(user, cosmetic.getSlot()));
            case OWNED -> Buttons.GREEN.label("Equipar").onClick(() -> equip(catalog, cosmetic, dye));
            case FOR_SALE -> Buttons.GREEN
                    .content(Row.of(5, Label.of("Comprar"), price(catalog.price(cosmetic), Palette.WHITE)))
                    .onClick(() -> openPurchase(cosmetic.getPlainName(), List.of(cosmetic)));
            case UNAVAILABLE -> {
                if (origin == null || !origin.hasAction()) {
                    yield Buttons.OFF.label(origin == null ? "" : origin.label());
                }
                // An address is opened by the client itself; anything else is a click the server follows.
                yield origin.url() != null ? Buttons.GREEN.label(origin.button()).target(Target.url(origin.url()))
                        : Buttons.GREEN.label(origin.button()).onClick(() -> {
                            session.close();
                            origin.action().accept(player);
                        });
            }
        };
    }

    // ==================== A set ====================

    private void openSet(CosmeticSet set) {
        session.show(screen -> drawSet(screen, set.id(), null));
    }

    private void drawSet(UIScreen screen, String setId, @Nullable TextColor preview) {
        DialogCatalog catalog = catalog();
        CosmeticSet set = catalog.set(setId);
        screen.title(Component.text("Cosméticos"));
        if (set == null) {
            // The configuration changed under the player: there is nothing left to show here.
            screen.text(Component.text("Este conjunto não existe mais.", Palette.MUTED));
            screen.add(Buttons.DARK.label("Voltar").onClick(session::back));
            return;
        }
        List<Cosmetic> pieces = set.pieces();

        screen.put(0, 2, Label.of("Conjunto " + set.name()));
        List<String> tags = new ArrayList<>(rarityTags(set.rarity()));
        if (set.isLimited()) tags.add(DialogPieces.LIMITED);
        if (set.isDyeable()) tags.add(DialogPieces.DYEABLE);
        screen.put(0, 18, progress(catalog, set, tags));

        screen.y(4 * LINE);
        boolean isBig = pieces.size() <= BIG_CARD_LIMIT;
        Row cards = Row.gap(4);
        for (Cosmetic piece : pieces) {
            TextColor dye = preview != null && piece.isDyeable() ? preview : catalog.dye(piece);
            cards.add(isBig ? bigCard(catalog, piece, dye) : card(catalog, piece));
        }
        screen.centred(cards);
        if (!isBig) screen.skip(1);

        if (set.isDyeable()) {
            Cosmetic firstDyeable = pieces.stream().filter(Cosmetic::isDyeable).findFirst().orElseThrow();
            screen.add(Swatches.of("Cor do conjunto", DyePalette.families(),
                    preview != null ? preview : catalog.dye(firstDyeable), colour -> {
                        pieces.stream().filter(Cosmetic::isDyeable).forEach(piece -> equip(catalog, piece, colour));
                        session.replace(next -> drawSet(next, setId, colour));
                    }));
        }
        drawSetActions(screen, catalog, set);
    }

    /**
     * A set piece on the set's page: its large picture over its rarity and what the player can do
     * with it. The page shows each piece once, so this card is both the showcase and the way in.
     */
    private Element bigCard(DialogCatalog catalog, Cosmetic cosmetic, TextColor dye) {
        State state = catalog.state(cosmetic);
        Row rarity = tags(rarityTags(cosmetic.getRarity()));
        return Card.big()
                .selected(state == State.EQUIPPED)
                .icon(DialogPieces.icon(cosmetic, PackIcons.Size.LARGE, dye, state == State.UNAVAILABLE))
                .caption(rarity.isEmpty() ? null : rarity)
                .footer(switch (state) {
                    case EQUIPPED -> Label.of("Em uso");
                    case OWNED -> Image.of(Kit.CHECK);
                    case FOR_SALE -> price(catalog.price(cosmetic), Palette.WHITE);
                    case UNAVAILABLE -> Label.of(catalog.origin(cosmetic).label(), Palette.MUTED);
                })
                .onClick(() -> openItem(cosmetic))
                .tooltip(tooltip(catalog, cosmetic));
    }

    private void drawSetActions(UIScreen screen, DialogCatalog catalog, CosmeticSet set) {
        List<Cosmetic> buyable = catalog.buyable(set.pieces());
        boolean ownsAny = set.pieces().stream().anyMatch(catalog::owns);
        boolean ownsAll = set.pieces().stream().allMatch(catalog::owns);
        boolean wearsOwned = set.pieces().stream().filter(catalog::owns).allMatch(catalog::isEquipped);
        Element wear = Buttons.LIGHT.label(ownsAll ? "Vestir conjunto" : "Vestir o que tenho")
                .onClick(() -> wear(catalog, set));
        int top = screen.y();
        int backTop = top;

        if (!buyable.isEmpty()) {
            screen.put(0, top, Buttons.GREEN
                    .content(Row.of(5, Label.of(ownsAny ? "Completar" : "Comprar conjunto"),
                            price(catalog.price(buyable), Palette.WHITE)))
                    .onClick(() -> openPurchase("Conjunto " + set.name(), set.pieces())));
            if (ownsAny && !wearsOwned) {
                screen.put(SECOND_COLUMN, top, wear);
                backTop = top + BUTTON_ROW;
            }
        } else if (ownsAny && !wearsOwned) {
            screen.put(0, top, wear);
        } else if (ownsAny) {
            screen.put(0, top, Buttons.OFF.label(Component.text("Em uso", Palette.GREEN)));
        } else {
            screen.put(0, top, Buttons.OFF.label(catalog.origin(set.pieces().getFirst()).label()));
        }
        screen.put(SECOND_COLUMN, backTop, Buttons.DARK.label("Voltar").onClick(session::back));
    }

    // ==================== Purchase ====================

    private void openPurchase(String title, List<Cosmetic> cosmetics) {
        session.show(screen -> drawPurchase(screen, title, cosmetics));
    }

    /** The confirmation for the pieces of {@code cosmetics} the player lacks and can buy. */
    private void drawPurchase(UIScreen screen, String title, List<Cosmetic> cosmetics) {
        DialogCatalog catalog = catalog();
        List<Cosmetic> buyable = catalog.buyable(cosmetics);
        int price = catalog.price(buyable);
        int balance = catalog.balance();
        boolean canAfford = balance >= price;
        screen.title(Component.text("Cosméticos"));

        screen.canvas().textCentred(Component.text(canAfford ? "Confirmar compra" : "Cash insuficiente", Palette.WHITE),
                0, WIDTH, 2, null);
        Box row = Box.of(Kit.LIST_ROW);
        for (int index = 0; index < buyable.size(); index++) {
            Cosmetic cosmetic = buyable.get(index);
            row.put(8 + index * 36, 5, DialogPieces.icon(cosmetic, PackIcons.Size.SMALL, catalog.dye(cosmetic), false));
        }
        int textX = 8 + buyable.size() * 36 + 4;
        row.put(textX, 12, Label.of(title));
        row.put(textX, 23, Label.of(buyable.size() == 1 ? "1 peça" : buyable.size() + " peças", Palette.MUTED));
        screen.put(0, 2 * LINE, row);

        drawAmount(screen, 8 * LINE, "Preço", price, Palette.WHITE);
        drawAmount(screen, 10 * LINE, "Seu saldo", balance, Palette.WHITE);
        drawAmount(screen, 12 * LINE, canAfford ? "Depois da compra" : "Faltam", Math.abs(balance - price),
                canAfford ? Palette.GREEN : Palette.RED);

        CosmeticMarket market = catalog.market();
        if (canAfford) {
            screen.put(0, ACTIONS_Y, Buttons.GREEN
                    .content(Row.of(5, Label.of("Confirmar"), price(price, Palette.WHITE)))
                    .onClick(() -> {
                        // What is charged is what this screen showed: anything else redraws it.
                        if (pay(buyable, price)) session.back();
                    }));
        } else if (market != null) {
            screen.put(0, ACTIONS_Y, Buttons.GREEN.label("Comprar cash").onClick(() -> {
                session.close();
                market.openTopUp(player);
            }));
        }
        screen.put(SECOND_COLUMN, ACTIONS_Y, Buttons.DARK.label("Cancelar").onClick(session::back));
    }

    private void drawAmount(UIScreen screen, int y, String label, int amount, TextColor colour) {
        Element value = price(amount, colour);
        screen.put(40, y, Label.of(label, Palette.MUTED));
        screen.put(280 - value.width(), y, value);
    }

    /**
     * Pays for {@code buyable}, when it is still what the player confirmed.
     *
     * @return false when nothing was charged: the price moved, a piece was bought meanwhile, the
     *         balance fell short or another purchase of theirs is still in flight
     */
    private boolean pay(List<Cosmetic> buyable, int confirmedPrice) {
        DialogCatalog catalog = catalog();
        CosmeticMarket market = catalog.market();
        List<Cosmetic> still = catalog.buyable(buyable);
        if (market == null || still.size() != buyable.size() || catalog.price(still) != confirmedPrice) return false;
        if (!PAYING.add(player.getUniqueId())) return false;
        try {
            if (!market.buy(player, still)) return false;
            DialogCatalog.markBought(player.getUniqueId(), still);
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            return true;
        } finally {
            PAYING.remove(player.getUniqueId());
        }
    }

    // ==================== Actions ====================

    private void equip(DialogCatalog catalog, Cosmetic cosmetic, @Nullable TextColor dye) {
        if (!catalog.owns(cosmetic)) return;
        Color colour = dye == null || !cosmetic.isDyeable() ? null : Color.fromRGB(dye.value());
        HMCCosmeticsAPI.equipCosmetic(user, cosmetic, colour);
    }

    private void wear(DialogCatalog catalog, CosmeticSet set) {
        set.pieces().forEach(piece -> equip(catalog, piece, catalog.dye(piece)));
    }

    /**
     * Tries the cosmetic on, which only the wardrobe may do: a preview puts on the player something
     * they may not own, and leaving the wardrobe is what takes it off again. A player who opened the
     * dialog out in the world is walked into the wardrobe first, and tries nothing on when that fails.
     */
    private void preview(Cosmetic cosmetic) {
        session.close();
        if (!user.isInWardrobe()) {
            Wardrobe wardrobe = WardrobeSettings.getWardrobe(DEFAULT_WARDROBE);
            if (wardrobe != null) user.enterWardrobe(wardrobe, false);
        }
        if (user.isInWardrobe()) user.togglePreview(cosmetic);
    }

    // ==================== Shared pieces ====================

    /** A coin and an amount, or the bare amount on a server with no coin texture configured. */
    private static Element price(int amount, TextColor colour) {
        Component text = Component.text(format(amount), colour);
        Piece coin = DialogPieces.coin();
        return coin == null ? Label.of(text) : Price.of(coin, text);
    }

    /** The pack's tags named in {@code names}, side by side. A name the pack has no tag for is skipped. */
    private static Row tags(List<String> names) {
        Row row = Row.gap(2);
        for (String name : names) {
            Piece tag = DialogPieces.tag(name);
            if (tag != null) row.add(Image.of(tag));
        }
        return row;
    }

    /** The cosmetic's tags in the order they are shown: its rarity, then limited, then dyeable. */
    private static List<String> tagsOf(Cosmetic cosmetic) {
        List<String> tags = new ArrayList<>(rarityTags(cosmetic.getRarity()));
        if (cosmetic.isLimited()) tags.add(DialogPieces.LIMITED);
        if (cosmetic.isDyeable()) tags.add(DialogPieces.DYEABLE);
        return tags;
    }

    private static List<String> rarityTags(@Nullable Rarity rarity) {
        return rarity == null ? List.of() : List.of(rarity.name().toLowerCase(Locale.ROOT));
    }

    private Component tooltip(DialogCatalog catalog, Cosmetic cosmetic) {
        Component tooltip = Component.text(cosmetic.getPlainName(), Palette.WHITE);
        Component tags = Component.empty();
        for (String name : tagsOf(cosmetic)) {
            Piece tag = DialogPieces.tag(name);
            if (tag != null) tags = tags.append(tag.inline()).append(Component.space());
        }
        if (!tags.children().isEmpty()) tooltip = tooltip.append(Component.newline()).append(tags);

        CosmeticSet set = catalog.setOf(cosmetic);
        tooltip = tooltip.append(Component.newline())
                .append(Component.text(set == null ? "Avulso" : "Conjunto " + set.name(), Palette.MUTED));
        if (catalog.state(cosmetic) != State.UNAVAILABLE) return tooltip;
        return tooltip.append(Component.newline()).append(Component.text(catalog.origin(cosmetic).label(), Palette.GOLD));
    }

    private static TextColor rarityColour(@Nullable Rarity rarity) {
        return rarity == null ? Palette.MUTED : rarity.color();
    }

    private static String format(int amount) {
        return NumberFormat.getIntegerInstance(Locale.of("pt", "BR")).format(amount);
    }

    private static String plain(@Nullable String text) {
        return text == null ? "" : text.replaceAll("<[^<>]*>", "");
    }

    private static List<String> wrap(String text) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() + word.length() + 1 > DESCRIPTION_WRAP && !line.isEmpty()) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }
}
