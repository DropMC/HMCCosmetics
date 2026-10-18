package com.hibiscusmc.hmccosmetics.util;

import me.lojosho.shaded.configurate.ConfigurationNode;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;

public class TranslationUtil {

    // unlocked-cosmetic -> true -> True
    private static final Map<@NotNull String, @NotNull List<TranslationPair>> KEYS = new ConcurrentHashMap<>();

    public static void setup(@NotNull ConfigurationNode config) {
        KEYS.clear();
        for (ConfigurationNode node : config.childrenMap().values()) {
            final ArrayList<TranslationPair> pairs = new ArrayList<>();
            for (ConfigurationNode translatableMessage : node.childrenMap().values()) {
                String key = translatableMessage.key().toString();
                key = key.replaceAll("'", ""); // Autoupdater adds ' to it? Removes it from the key
                TranslationPair pair = new TranslationPair(key, translatableMessage.getString());
                pairs.add(pair);
                MessagesUtil.sendDebugMessages("setupTranslation key:" + node.key().toString() + " | " + node);
                MessagesUtil.sendDebugMessages("Overall Key " + node.key().toString());
                MessagesUtil.sendDebugMessages("Key '" + pair.key() + "' Value '" + pair.value() + "'");
            }
            KEYS.put(node.key().toString().toLowerCase(), pairs);
        }
    }

    public static String getTranslation(@NotNull String key, @NotNull String message) {
        final List<TranslationPair> pairs = KEYS.get(key);
        for (TranslationPair pair : pairs) {
            if (pair.key().equals(message.toLowerCase())) return pair.value();
        }

        return message;
    }
}
