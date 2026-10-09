package com.github.Jesper_Andersson.biomepicknchoose.common;

import com.github.Jesper_Andersson.biomepicknchoose.Constants;
import com.github.Jesper_Andersson.biomepicknchoose.platform.Services;
import com.google.gson.Gson;
import com.mojang.logging.LogUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Which biomes of the overworld, the Nether and the End are turned off. The config is read when a world starts and
 * again on {@code /reload}, so changes apply to chunks generated after that. Chunks that already exist keep their
 * biomes.
 * <p>
 * On NeoForge, other mods can list their overworld biomes in the menu before any world was loaded by sending an IMC
 * message to {@value Constants#MOD_ID} with method {@value #REGISTER_BIOMES} and a {@code Collection} of biome
 * {@code Identifier}s, {@code ResourceKey}s or id strings, from {@code InterModEnqueueEvent}.
 */
public final class BiomeToggles {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    public static final String REGISTER_BIOMES = "register_biomes";

    // Biome ids sent by other mods, through IMC on NeoForge
    private static volatile Set<Identifier> registered = Set.of();

    private static volatile Set<ResourceKey<Biome>> disabled = Set.of();
    // Bumped on every snapshot, so biome sources know to rebuild their filtered parameter lists
    private static volatile int version;

    private BiomeToggles() {}

    // Synchronized, since version++ isn't atomic
    public static synchronized void snapshot() {
        Set<ResourceKey<Biome>> keys = new HashSet<>();
        for (String id : BiomeConfig.disabledBiomes()) {
            Identifier location = Identifier.tryParse(id);
            if (location != null) keys.add(ResourceKey.create(Registries.BIOME, location));
        }
        disabled = Set.copyOf(keys);
        version++;
        LOGGER.info("Disabled biomes: {}", keys.stream().map(ResourceKey::identifier).sorted().toList());
    }

    public static int version() {
        return version;
    }

    public static boolean anyDisabled() {
        return !disabled.isEmpty();
    }

    /** Whether the biome is turned off in the world that is running. Stable API for other mods. */
    public static boolean isDisabled(Holder<Biome> biome) {
        Set<ResourceKey<Biome>> keys = disabled;
        return !keys.isEmpty() && biome.unwrapKey().map(keys::contains).orElse(false);
    }

    /** Whether the biome is turned off in the world that is running. Stable API for other mods. */
    public static boolean isDisabled(ResourceKey<Biome> biome) {
        return disabled.contains(biome);
    }

    /**
     * The biomes each dimension of the last loaded world can place, and its cave biomes, so the menu can list biomes
     * from other mods: {@code {"overworld": [...], "nether": [...], "end": [...], "caves": [...], "water": [...]}}.
     */
    public static Path knownBiomesFile() {
        return Services.PLATFORM.getConfigDir().resolve("biomepicknchoose-known-biomes.json");
    }

    /**
     * Vanilla, IMC-registered (overworld only), found in mod files and cached biome ids of each dimension: the vanilla
     * ones, the ones mods add in dimension files, and the ones of the last loaded world. Disabled biomes that are in
     * none of them are listed in the overworld, so a disabled biome can always be turned back on.
     */
    public static Map<BiomeDimension, Set<Identifier>> knownBiomes() {
        Map<String, Set<Identifier>> cached = readKnownBiomes();
        Set<BiomeDimension> dimensions = new TreeSet<>(BiomeDimension.VANILLA);
        dimensions.addAll(ModBiomeScan.dimensions());
        dimensions.addAll(cachedDimensions(cached).keySet());
        Map<BiomeDimension, Set<Identifier>> known = new TreeMap<>();
        for (BiomeDimension dimension : dimensions) {
            Set<Identifier> ids = dimension.vanilla();
            if (dimension.equals(BiomeDimension.OVERWORLD)) ids.addAll(registered);
            ids.addAll(ModBiomeScan.biomes(dimension));
            ids.addAll(cached.getOrDefault(dimension.key(), Set.of()));
            known.put(dimension, ids);
        }
        addUnlistedDisabled(known);
        return known;
    }


    /** The known biomes of every dimension together, for saving the config. */
    public static Set<Identifier> allKnown(Map<BiomeDimension, Set<Identifier>> known) {
        Set<Identifier> ids = new TreeSet<>();
        known.values().forEach(ids::addAll);
        return ids;
    }

    /**
     * The biomes each dimension of the last loaded world can place. Dimensions the known biomes file doesn't list,
     * like all of them before any world was loaded, are left out, since then it isn't known what they place.
     */
    public static Map<BiomeDimension, Set<Identifier>> generatingBiomes() {
        return cachedDimensions(readKnownBiomes());
    }

    // The dimension lists in the known biomes file, leaving out the others, like the cave biomes
    private static Map<BiomeDimension, Set<Identifier>> cachedDimensions(Map<String, Set<Identifier>> cached) {
        Map<BiomeDimension, Set<Identifier>> dimensions = new TreeMap<>();
        cached.forEach((key, ids) -> {
            BiomeDimension dimension = BiomeDimension.byKey(key);
            if (dimension != null) dimensions.put(dimension, ids);
        });
        return dimensions;
    }

    /**
     * Vanilla ocean and river biomes, the ones mods tag as such, and the ones seen in the last loaded world, so the menu
     * can mark them.
     */
    public static Set<Identifier> knownWaterBiomes() {
        Set<Identifier> ids = new TreeSet<>(WaterBiomes.vanilla());
        ids.addAll(ModBiomeScan.waterBiomes());
        ids.addAll(readKnownBiomes().getOrDefault("water", Set.of()));
        return ids;
    }

    /** Vanilla cave biomes and the ones seen in the last loaded world, so the menu can mark them. */
    public static Set<Identifier> knownCaveBiomes() {
        Set<Identifier> ids = new TreeSet<>(CaveBiomes.vanilla());
        ids.addAll(readKnownBiomes().getOrDefault("caves", Set.of()));
        return ids;
    }

    // The lists in the known biomes file. Older versions wrote only the overworld biomes, as a plain array or as
    // {"biomes": [...], "caves": [...]}
    private static Map<String, Set<Identifier>> readKnownBiomes() {
        Map<String, Set<Identifier>> lists = new HashMap<>();
        Path file = knownBiomesFile();
        if (!Files.isRegularFile(file)) return lists;
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (root.isJsonArray()) {
                lists.put(BiomeDimension.OVERWORLD.key(), ids(root.getAsJsonArray()));
            } else {
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject().entrySet()) {
                    String key = entry.getKey().equals("biomes") ? BiomeDimension.OVERWORLD.key() : entry.getKey();
                    if (entry.getValue().isJsonArray()) lists.put(key, ids(entry.getValue().getAsJsonArray()));
                }
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Couldn't read {}", file, e);
        }
        return lists;
    }

    private static Set<Identifier> ids(JsonArray array) {
        Set<Identifier> ids = new TreeSet<>();
        for (JsonElement element : array) {
            Identifier location = Identifier.tryParse(element.getAsString());
            if (location != null) ids.add(location);
        }
        return ids;
    }

    private static void addUnlistedDisabled(Map<BiomeDimension, Set<Identifier>> known) {
        Set<Identifier> listed = allKnown(known);
        for (String id : BiomeConfig.disabledBiomes()) {
            Identifier location = Identifier.tryParse(id);
            if (location != null && !listed.contains(location)) known.get(BiomeDimension.OVERWORLD).add(location);
        }
    }

    /**
     * Biomes each dimension of the server can place, with vanilla, IMC-registered and currently disabled biomes. Used
     * instead of {@link #knownBiomes()} when an operator edits the server's config from a client.
     */
    public static Map<BiomeDimension, Set<Identifier>> serverKnownBiomes(MinecraftServer server) {
        Set<BiomeDimension> dimensions = new TreeSet<>(BiomeDimension.VANILLA);
        dimensions.addAll(serverDimensions(server));
        Map<BiomeDimension, Set<Identifier>> known = new TreeMap<>();
        for (BiomeDimension dimension : dimensions) {
            Set<Identifier> ids = dimension.vanilla();
            if (dimension.equals(BiomeDimension.OVERWORLD)) ids.addAll(registered);
            ids.addAll(possibleBiomes(server, dimension));
            known.put(dimension, ids);
        }
        addUnlistedDisabled(known);
        return known;
    }

    /** Biomes each dimension of the server can place. */
    public static Map<BiomeDimension, Set<Identifier>> serverGeneratingBiomes(MinecraftServer server) {
        Map<BiomeDimension, Set<Identifier>> generating = new TreeMap<>();
        for (BiomeDimension dimension : serverDimensions(server)) generating.put(dimension, possibleBiomes(server, dimension));
        return generating;
    }

    /** Every dimension the server has loaded, vanilla first. */
    public static Set<BiomeDimension> serverDimensions(MinecraftServer server) {
        Set<BiomeDimension> dimensions = new TreeSet<>();
        server.levelKeys().forEach(level -> dimensions.add(BiomeDimension.of(level)));
        return dimensions;
    }

    // Empty if the server has no such level
    private static Set<Identifier> possibleBiomes(MinecraftServer server, BiomeDimension dimension) {
        Set<Identifier> ids = new TreeSet<>();
        ServerLevel level = server.getLevel(dimension.level());
        if (level == null) return ids;
        level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes().stream()
                .flatMap(biome -> biome.unwrapKey().stream())
                .forEach(key -> ids.add(key.identifier()));
        return ids;
    }

    /** Biome ids other mods asked to list in the menu, replacing any set before. */
    public static void setRegistered(Set<Identifier> ids) {
        registered = Set.copyOf(ids);
    }

    /** Called by each loader when a server is about to load its worlds. */
    public static void onServerAboutToStart() {
        snapshot();
    }

    /** Called by each loader after {@code /reload}, so config changes apply without restarting. */
    public static void onReload(MinecraftServer server) {
        snapshot();
        SmokeTest.onReload(server);
    }

    /** Called by each loader once a server has loaded its worlds. */
    public static void onServerStarted(MinecraftServer server) {
        JsonObject root = new JsonObject();
        serverGeneratingBiomes(server).forEach((dimension, ids) -> root.add(dimension.key(), json(ids)));
        root.add("caves", json(CaveBiomes.fromServer(server)));
        root.add("water", json(WaterBiomes.fromServer(server)));
        Path file = knownBiomesFile();
        try (Writer writer = Files.newBufferedWriter(file)) {
            GSON.toJson(root, writer);
        } catch (IOException e) {
            LOGGER.warn("Couldn't write {}", file, e);
        }
        SmokeTest.onServerStarted(server);
    }

    private static JsonArray json(Set<Identifier> ids) {
        JsonArray array = new JsonArray();
        ids.forEach(id -> array.add(id.toString()));
        return array;
    }
}
