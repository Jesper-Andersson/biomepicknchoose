package com.github.Jesper_Andersson.biomepicknchoose.client.gui;

import com.github.Jesper_Andersson.biomepicknchoose.BiomePickNChoose;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Named sets of biome on/off settings in {@code config/biomepicknchoose/presets}. Each file maps biome ids to true for enabled,
 * and keeps ids from mods that aren't installed right now.
 */
public final class BiomePresets {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String EXTENSION = ".json";

    private BiomePresets() {}

    public static Path dir() {
        return FMLPaths.CONFIGDIR.get().resolve(BiomePickNChoose.MODID).resolve("presets");
    }

    /** The preset name as stored on disk, or null for a blank name. */
    @Nullable
    public static String fileName(String name) {
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.replaceAll("[^A-Za-z0-9 _.-]", "_");
    }

    public static List<String> list() {
        Path dir = dir();
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .map(file -> file.getFileName().toString())
                    .filter(file -> file.endsWith(EXTENSION))
                    .map(file -> file.substring(0, file.length() - EXTENSION.length()))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException e) {
            LOGGER.warn("Couldn't list biome presets in {}", dir, e);
            return List.of();
        }
    }

    public static boolean exists(String name) {
        return Files.isRegularFile(file(name));
    }

    public static Map<ResourceLocation, Boolean> load(String name) {
        Path file = file(name);
        Map<ResourceLocation, Boolean> values = new HashMap<>();
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject biomes = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("biomes");
            for (Map.Entry<String, JsonElement> entry : biomes.entrySet()) {
                ResourceLocation id = ResourceLocation.tryParse(entry.getKey());
                if (id != null && entry.getValue().isJsonPrimitive()) values.put(id, entry.getValue().getAsBoolean());
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Couldn't read biome preset {}", file, e);
            return Map.of();
        }
        return values;
    }

    /** Writes the preset, keeping entries already in the file for biomes that aren't in values. */
    public static void save(String name, Map<ResourceLocation, Boolean> values) {
        Path file = file(name);
        Map<String, Boolean> merged = new TreeMap<>();
        if (Files.isRegularFile(file)) load(name).forEach((id, enabled) -> merged.put(id.toString(), enabled));
        values.forEach((id, enabled) -> merged.put(id.toString(), enabled));
        JsonObject biomes = new JsonObject();
        merged.forEach(biomes::addProperty);
        JsonObject root = new JsonObject();
        root.add("biomes", biomes);
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(root, writer);
            }
        } catch (IOException e) {
            LOGGER.warn("Couldn't write biome preset {}", file, e);
        }
    }

    public static void delete(String name) {
        Path file = file(name);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.warn("Couldn't delete biome preset {}", file, e);
        }
    }

    /** Opens the presets folder in the system file browser, creating it first if needed. */
    public static void openDir() {
        Path dir = dir();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOGGER.warn("Couldn't create biome preset folder {}", dir, e);
        }
        Util.getPlatform().openPath(dir);
    }

    private static Path file(String name) {
        return dir().resolve(name + EXTENSION);
    }
}
