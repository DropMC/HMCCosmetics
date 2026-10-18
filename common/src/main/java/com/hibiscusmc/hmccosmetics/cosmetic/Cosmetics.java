package com.hibiscusmc.hmccosmetics.cosmetic;

import com.google.common.collect.ImmutableBiMap;
import com.hibiscusmc.hmccosmetics.HMCCosmeticsPlugin;
import com.hibiscusmc.hmccosmetics.util.MessagesUtil;
import lombok.extern.slf4j.Slf4j;
import me.lojosho.shaded.configurate.CommentedConfigurationNode;
import me.lojosho.shaded.configurate.ConfigurateException;
import me.lojosho.shaded.configurate.ConfigurationNode;
import me.lojosho.shaded.configurate.yaml.YamlConfigurationLoader;
import org.bukkit.permissions.Permission;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.stream.Stream;

@Slf4j
public class Cosmetics {
    /**
     * Read from every region thread and the async loads, written only by setup and the API: each write
     * publishes a new copy, in insertion order as before.
     */
    private static volatile ImmutableBiMap<String, Cosmetic> COSMETICS = ImmutableBiMap.of();

    private static CosmeticProvider PROVIDER = CosmeticProvider.Default.INSTANCE;

    public static synchronized void addCosmetic(Cosmetic cosmetic) {
        Map<String, Cosmetic> copy = new LinkedHashMap<>(COSMETICS);
        copy.put(cosmetic.getId(), cosmetic);
        COSMETICS = ImmutableBiMap.copyOf(copy);
    }

    public static synchronized void removeCosmetic(String id) {
        if (!COSMETICS.containsKey(id)) return;
        Map<String, Cosmetic> copy = new LinkedHashMap<>(COSMETICS);
        copy.remove(id);
        COSMETICS = ImmutableBiMap.copyOf(copy);
    }

    public static void removeCosmetic(Cosmetic cosmetic) {
        removeCosmetic(cosmetic.getId());
    }

    @Nullable
    public static Cosmetic getCosmetic(String id) {
        return COSMETICS.get(id);
    }

    @Contract(pure = true)
    @NotNull
    public static Set<Cosmetic> values() {
        return COSMETICS.values();
    }

    @Contract(pure = true)
    @NotNull
    public static Set<String> keys() {
        return COSMETICS.keySet();
    }

    public static boolean hasCosmetic(String id) {
        return COSMETICS.containsKey(id);
    }

    public static boolean hasCosmetic(Cosmetic cosmetic) {
        return COSMETICS.containsValue(cosmetic);
    }

    public static void setup() {
        synchronized (Cosmetics.class) {
            COSMETICS = ImmutableBiMap.of();
        }
        // Loaded into one map and published once, so a reload does not copy the registry per cosmetic
        Map<String, Cosmetic> loaded = new LinkedHashMap<>();

        File cosmeticFolder = new File(HMCCosmeticsPlugin.getInstance().getDataFolder() + "/cosmetics");
        if (!cosmeticFolder.exists()) cosmeticFolder.mkdir();

        File[] directoryListing = cosmeticFolder.listFiles();
        if (directoryListing == null) return;

        try (Stream<Path> walkStream = Files.walk(cosmeticFolder.toPath())) {
            walkStream.filter(p -> p.toFile().isFile()).forEach(child -> {
                if (child.toString().contains(".yml") || child.toString().contains(".yaml")) {
                    MessagesUtil.sendDebugMessages("Scanning " + child);
                    // Loads file
                    YamlConfigurationLoader loader = YamlConfigurationLoader.builder().path(child).build();
                    CommentedConfigurationNode root;
                    try {
                        root = loader.load();
                    } catch (ConfigurateException e) {
                        throw new RuntimeException(e);
                    }
                    setupCosmetics(root, loaded);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
        synchronized (Cosmetics.class) {
            COSMETICS = ImmutableBiMap.copyOf(loaded);
        }

        refreshPermissions();
    }

    /**
     * Register a custom {@link CosmeticProvider} to provide your own user implementation to
     * be used and queried.
     * @param provider the provider to register
     * @throws IllegalArgumentException if the provider is already registered by another plugin
     */
    public static void registerProvider(final CosmeticProvider provider) {
        if(PROVIDER != CosmeticProvider.Default.INSTANCE) {
            throw new IllegalArgumentException("CosmeticProvider already registered by %s, this conflicts with %s attempting to register their own.".formatted(
                PROVIDER.getProviderPlugin().getName(),
                provider.getProviderPlugin().getName()
            ));
        }

        PROVIDER = provider;
    }

    /**
     * Fetch the current {@link CosmeticProvider} being used.
     * @return the current {@link CosmeticProvider} being used
     */
    public static CosmeticProvider getProvider() {
        return PROVIDER;
    }

    private static void setupCosmetics(@NotNull CommentedConfigurationNode config, @NotNull Map<String, Cosmetic> loaded) {
        for (ConfigurationNode cosmeticConfig : config.childrenMap().values()) {
            String id = cosmeticConfig.key().toString();
            MessagesUtil.sendDebugMessages("Attempting to add " + id);
            ConfigurationNode slotNode = cosmeticConfig.node("slot");
            if (slotNode.virtual()) {
                MessagesUtil.sendDebugMessages("Unable to create " + id + " because there is no slot defined!", Level.WARNING);
                continue;
            }
            String slot = slotNode.getString("");
            CosmeticSlot cosmeticSlot = CosmeticSlot.valueOf(slot);
            if (cosmeticSlot == null) {
                MessagesUtil.sendDebugMessages("Unable to create " + id + " because " + slotNode.getString() + " is not a valid slot!", Level.WARNING);
                continue;
            }

            try {
                Cosmetic cosmetic = PROVIDER.createCosmetic(id, cosmeticConfig, cosmeticSlot);
                loaded.put(cosmetic.getId(), cosmetic);
            } catch(Exception ex) {
                log.error("Unable to construct cosmetic for {}, skipping processing it.", id, ex);
            }
        }
    }

    public static void refreshPermissions() {
        final HMCCosmeticsPlugin instance = HMCCosmeticsPlugin.getInstance();
        for (Cosmetic cosmetic : Cosmetics.values()) {
            if (cosmetic.getPermission() == null) continue;
            if (instance.getServer().getPluginManager().getPermission(cosmetic.getPermission()) != null) continue;
            instance.getServer().getPluginManager().addPermission(new Permission(cosmetic.getPermission()));
        }
    }
}