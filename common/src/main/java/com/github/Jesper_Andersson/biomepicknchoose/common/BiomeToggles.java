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
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which overworld biomes are turned off. The config is read when a world starts and again on {@code /reload}, so
 * changes apply to chunks generated after that. Chunks that already exist keep their biomes.
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
        LOGGER.info("Disabled overworld biomes: {}", keys.stream().map(ResourceKey::identifier).sorted().toList());
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

    /** Overworld biomes from the last loaded world, so the menu can list biomes from other mods. */
    public static Path knownBiomesFile() {
        return Services.PLATFORM.getConfigDir().resolve("biomepicknchoose-known-biomes.json");
    }

    /**
     * Vanilla, IMC-registered, found in mod files, cached and currently disabled biome ids, so a disabled biome can
     * always be turned back on.
     */
    public static Set<Identifier> knownBiomes() {
        Set<Identifier> ids = new TreeSet<>();
        MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD.usedBiomes().forEach(key -> ids.add(key.identifier()));
        ids.addAll(registered);
        ids.addAll(ModBiomeScan.overworldBiomes());
        ids.addAll(readKnownBiomes("biomes"));
        for (String id : BiomeConfig.disabledBiomes()) {
            Identifier location = Identifier.tryParse(id);
            if (location != null) ids.add(location);
        }
        return ids;
    }

    /** Vanilla cave biomes and the ones seen in the last loaded world, so the menu can mark them. */
    public static Set<Identifier> knownCaveBiomes() {
        Set<Identifier> ids = new TreeSet<>(CaveBiomes.vanilla());
        ids.addAll(readKnownBiomes("caves"));
        return ids;
    }

    // One list from the known biomes file: {"biomes": [...], "caves": [...]}. Older versions wrote only the biomes,
    // as a plain array
    private static Set<Identifier> readKnownBiomes(String list) {
        Set<Identifier> ids = new TreeSet<>();
        Path file = knownBiomesFile();
        if (!Files.isRegularFile(file)) return ids;
        try (Reader reader = Files.newBufferedReader(file)) {
            JsonElement root = JsonParser.parseReader(reader);
            JsonArray array = root.isJsonArray() ? (list.equals("biomes") ? root.getAsJsonArray() : null)
                    : root.getAsJsonObject().getAsJsonArray(list);
            if (array == null) return ids;
            for (JsonElement element : array) {
                Identifier location = Identifier.tryParse(element.getAsString());
                if (location != null) ids.add(location);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Couldn't read {}", file, e);
        }
        return ids;
    }

    /**
     * Biomes the server's overworld can place, with vanilla, IMC-registered and currently disabled biomes. Used instead
     * of {@link #knownBiomes()} when an operator edits the server's config from a client.
     */
    public static Set<Identifier> serverKnownBiomes(MinecraftServer server) {
        Set<Identifier> ids = new TreeSet<>();
        MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD.usedBiomes().forEach(key -> ids.add(key.identifier()));
        ids.addAll(registered);
        server.overworld().getChunkSource().getGenerator().getBiomeSource().possibleBiomes().stream()
                .flatMap(biome -> biome.unwrapKey().stream())
                .forEach(key -> ids.add(key.identifier()));
        for (String id : BiomeConfig.disabledBiomes()) {
            Identifier location = Identifier.tryParse(id);
            if (location != null) ids.add(location);
        }
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
        ServerLevel overworld = server.overworld();
        JsonArray ids = new JsonArray();
        overworld.getChunkSource().getGenerator().getBiomeSource().possibleBiomes().stream()
                .flatMap(biome -> biome.unwrapKey().stream())
                .map(key -> key.identifier().toString())
                .sorted()
                .forEach(ids::add);
        JsonArray caves = new JsonArray();
        CaveBiomes.fromServer(server).forEach(id -> caves.add(id.toString()));
        JsonObject root = new JsonObject();
        root.add("biomes", ids);
        root.add("caves", caves);
        Path file = knownBiomesFile();
        try (Writer writer = Files.newBufferedWriter(file)) {
            GSON.toJson(root, writer);
        } catch (IOException e) {
            LOGGER.warn("Couldn't write {}", file, e);
        }
        SmokeTest.onServerStarted(server);
    }
}
