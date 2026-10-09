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
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Finds the biomes of installed mods from their data files, so the menu can list them before any world was loaded. A
 * biome counts for a vanilla dimension when a mod defines it, and it is either in that dimension's biome tag, or listed
 * in a replaced dimension or biome parameter list. Overworld tagged biomes that are also in a Nether or End tag don't
 * count for the overworld. TerraBlender based mods like Biomes O' Plenty tag their biomes, and datapack style mods like
 * Terralith replace the overworld. Dimensions that mods add are found from their dimension files, with the biomes
 * those list. Loading a world is still the complete list, since a mod can also place biomes or add dimensions from
 * code.
 */
public final class ModBiomeScan {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final List<String> TAG_NAMESPACES = List.of("minecraft", "c", "forge", "neoforge");
    private static final Map<BiomeDimension, List<String>> TAGS = Map.of(
            BiomeDimension.OVERWORLD, List.of("is_overworld"),
            BiomeDimension.NETHER, List.of("is_nether"),
            BiomeDimension.END, List.of("is_end", "is_the_end"));
    // Ocean and river biomes, which the menu colours blue
    private static final List<String> WATER_TAGS = List.of("is_ocean", "is_deep_ocean", "is_river", "is_aquatic");
    private static final List<String> ALL_TAGS = Stream.concat(TAGS.values().stream().flatMap(List::stream), WATER_TAGS.stream()).toList();
    // Replaced vanilla biome parameter lists. Dimension files, vanilla or not, are found in every namespace
    private static final Map<BiomeDimension, String> PARAMETER_LIST_FILES = Map.of(
            BiomeDimension.OVERWORLD, "data/minecraft/worldgen/multi_noise_biome_source_parameter_list/overworld.json",
            BiomeDimension.NETHER, "data/minecraft/worldgen/multi_noise_biome_source_parameter_list/nether.json");

    private record Found(Map<BiomeDimension, Set<Identifier>> dimensions, Set<Identifier> water) {}

    // The mods can't change while the game runs, so this is only done once
    private static volatile Found found;

    private ModBiomeScan() {}

    public static Set<Identifier> biomes(BiomeDimension dimension) {
        return found().dimensions().getOrDefault(dimension, Set.of());
    }

    /** The vanilla dimensions, and the ones installed mods add in dimension files. */
    public static Set<BiomeDimension> dimensions() {
        return found().dimensions().keySet();
    }

    /** Biomes of installed mods in an ocean or river tag. */
    public static Set<Identifier> waterBiomes() {
        return found().water();
    }

    private static Found found() {
        Found result = found;
        if (result == null) {
            long start = System.nanoTime();
            result = scan();
            found = result;
            Map<BiomeDimension, Set<Identifier>> biomes = result.dimensions();
            LOGGER.info("Found {} overworld, {} Nether, {} End biomes and {} other dimensions in the files of installed mods in {} ms",
                    biomes.get(BiomeDimension.OVERWORLD).size(), biomes.get(BiomeDimension.NETHER).size(),
                    biomes.get(BiomeDimension.END).size(), biomes.size() - BiomeDimension.VANILLA.size(),
                    (System.nanoTime() - start) / 1_000_000);
        }
        return result;
    }

    private static Found scan() {
        Set<Identifier> defined = new HashSet<>();
        // Tag id to its entries, from every mod: biome ids, and other tags as "#namespace:path"
        Map<String, List<String>> tags = new HashMap<>();
        Map<BiomeDimension, Set<Identifier>> listed = new TreeMap<>();
        for (BiomeDimension dimension : BiomeDimension.VANILLA) listed.put(dimension, new HashSet<>());
        for (Path root : Services.PLATFORM.getModRoots()) {
            try {
                scanRoot(root, defined, tags, listed);
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Couldn't look for biomes in {}", root, e);
            }
        }

        Map<BiomeDimension, Set<Identifier>> tagged = new HashMap<>();
        for (BiomeDimension dimension : BiomeDimension.VANILLA) tagged.put(dimension, resolveTags(tags, TAGS.get(dimension)));
        tagged.get(BiomeDimension.OVERWORLD).removeAll(tagged.get(BiomeDimension.NETHER));
        tagged.get(BiomeDimension.OVERWORLD).removeAll(tagged.get(BiomeDimension.END));

        Map<BiomeDimension, Set<Identifier>> result = new TreeMap<>();
        for (Map.Entry<BiomeDimension, Set<Identifier>> entry : listed.entrySet()) {
            Set<Identifier> biomes = new HashSet<>(entry.getValue());
            biomes.addAll(tagged.getOrDefault(entry.getKey(), Set.of()));
            biomes.removeIf(id -> !exists(id, defined));
            result.put(entry.getKey(), Set.copyOf(biomes));
        }

        Set<Identifier> water = resolveTags(tags, WATER_TAGS);
        water.removeIf(id -> !exists(id, defined));
        return new Found(result, Set.copyOf(water));
    }

