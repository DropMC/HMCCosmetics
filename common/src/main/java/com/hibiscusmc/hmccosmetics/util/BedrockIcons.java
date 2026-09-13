package com.hibiscusmc.hmccosmetics.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Where a Bedrock client can find the icon of a Java item, for the buttons of a Bedrock form.
 * <p>
 * A form button draws its image from a texture path inside a pack the client already has, and the
 * pack Bedrock players get is the one Scaffolding converts from the Nexo pack. Two files it writes
 * are what turn a cosmetic into such a path, and neither is reachable through an API:
 * </p>
 * <ol>
 *   <li>the Geyser mappings say which Bedrock icon a Java item model became
 *       ({@code nexo:beanie} to {@code hmccosmetics_beanie_4h5l3y});</li>
 *   <li>the pack's own {@code item_texture.json} says where that icon's texture lives
 *       ({@code textures/icons/hmccosmetics_beanie_4h5l3y}).</li>
 * </ol>
 * <p>
 * The second lookup is not decoration: most icons sit under {@code textures/icons}, but a few
 * hundred do not, so composing the path from the icon name alone would quietly lose them.
 * </p>
 * Everything is missable, and every miss is answered with {@code null} so the caller falls back to
 * a button with no image: Scaffolding may not be installed, may not have generated yet, and the
 * paths change every time it does generate.
 */
public final class BedrockIcons {

    private static final String MAPPINGS_PATH = "output/geyser_mappings/scaffolding_mappings.json";
    private static final String PACK_PATH = "output/Scaffolding.mcpack";
    private static final String TEXTURE_INDEX = "textures/item_texture.json";

    private static Map<String, String> pathsByModel = Map.of();
    /**
     * Texture path by {@code <material>/<customModelData>}, for the cosmetics that are still a
     * vanilla item plus a model number rather than a Nexo id. Scaffolding writes those as a second
     * kind of entry ({@code type: legacy}), and a whole set of cosmetics is addressed only that way.
     */
    private static Map<String, String> pathsByLegacyModel = Map.of();
    /** Pack the current index was read from, so a regenerated pack is picked up without a restart. */
    private static long indexedPack = Long.MIN_VALUE;

