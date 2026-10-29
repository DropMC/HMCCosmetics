package com.hibiscusmc.hmccosmetics.hooks.misc;

import com.hibiscusmc.hmccosmetics.util.MessagesUtil;
import me.lojosho.hibiscuscommons.hooks.Hook;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the skin a player shows from the DropMC commons skin changer ({@code /skin}), for the aura
 * buttons of the Bedrock wardrobe, which draw the viewer wearing each aura.
 * <p>
 * {@code SkinAPI.displaySkin} is the one to ask rather than the player's profile: it also knows a
 * {@code /skin} choice and a Bedrock device skin that have not reached the profile yet, and a
 * Bedrock player, the only one who sees these buttons, is exactly who that happens to.
 * </p>
 * Commons is reached by reflection instead of compiled against, because it is built for Java 25 and
 * this fork for Java 21, and javac refuses a class file newer than the release it targets. Only three
 * members are needed, and they are looked up once, the first time a skin is asked for.
 */
public class HookCommonsSkins extends Hook {

    private static final String ID = "CommonsBukkit";
    private static final String SKIN_API = "gg.dropmc.commons.bukkit.feature.skin.api.SkinAPI";
    private static final String SKIN_DATA = "gg.dropmc.commons.shared.feature.mojang.api.SkinData";
    /** Every skin sheet lives here, a {@code /skin} upload included, so this is also all the API downloads from. */
    private static final Pattern TEXTURE_URL = Pattern.compile("https?://textures\\.minecraft\\.net/texture/([0-9a-f]{1,64})");
    private static final long TIMEOUT_MILLIS = 2000;

    private static HookCommonsSkins instance;
    private static Handles handles;
    private static boolean unreachable;

    /** A skin sheet on textures.minecraft.net and whether it is drawn on the slim model. */
    public record Skin(@NotNull String texture, boolean slim) {
    }

    private record Handles(MethodHandle displaySkin, MethodHandle skinUrl, MethodHandle isSlim) {
    }

    public HookCommonsSkins() {
        super(ID);
        instance = this;
    }

    /**
     * The skin this player shows. Never fails: empty when commons is not installed, when the skin is
     * not on textures.minecraft.net, or when it takes longer than a couple of seconds to resolve.
     */
    @NotNull
    public static CompletableFuture<Optional<Skin>> skin(@NotNull Player player) {
        final Handles resolved = handles();
        if (resolved == null) return CompletableFuture.completedFuture(Optional.empty());

        try {
            final CompletableFuture<?> display = (CompletableFuture<?>) resolved.displaySkin().invoke(player.getUniqueId());
            return display
                    .thenApply(found -> ((Optional<?>) found).flatMap(data -> skinOf(resolved, data)))
                    .completeOnTimeout(Optional.empty(), TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    .exceptionally(failure -> Optional.empty());
        } catch (Throwable t) {
            MessagesUtil.sendDebugMessages("Could not ask CommonsBukkit for the skin of " + player.getName() + ": " + t);
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    private static Optional<Skin> skinOf(Handles resolved, Object data) {
        try {
            final String url = (String) resolved.skinUrl().invoke(data);
            if (url == null) return Optional.empty();

            final Matcher texture = TEXTURE_URL.matcher(url);
            if (!texture.matches()) return Optional.empty();
            return Optional.of(new Skin(texture.group(1), (boolean) resolved.isSlim().invoke(data)));
        } catch (Throwable t) {
            MessagesUtil.sendDebugMessages("Could not read a skin from CommonsBukkit: " + t);
            return Optional.empty();
        }
    }

    /**
     * Resolved on first use rather than when the hook loads, so a commons that is detected but loads
     * after this plugin is still found. A failed lookup is not retried: it means an incompatible commons.
     */
    private static synchronized Handles handles() {
        if (handles != null || unreachable) return handles;
        if (instance == null || !instance.isDetected()) return null;

        final Plugin commons = Bukkit.getPluginManager().getPlugin(ID);
        if (commons == null || !commons.isEnabled()) return null;

        try {
            final ClassLoader loader = commons.getClass().getClassLoader();
            final Class<?> api = Class.forName(SKIN_API, true, loader);
            final Class<?> data = Class.forName(SKIN_DATA, true, loader);
            final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            handles = new Handles(
                    lookup.findStatic(api, "displaySkin", MethodType.methodType(CompletableFuture.class, UUID.class)),
                    lookup.findVirtual(data, "skinUrl", MethodType.methodType(String.class)),
                    lookup.findVirtual(data, "isSlim", MethodType.methodType(boolean.class)));
        } catch (ReflectiveOperationException | RuntimeException e) {
            unreachable = true;
            MessagesUtil.sendDebugMessages("CommonsBukkit is installed but its SkinAPI could not be reached, aura "
                    + "buttons keep their plain icon: " + e, Level.WARNING);
        }
        return handles;
    }
}
