package com.github.Jesper_Andersson.biomepicknchoose.common;

import com.github.Jesper_Andersson.biomepicknchoose.platform.Services;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * {@code config/biomepicknchoose-known-biomes.json}: what the last loaded world's dimensions can place, and which of
 * those biomes are caves or water, so the menu can show them before any world is loaded in this game. Lists are keyed
 * by {@link BiomeDimension#key()}, plus {@value #CAVES} and {@value #WATER}.
 */
public final class KnownBiomesFile {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final String CAVES = "caves";
    private static final String WATER = "water";
    // Written by older versions, for overworld biomes only
    private static final String LEGACY_OVERWORLD = "biomes";

    /** The lists as read from the file, empty if there is none yet or it can't be read. */
    public record Contents(Map<BiomeDimension, Set<Identifier>> dimensions, Set<Identifier> caves, Set<Identifier> water) {
        static final Contents EMPTY = new Contents(Map.of(), Set.of(), Set.of());

        public boolean isEmpty() {
            return dimensions.isEmpty();
        }
    }

    private KnownBiomesFile() {}

    public static Path path() {
        return Services.PLATFORM.getConfigDir().resolve("biomepicknchoose-known-biomes.json");
    }

    public static Contents read() {
        Path file = path();
        if (!Files.isRegularFile(file)) return Contents.EMPTY;
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonElement root = JsonParser.parseReader(reader);
            // The oldest versions wrote a plain array of overworld biomes
            if (root.isJsonArray()) return new Contents(Map.of(BiomeDimension.OVERWORLD, ids(root.getAsJsonArray())), Set.of(), Set.of());

            Map<BiomeDimension, Set<Identifier>> dimensions = new TreeMap<>();
            Map<String, Set<Identifier>> others = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonArray()) continue;
                Set<Identifier> ids = ids(entry.getValue().getAsJsonArray());
                String key = entry.getKey().equals(LEGACY_OVERWORLD) ? BiomeDimension.OVERWORLD.key() : entry.getKey();
                BiomeDimension dimension = BiomeDimension.byKey(key);
                if (dimension != null) dimensions.put(dimension, ids);
                else others.put(key, ids);
            }
            return new Contents(dimensions, others.getOrDefault(CAVES, Set.of()), others.getOrDefault(WATER, Set.of()));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Couldn't read {}", file, e);
            return Contents.EMPTY;
        }
    }

    /** Saves what the server's dimensions can place. Called once a server has loaded its worlds. */
    public static void write(MinecraftServer server) {
        JsonObject root = new JsonObject();
        BiomeCatalog.serverGenerating(server).forEach((dimension, ids) -> root.add(dimension.key(), json(ids)));
        root.add(CAVES, json(CaveBiomes.fromServer(server)));
        root.add(WATER, json(WaterBiomes.fromServer(server)));
        Path file = path();
        try (Writer writer = Files.newBufferedWriter(file)) {
            GSON.toJson(root, writer);
        } catch (IOException e) {
            LOGGER.warn("Couldn't write {}", file, e);
        }
    }

    private static Set<Identifier> ids(JsonArray array) {
        List<String> ids = new ArrayList<>();
        array.forEach(element -> ids.add(element.getAsString()));
        return Identifiers.parseAll(ids);
    }

    private static JsonArray json(Set<Identifier> ids) {
        JsonArray array = new JsonArray();
        ids.forEach(id -> array.add(id.toString()));
        return array;
    }
}
