package com.github.Jesper_Andersson.biomepicknchoose.common;

import com.github.Jesper_Andersson.biomepicknchoose.Constants;
import com.github.Jesper_Andersson.biomepicknchoose.platform.Services;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The config in {@code config/biomepicknchoose.json}, the same file on both loaders, clients and servers. It has the
 * same format as a preset, so a preset can be copied over it: {@code {"biomes": {"minecraft:plains": false}}} maps
 * biome ids to true for enabled, and every biome mapped to false is disabled. Read again on every access, so edits
 * made by hand apply on the next {@code /reload} or world load.
 */
public final class BiomeConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private BiomeConfig() {}

    public static Path file() {
        return Services.PLATFORM.getConfigDir().resolve(Constants.MOD_ID + ".json");
    }

    /**
     * Called by each loader on startup. Writes a config listing the vanilla biomes if there is none yet, so it can be
     * found and edited, with the biomes disabled in the config of an older version of the mod.
     */
    public static synchronized void init() {
        if (Files.exists(file())) return;
        Map<ResourceLocation, Boolean> values = new HashMap<>();
        MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD.usedBiomes().forEach(key -> values.put(key.location(), true));
        List<String> legacy = Services.PLATFORM.migrateLegacyConfig();
        for (String id : legacy) {
            ResourceLocation location = ResourceLocation.tryParse(id);
            if (location != null) values.put(location, false);
        }
        if (!legacy.isEmpty()) LOGGER.info("Moved the disabled biomes from the old config to {}", file());
        write(file(), values);
    }

    /** Biome ids that won't generate. */
    public static synchronized List<String> disabledBiomes() {
        return read(file()).entrySet().stream()
                .filter(entry -> !entry.getValue())
                .map(entry -> entry.getKey().toString())
                .sorted()
                .toList();
    }

    /** Writes every known biome as enabled unless it is in disabled, keeping entries for biomes that aren't known. */
    public static synchronized void save(Set<ResourceLocation> known, Collection<String> disabled) {
        Map<ResourceLocation, Boolean> values = new HashMap<>();
        known.forEach(id -> values.put(id, true));
        for (String id : disabled) {
            ResourceLocation location = ResourceLocation.tryParse(id);
            if (location != null) values.put(location, false);
        }
        write(file(), values);
    }

    /** Reads a config or preset file, or nothing if it is missing or broken. */
    public static Map<ResourceLocation, Boolean> read(Path file) {
        Map<ResourceLocation, Boolean> values = new HashMap<>();
        if (!Files.isRegularFile(file)) return values;
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonObject biomes = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("biomes");
            if (biomes == null) return values;
            for (Map.Entry<String, JsonElement> entry : biomes.entrySet()) {
                ResourceLocation id = ResourceLocation.tryParse(entry.getKey());
                if (id != null && entry.getValue().isJsonPrimitive()) values.put(id, entry.getValue().getAsBoolean());
                else LOGGER.warn("Ignoring invalid entry {} in {}", entry.getKey(), file);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Couldn't read {}", file, e);
        }
        return values;
    }

    /** Writes a config or preset file, keeping entries already in it for biomes that aren't in values. */
    public static void write(Path file, Map<ResourceLocation, Boolean> values) {
        Map<String, Boolean> merged = new TreeMap<>();
        read(file).forEach((id, enabled) -> merged.put(id.toString(), enabled));
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
            LOGGER.warn("Couldn't write {}", file, e);
        }
    }
}
