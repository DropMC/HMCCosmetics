package com.hibiscusmc.hmccosmetics.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Turns a message into something a Bedrock form can draw, glyphs included.
 * <p>
 * A Nexo glyph is a character in the pack's font, and the converted Bedrock pack puts that same
 * character somewhere else: Scaffolding computes the table and publishes it as a Nexo
 * {@code ComponentProjections} projection. Every other Bedrock surface gets that translation from
 * the core plugin's packet listener, but a form never becomes a packet here (Floodgate sends it
 * over its own channel), so a form's text has to be projected by hand or every glyph in it reaches
 * the player as an empty box.
 * </p>
 * The font is dropped along the way, which is correct rather than lossy: Bedrock draws all text in
 * one font, and the projected codepoint is already the one that font has the glyph at.
 * <p>
 * {@code ComponentProjections} is a Nexo 1.25+ API reached reflectively, so an older or absent Nexo
 * costs the glyphs and nothing else: the text still goes out, without the projection.
 * </p>
 */
public final class BedrockText {

    private static final MethodHandle PROJECTIONS_APPLY = resolveProjectionsApply();

    private BedrockText() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /**
     * A {@code messages.yml} entry, ready for a form.
     *
     * @param fallback used when the key is missing from the live file, which the plugin never fills
     *                 in with its own defaults
     */
    @NotNull
    public static String fromKey(@NotNull Player player, @NotNull String key, @NotNull String fallback) {
        Component message = MessagesUtil.processString(player, key);
        return message == null ? fallback : legacy(player, message);
    }

    /** A MiniMessage string, Nexo tags included, ready for a form. */
    @NotNull
    public static String fromMiniMessage(@NotNull Player player, @NotNull String miniMessage) {
        return legacy(player, MessagesUtil.processStringNoKey(player, miniMessage));
    }

    @NotNull
    private static String legacy(@NotNull Player player, @NotNull Component component) {
        return LegacyComponentSerializer.legacySection().serialize(project(player, component));
    }

    /**
     * Rewrites the Java codepoints to the Bedrock ones. Returns the input untouched if anything is
     * missing or the projection throws, so a broken conversion costs a glyph and never the text.
     */
    @NotNull
    private static Component project(@NotNull Player player, @NotNull Component component) {
        if (PROJECTIONS_APPLY == null) return component;

        try {
            Component projected = (Component) PROJECTIONS_APPLY.invoke(player, component);
            return projected != null ? projected : component;
        } catch (Throwable throwable) {
            return component;
        }
    }

    @Nullable
    private static MethodHandle resolveProjectionsApply() {
        try {
            Class<?> type = Class.forName("com.nexomc.nexo.api.ComponentProjections");
            Object instance = type.getField("INSTANCE").get(null);
            MethodHandle handle = MethodHandles.lookup().findVirtual(
                    type, "apply", MethodType.methodType(Component.class, Player.class, Component.class));
            return handle.bindTo(instance);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            // Nexo older than 1.25, or absent. Glyphs in forms are simply off.
            return null;
        }
    }
}
