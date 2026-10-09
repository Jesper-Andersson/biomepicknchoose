package com.github.Jesper_Andersson.biomepicknchoose.common;

import com.github.Jesper_Andersson.biomepicknchoose.platform.Services;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Finds the overworld biomes of installed mods from their data files, so the menu can list them before any world was
 * loaded. A biome counts when a mod defines it, and it is either in an overworld biome tag but no Nether or End tag,
 * or listed in a replaced overworld dimension or biome parameter list. TerraBlender based mods like Biomes O' Plenty
 * tag their biomes, and datapack style mods like Terralith replace the overworld. Loading a world is still the
 * complete list, since a mod can also place biomes from code.
 */
public final class ModBiomeScan {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<String> TAG_NAMESPACES = List.of("minecraft", "c", "forge", "neoforge");
    private static final String OVERWORLD_TAG = "is_overworld";
    private static final List<String> OTHER_TAGS = List.of("is_nether", "is_end", "is_the_end");
    private static final List<String> ALL_TAGS = Stream.concat(Stream.of(OVERWORLD_TAG), OTHER_TAGS.stream()).toList();
    private static final List<String> OVERWORLD_FILES = List.of(
            "data/minecraft/dimension/overworld.json",
            "data/minecraft/worldgen/multi_noise_biome_source_parameter_list/overworld.json");

    // The mods can't change while the game runs, so this is only done once
    private static volatile Set<Identifier> found;

    private ModBiomeScan() {}

    public static Set<Identifier> overworldBiomes() {
        Set<Identifier> biomes = found;
        if (biomes == null) {
            long start = System.nanoTime();
            biomes = Set.copyOf(scan());
            found = biomes;
            LOGGER.info("Found {} overworld biomes in the files of installed mods in {} ms", biomes.size(), (System.nanoTime() - start) / 1_000_000);
        }
        return biomes;
    }

    private static Set<Identifier> scan() {
        Set<Identifier> defined = new HashSet<>();
        // Tag id to its entries, from every mod: biome ids, and other tags as "#namespace:path"
        Map<String, List<String>> tags = new HashMap<>();
        Set<Identifier> listed = new HashSet<>();
        for (Path root : Services.PLATFORM.getModRoots()) {
            try {
                scanRoot(root, defined, tags, listed);
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Couldn't look for biomes in {}", root, e);
            }
        }

        Set<String> overworld = new HashSet<>();
        Set<String> other = new HashSet<>();
        for (String namespace : TAG_NAMESPACES) {
            resolve(tags, namespace + ":" + OVERWORLD_TAG, overworld, new HashSet<>());
            for (String tag : OTHER_TAGS) resolve(tags, namespace + ":" + tag, other, new HashSet<>());
        }
        overworld.removeAll(other);

        Set<Identifier> biomes = new HashSet<>();
        for (String id : overworld) {
            Identifier location = Identifier.tryParse(id);
            if (location != null) biomes.add(location);
        }
        biomes.addAll(listed);
        // Entries for mods that aren't installed, which tags can name as optional
        biomes.retainAll(defined);
        return biomes;
    }

    private static void scanRoot(Path root, Set<Identifier> defined, Map<String, List<String>> tags, Set<Identifier> listed) throws IOException {
        Path data = root.resolve("data");
        if (!Files.isDirectory(data)) return;
        try (Stream<Path> namespaces = Files.list(data)) {
            for (Path namespaceDir : namespaces.toList()) {
                String namespace = fileName(namespaceDir);
                Path biomeDir = namespaceDir.resolve("worldgen/biome");
                if (Files.isDirectory(biomeDir)) {
                    try (Stream<Path> files = Files.walk(biomeDir)) {
                        files.filter(file -> fileName(file).endsWith(".json")).forEach(file -> {
                            String path = biomeDir.relativize(file).toString().replace('\\', '/');
                            Identifier id = Identifier.tryBuild(namespace, path.substring(0, path.length() - ".json".length()));
                            if (id != null) defined.add(id);
                        });
                    }
                }
            }
        }
        for (String namespace : TAG_NAMESPACES) {
            for (String tag : ALL_TAGS) {
                Path file = data.resolve(namespace).resolve("tags/worldgen/biome").resolve(tag + ".json");
                if (!Files.isRegularFile(file)) continue;
                JsonArray values = read(file).getAsJsonArray("values");
                if (values == null) continue;
                List<String> entries = tags.computeIfAbsent(namespace + ":" + tag, key -> new ArrayList<>());
                for (JsonElement value : values) {
                    // Either "id" or {"id": "id", "required": false}
                    if (value.isJsonPrimitive()) entries.add(value.getAsString());
                    else if (value.isJsonObject() && value.getAsJsonObject().has("id")) entries.add(value.getAsJsonObject().get("id").getAsString());
                }
            }
        }
        for (String name : OVERWORLD_FILES) {
            Path file = root.resolve(name);
            if (Files.isRegularFile(file)) collectBiomes(read(file), listed);
        }
    }

    // Adds every "biome" string anywhere in the file, which is where both formats name the biomes they place
    private static void collectBiomes(JsonElement element, Set<Identifier> biomes) {
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if (entry.getKey().equals("biome") && value.isJsonPrimitive()) {
                    Identifier id = Identifier.tryParse(value.getAsString());
                    if (id != null) biomes.add(id);
                } else {
                    collectBiomes(value, biomes);
                }
            }
        } else if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(value -> collectBiomes(value, biomes));
        }
    }

    // Adds the biomes in a tag, following tags it includes
    private static void resolve(Map<String, List<String>> tags, String tag, Set<String> biomes, Set<String> seen) {
        if (!seen.add(tag)) return;
        for (String entry : tags.getOrDefault(tag, List.of())) {
            if (entry.startsWith("#")) resolve(tags, entry.substring(1), biomes, seen);
            else biomes.add(entry);
        }
    }

    private static JsonObject read(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static String fileName(Path path) {
        String name = path.getFileName().toString();
        // Folders inside jars can end with a slash
        return name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
    }
}