    // Tags can name biomes of mods that aren't installed, as optional entries. Vanilla biomes always exist
    private static boolean exists(Identifier biome, Set<Identifier> defined) {
        return defined.contains(biome) || biome.getNamespace().equals(Identifier.DEFAULT_NAMESPACE);
    }

    // The biomes in the named tags, in each of the namespaces mods put shared tags in
    private static Set<Identifier> resolveTags(Map<String, List<String>> tags, List<String> tagNames) {
        Set<String> ids = new HashSet<>();
        for (String namespace : TAG_NAMESPACES) {
            for (String tag : tagNames) resolve(tags, namespace + ":" + tag, ids, new HashSet<>());
        }
        return Identifiers.parseAll(ids);
    }

    private static void scanRoot(Path root, Set<Identifier> defined, Map<String, List<String>> tags,
                                 Map<BiomeDimension, Set<Identifier>> listed) throws IOException {
        Path data = root.resolve("data");
        if (!Files.isDirectory(data)) return;
        try (Stream<Path> namespaces = Files.list(data)) {
            for (Path namespaceDir : namespaces.toList()) {
                String namespace = fileName(namespaceDir);
                Path biomeDir = namespaceDir.resolve("worldgen/biome");
                jsonFiles(namespace, namespaceDir.resolve("worldgen/biome")).forEach((id, file) -> defined.add(id));
                for (Map.Entry<Identifier, Path> dimension : jsonFiles(namespace, namespaceDir.resolve("dimension")).entrySet()) {
                    Set<Identifier> biomes = listed.computeIfAbsent(new BiomeDimension(dimension.getKey()), key -> new HashSet<>());
                    collectDimension(read(dimension.getValue()), biomes);
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
        for (Map.Entry<BiomeDimension, String> entry : PARAMETER_LIST_FILES.entrySet()) {
            Path file = root.resolve(entry.getValue());
            if (Files.isRegularFile(file)) collectBiomes(read(file), listed.get(entry.getKey()));
        }
    }

    // The JSON files in a data folder, by the id they define: the namespace, and their path without ".json"
    private static Map<Identifier, Path> jsonFiles(String namespace, Path dir) throws IOException {
        Map<Identifier, Path> files = new HashMap<>();
        if (!Files.isDirectory(dir)) return files;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path file : walk.filter(file -> fileName(file).endsWith(".json")).toList()) {
                String path = dir.relativize(file).toString().replace('\\', '/');
                Identifier id = Identifier.tryBuild(namespace, path.substring(0, path.length() - ".json".length()));
                if (id != null) files.put(id, file);
            }
        }
        return files;
    }

    // The biomes a dimension file names, and the vanilla ones of a vanilla biome source preset it uses. Only looks at
    // the biome source, since the dimension's own "type" names a dimension type, like minecraft:overworld
    private static void collectDimension(JsonObject dimension, Set<Identifier> biomes) {
        JsonElement generator = dimension.get("generator");
        if (generator == null || !generator.isJsonObject()) return;
        JsonElement source = generator.getAsJsonObject().get("biome_source");
        if (source == null) return;
        collectBiomes(source, biomes);
        Set<String> values = new HashSet<>();
        collectStrings(source, Set.of("preset", "type"), values);
        if (values.contains("minecraft:overworld")) biomes.addAll(BiomeDimension.OVERWORLD.vanilla());
        if (values.contains("minecraft:nether")) biomes.addAll(BiomeDimension.NETHER.vanilla());
        if (values.contains("minecraft:the_end")) biomes.addAll(BiomeDimension.END.vanilla());
    }

    // Adds the string values of the given keys anywhere in the file
    private static void collectStrings(JsonElement element, Set<String> keys, Set<String> values) {
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if (keys.contains(entry.getKey()) && value.isJsonPrimitive()) values.add(value.getAsString());
                else collectStrings(value, keys, values);
            }
        } else if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(value -> collectStrings(value, keys, values));
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
