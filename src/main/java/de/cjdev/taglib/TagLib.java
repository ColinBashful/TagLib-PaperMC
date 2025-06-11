package de.cjdev.taglib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.tag.Tag;
import io.papermc.paper.registry.tag.TagKey;
import net.kyori.adventure.key.Key;
import net.minecraft.util.StringRepresentable;
import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class TagLib extends JavaPlugin {

    private static Logger LOGGER;
    public static final Map<NamespacedKey, Set<NamespacedKey>> ITEM_TAGS = new HashMap<>();
    public static final Map<NamespacedKey, Set<NamespacedKey>> ITEM_TAGS_REVERSE = new HashMap<>();
    public static final Map<NamespacedKey, Set<NamespacedKey>> BLOCK_TAGS = new HashMap<>();
    public static final Map<NamespacedKey, Set<NamespacedKey>> BLOCK_TAGS_REVERSE = new HashMap<>();
    public static final Map<NamespacedKey, Set<NamespacedKey>> ENTITY_TYPE_TAGS = new HashMap<>();
    public static final Map<NamespacedKey, Set<NamespacedKey>> ENTITY_TYPE_TAGS_REVERSE = new HashMap<>();

    @Override
    public void onEnable() {
        LOGGER = getLogger();

        // Plugin startup logic
        loadRegistryTags(Registry.ITEM, ITEM_TAGS, ITEM_TAGS_REVERSE);
        loadRegistryTags(Registry.BLOCK, BLOCK_TAGS, BLOCK_TAGS_REVERSE);
        loadRegistryTags(Registry.ENTITY_TYPE, ENTITY_TYPE_TAGS, ENTITY_TYPE_TAGS_REVERSE);

        Map<PathRecord.TagType, List<Map.Entry<NamespacedKey, NamespacedKey>>> TEMP_REFERENCES = new HashMap<>(PathRecord.TagType.values().length);
        for (Plugin plugin : getServer().getPluginManager().getPlugins()) {
            URL classUrl = plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
            if (classUrl == null)
                continue;

            try {
                Path path = Paths.get(classUrl.toURI());

                ZipFile jarFile = new ZipFile(path.toFile());
                addTagsFromPluginJar(jarFile, pathRecord -> {
                    TEMP_REFERENCES.computeIfAbsent(pathRecord.tagType(), tagType -> new ArrayList<>()).add(Map.entry(pathRecord.tagName, pathRecord.targetTag));
                });
            } catch (URISyntaxException ignored) {
            } catch (IOException e) {
                LOGGER.warning(e.getMessage());
            }
        }
        mergeTags(TEMP_REFERENCES).forEach((tagType, keySetMap) -> {
            Map<NamespacedKey, Set<NamespacedKey>> TAG_MAP = tagType.getTagMap();

            for (Map.Entry<NamespacedKey, NamespacedKey> keyKeyEntry : keySetMap) {
                Set<NamespacedKey> TARGET_TAG = TAG_MAP.get(keyKeyEntry.getValue());
                if (TARGET_TAG == null)
                    continue;
                TAG_MAP.computeIfAbsent(keyKeyEntry.getKey(), key -> new HashSet<>()).addAll(TARGET_TAG);
            }
            for (Map.Entry<NamespacedKey, NamespacedKey> keyKeyEntry : keySetMap) {
                Set<NamespacedKey> TARGET_TAG = TAG_MAP.get(keyKeyEntry.getValue());
                if (TARGET_TAG == null)
                    continue;
                TAG_MAP.computeIfAbsent(keyKeyEntry.getKey(), key -> new HashSet<>()).addAll(TARGET_TAG);
            }
        });
    }

    private static Map<PathRecord.TagType, List<Map.Entry<NamespacedKey, NamespacedKey>>> mergeTags(Map<PathRecord.TagType, List<Map.Entry<NamespacedKey, NamespacedKey>>> inputMap) {
        Map<PathRecord.TagType, List<Map.Entry<NamespacedKey, NamespacedKey>>> mergedMap = new HashMap<>();

        // Iterate over each TagType in the input map
        for (Map.Entry<PathRecord.TagType, List<Map.Entry<NamespacedKey, NamespacedKey>>> entry : inputMap.entrySet()) {
            PathRecord.TagType tagType = entry.getKey();
            List<Map.Entry<NamespacedKey, NamespacedKey>> entryList = entry.getValue();

            // Create a map to track key dependencies
            Map<NamespacedKey, Set<NamespacedKey>> dependenciesMap = new HashMap<>();
            Map<NamespacedKey, Set<NamespacedKey>> allTags = new HashMap<>();

            // Initialize dependencies map
            for (Map.Entry<NamespacedKey, NamespacedKey> e : entryList) {
                dependenciesMap.putIfAbsent(e.getKey(), new HashSet<>());
                dependenciesMap.putIfAbsent(e.getValue(), new HashSet<>());
                dependenciesMap.get(e.getKey()).add(e.getValue()); // Add dependency
            }

            // Merge dependencies for each key and track all tags for the result
            for (Map.Entry<NamespacedKey, Set<NamespacedKey>> depEntry : dependenciesMap.entrySet()) {
                Set<NamespacedKey> mergedSet = new HashSet<>();
                mergeDependencies(depEntry.getKey(), dependenciesMap, mergedSet);
                allTags.put(depEntry.getKey(), mergedSet);
            }

            // Now create the list of merged entries for this tagType
            List<Map.Entry<NamespacedKey, NamespacedKey>> mergedEntries = new ArrayList<>();
            for (NamespacedKey key : allTags.keySet()) {
                for (NamespacedKey depKey : allTags.get(key)) {
                    mergedEntries.add(new AbstractMap.SimpleEntry<>(key, depKey));
                }
            }

            mergedMap.put(tagType, mergedEntries);
        }

        return mergedMap;
    }

    // Recursive function to merge the dependencies
    private static void mergeDependencies(NamespacedKey key, Map<NamespacedKey, Set<NamespacedKey>> dependenciesMap, Set<NamespacedKey> mergedSet) {
        // If the key has dependencies, add them recursively
        if (dependenciesMap.containsKey(key)) {
            for (NamespacedKey depKey : dependenciesMap.get(key)) {
                if (!mergedSet.contains(depKey)) {
                    mergedSet.add(depKey);
                    mergeDependencies(depKey, dependenciesMap, mergedSet); // Recursively merge dependencies
                }
            }
        }
    }

    private <T extends Keyed> void loadRegistryTags(Registry<@NotNull T> registry, Map<NamespacedKey, Set<NamespacedKey>> STORE_IN, Map<NamespacedKey, Set<NamespacedKey>> REVERSE_MAP) {
        for (Tag<T> tag : registry.getTags()) {
            TagKey<T> TAG_KEY = tag.tagKey();
            NamespacedKey TAG_KEY_NAMESPACED = new NamespacedKey(TAG_KEY.key().namespace(), TAG_KEY.key().value());
            Set<NamespacedKey> KEYS = STORE_IN.computeIfAbsent(TAG_KEY_NAMESPACED, key -> new HashSet<>());
            for (TypedKey<T> tTypedKey : registry.getTag(TAG_KEY)) {
                Key key = tTypedKey.key();
                KEYS.add(new NamespacedKey(key.namespace(), key.value()));
                REVERSE_MAP.computeIfAbsent(TAG_KEY_NAMESPACED, namespacedKey -> new HashSet<>()).add(TAG_KEY_NAMESPACED);
            }
        }
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }

    public static void addItemTag(NamespacedKey tagKey, @NotNull NamespacedKey itemId) {
        ITEM_TAGS.computeIfAbsent(tagKey, key -> new HashSet<>()).add(itemId);
        ITEM_TAGS_REVERSE.computeIfAbsent(itemId, namespacedKey -> new HashSet<>()).add(tagKey);
    }

    private record PathRecord(TagType tagType, NamespacedKey tagName, NamespacedKey targetTag) {
        @Override
        public int hashCode() {
            return (tagType.hashCode() * 31 + tagType.hashCode()) * 31 + targetTag.hashCode();
        }

        private enum TagType implements StringRepresentable {
            ITEM("item", ITEM_TAGS),
            BLOCK("block", BLOCK_TAGS),
            ENTITY_TYPE("entity_type", ENTITY_TYPE_TAGS);

            private final String name;
            private final Map<NamespacedKey, Set<NamespacedKey>> connectedMap;

            private static final Map<String, TagType> LOOKUP;

            TagType(String name, Map<NamespacedKey, Set<NamespacedKey>> connectedMap) {
                this.name = name;
                this.connectedMap = connectedMap;
            }

            private static @Nullable TagType parseName(String name) {
                return LOOKUP.get(name);
            }

            @Override
            public String getSerializedName() {
                return this.name;
            }

            public Map<NamespacedKey, Set<NamespacedKey>> getTagMap() {
                return this.connectedMap;
            }

            static {
                Map<String, TagType> lookup = new HashMap<>(values().length);
                for (TagType value : values()) {
                    lookup.put(value.getSerializedName(), value);
                }
                LOOKUP = lookup;
            }
        }
    }

    private static void addTagsFromPluginJar(ZipFile zipFile, Consumer<PathRecord> tagBinds) {
        Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();

        String regex = "^data/([^/]+)/tags/([^/]+)/(.+)\\.json$";
        Pattern pattern = Pattern.compile(regex);

        while (zipEntries.hasMoreElements()) {
            ZipEntry zipEntry = zipEntries.nextElement();

            Matcher matcher = pattern.matcher(zipEntry.getName());

            if (!zipEntry.isDirectory() && matcher.find()) {
                String namespace = matcher.group(1);
                String tagType = matcher.group(2);
                String tagName = matcher.group(3);

                PathRecord.TagType type = PathRecord.TagType.parseName(tagType);
                if (type == null)
                    continue;

                NamespacedKey tagKey = new NamespacedKey(namespace, tagName);
                LOGGER.warning(tagKey.asString());
                try (InputStream is = zipFile.getInputStream(zipEntry)) {
                    ObjectMapper objectMapper = new ObjectMapper();
                    JsonNode jsonNode = objectMapper.readTree(is);

                    JsonNode valuesNode = jsonNode.get("values");
                    for (JsonNode valueNode : valuesNode) {
                        String plainKey = valueNode.asText();
                        if (plainKey == null)
                            continue;
                        boolean isTag = plainKey.startsWith("#");
                        NamespacedKey itemKey = NamespacedKey.fromString(plainKey.substring(isTag ? 1 : 0));
                        if (itemKey == null)
                            continue;
                        if (isTag)
                            tagBinds.accept(new PathRecord(type, tagKey, itemKey));
                        else
                            addItemTag(tagKey, itemKey);
                    }
                } catch (IOException e) {
                    LOGGER.warning(e.getMessage());
                } catch (NullPointerException ignored) {
                    warnFailLoad("%s:tags/%s/%s", namespace, tagType, tagName);
                }
            }
        }
    }

    private static void warnFailLoad(String id, Object... args) {
        LOGGER.info("\u001B[38;2;255;85;85m%s failed to load\u001B[0m".formatted(id.formatted(args)));
    }

}