    private BedrockIcons() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /**
     * Reads the index ahead of the first player who needs it. The files are about a megabyte of
     * JSON together, which is not something to parse on the main thread while a menu is opening.
     */
    public static void warmUp(@NotNull Plugin plugin) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> texturePath("nexo:warm_up", null));
    }

    /**
     * The texture path for a cosmetic's item, by whichever of the two things identifies it.
     *
     * @param model the item model key from the cosmetic's {@code material}, e.g. {@code nexo:beanie}
     * @param item  the cosmetic's item, read only when the model is a plain vanilla material and the
     *              icon has to be found by its custom model data instead
     * @return the path to hand a form image, or null when this item has no Bedrock icon
     */
    @Nullable
    public static synchronized String texturePath(@Nullable String model, @Nullable ItemStack item) {
        File pack = scaffoldingFile(PACK_PATH);
        long stamp = pack == null ? Long.MIN_VALUE : pack.lastModified();
        if (stamp != indexedPack) {
            indexedPack = stamp;
            buildIndex(pack);
        }

        if (model != null && model.indexOf(':') >= 0) {
            String path = pathsByModel.get(model);
            if (path != null) return path;
        }

        String legacy = legacyKey(item);
        return legacy == null ? null : pathsByLegacyModel.get(legacy);
    }

    /**
     * {@code <material>/<customModelData>}, or null for an item that has no model data.
     * <p>
     * The component is read before the old field, and not only for tidiness: from 1.21.5 on, the
     * item builder behind every cosmetic writes the number into the component and leaves the old
     * field empty, so reading only the field would find nothing on any current server.
     * </p>
     */
    @Nullable
    @SuppressWarnings("deprecation")
    private static String legacyKey(@Nullable ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;

        List<Float> floats = meta.getCustomModelDataComponent().getFloats();
        if (!floats.isEmpty()) return item.getType().getKey() + "/" + floats.get(0).intValue();

        return meta.hasCustomModelData() ? item.getType().getKey() + "/" + meta.getCustomModelData() : null;
    }

    private static void buildIndex(@Nullable File pack) {
        pathsByModel = Map.of();
        pathsByLegacyModel = Map.of();

        Map<String, String> icons = readIconNames();
        if (icons.isEmpty() || pack == null || !pack.isFile()) return;

        Map<String, String> pathsByIcon = readTexturePaths(pack);
        if (pathsByIcon.isEmpty()) return;

        Map<String, String> byModel = new HashMap<>();
        Map<String, String> byLegacy = new HashMap<>();
        icons.forEach((key, icon) -> {
            String path = pathsByIcon.get(icon);
            if (path == null) return;
            // A legacy key carries the material it was listed under, which a model key never does.
            if (key.indexOf('/') >= 0) byLegacy.put(key, path);
            else byModel.put(key, path);
        });

        pathsByModel = Map.copyOf(byModel);
        pathsByLegacyModel = Map.copyOf(byLegacy);
        MessagesUtil.sendDebugMessages("Indexed " + byModel.size() + " Bedrock icons by model and "
                + byLegacy.size() + " by model data");
    }

    /**
     * Item to Bedrock icon name, out of the mappings Scaffolding writes for Geyser. Keyed by the
     * item model where there is one, and by {@code <material>/<customModelData>} where there is not.
     */
    private static Map<String, String> readIconNames() {
        File mappings = scaffoldingFile(MAPPINGS_PATH);
        if (mappings == null || !mappings.isFile()) return Map.of();

        Map<String, String> icons = new HashMap<>();
        try (Reader reader = Files.newBufferedReader(mappings.toPath(), StandardCharsets.UTF_8)) {
            JsonObject items = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("items");
            if (items == null) return Map.of();

            for (Map.Entry<String, JsonElement> material : items.entrySet()) {
                if (!material.getValue().isJsonArray()) continue;
                for (JsonElement element : material.getValue().getAsJsonArray()) {
                    if (!element.isJsonObject()) continue;
                    JsonObject definition = element.getAsJsonObject();
                    JsonObject options = definition.getAsJsonObject("bedrock_options");
                    if (options == null) continue;

                    String icon = string(options, "icon");
                    String key = keyOf(definition, material.getKey());
                    if (key != null && icon != null) icons.put(key, icon);
                }
            }
        } catch (Exception e) {
            MessagesUtil.sendDebugMessages("Could not read the Scaffolding mappings: " + e);
            return Map.of();
        }
        return icons;
    }

    /** The model key of a definition entry, or the legacy key of a custom model data one. */
    @Nullable
    private static String keyOf(@NotNull JsonObject definition, @NotNull String material) {
        String model = string(definition, "model");
        if (model != null) return model;

        JsonElement modelData = definition.get("custom_model_data");
        return modelData != null && modelData.isJsonPrimitive()
                ? material + "/" + modelData.getAsInt()
                : null;
    }

    /** Bedrock icon name to texture path, out of the pack's own texture index. */
    private static Map<String, String> readTexturePaths(@NotNull File pack) {
        Map<String, String> paths = new HashMap<>();
        try (ZipFile zip = new ZipFile(pack)) {
            ZipEntry entry = zip.getEntry(TEXTURE_INDEX);
            if (entry == null) return Map.of();

            try (Reader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                JsonObject data = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("texture_data");
                if (data == null) return Map.of();

                for (Map.Entry<String, JsonElement> icon : data.entrySet()) {
                    if (!icon.getValue().isJsonObject()) continue;
                    String path = texturesOf(icon.getValue().getAsJsonObject());
                    if (path != null) paths.put(icon.getKey(), path);
                }
            }
        } catch (Exception e) {
            MessagesUtil.sendDebugMessages("Could not read the Scaffolding pack: " + e);
            return Map.of();
        }
        return paths;
    }

    /** The {@code textures} field, which is a path, or a list of them for an animated icon. */
    @Nullable
    private static String texturesOf(@NotNull JsonObject icon) {
        JsonElement textures = icon.get("textures");
        if (textures == null) return null;
        if (textures.isJsonPrimitive()) return textures.getAsString();
        if (!textures.isJsonArray()) return null;

        JsonArray frames = textures.getAsJsonArray();
        return frames.isEmpty() || !frames.get(0).isJsonPrimitive() ? null : frames.get(0).getAsString();
    }

    @Nullable
    private static String string(@NotNull JsonObject object, @NotNull String field) {
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    @Nullable
    private static File scaffoldingFile(@NotNull String path) {
        Plugin scaffolding = Bukkit.getPluginManager().getPlugin("Scaffolding");
        return scaffolding == null ? null : new File(scaffolding.getDataFolder(), path);
    }
}
