package com.github.Jesper_Andersson.biomepicknchoose.common;

import com.github.Jesper_Andersson.biomepicknchoose.Constants;
import com.github.Jesper_Andersson.biomepicknchoose.platform.Services;
import com.google.gson.Gson;
import com.mojang.logging.LogUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
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
 * Which overworld biomes are turned off. The config is read once when a world starts, so it stays fixed while the
 * world runs, and the menu changes only apply on the next world load.
 * <p>
 * On NeoForge, other mods can list their overworld biomes in the menu before any world was loaded by sending an IMC
 * message to {@value Constants#MOD_ID} with method {@value #REGISTER_BIOMES} and a {@code Collection} of biome
 * {@code ResourceLocation}s, {@code ResourceKey}s or id strings, from {@code InterModEnqueueEvent}.
 */
public final class BiomeToggles {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    public static final String REGISTER_BIOMES = "register_biomes";

    // Biome ids sent by other mods, through IMC on NeoForge
    private static volatile Set<ResourceLocation> registered = Set.of();

    private static volatile Set<ResourceKey<Biome>> disabled = Set.of();
    // Bumped on every snapshot, so biome sources know to rebuild their filtered parameter lists
    private static volatile int version;

    private BiomeToggles() {}

    public static void snapshot() {
        Set<ResourceKey<Biome>> keys = new HashSet<>();
        for (String id : Services.PLATFORM.getDisabledBiomes()) {
            ResourceLocation location = ResourceLocation.tryParse(id);
            if (location != null) keys.add(ResourceKey.create(Registries.BIOME, location));
        }
        disabled = Set.copyOf(keys);
        version++;
        if (!keys.isEmpty()) LOGGER.info("Disabled overworld biomes: {}", keys.stream().map(ResourceKey::location).toList());
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

    /** Vanilla, IMC-registered, cached and currently disabled biome ids, so a disabled biome can always be turned back on. */
    public static Set<ResourceLocation> knownBiomes() {
        Set<ResourceLocation> ids = new TreeSet<>();
        MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD.usedBiomes().forEach(key -> ids.add(key.location()));
        ids.addAll(registered);
        Path file = knownBiomesFile();
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                for (JsonElement element : JsonParser.parseReader(reader).getAsJsonArray()) {
                    ResourceLocation location = ResourceLocation.tryParse(element.getAsString());
                    if (location != null) ids.add(location);
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Couldn't read {}", file, e);
            }
        }
        for (String id : Services.PLATFORM.getDisabledBiomes()) {
            ResourceLocation location = ResourceLocation.tryParse(id);
            if (location != null) ids.add(location);
        }
        return ids;
    }

    /** Biome ids other mods asked to list in the menu, replacing any set before. */
    public static void setRegistered(Set<ResourceLocation> ids) {
        registered = Set.copyOf(ids);
    }

    /** Called by each loader when a server is about to load its worlds. */
    public static void onServerAboutToStart() {
        snapshot();
    }

    /** Called by each loader once a server has loaded its worlds. */
    public static void onServerStarted(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        JsonArray ids = new JsonArray();
        overworld.getChunkSource().getGenerator().getBiomeSource().possibleBiomes().stream()
                .flatMap(biome -> biome.unwrapKey().stream())
                .map(key -> key.location().toString())
                .sorted()
                .forEach(ids::add);
        Path file = knownBiomesFile();
        try (Writer writer = Files.newBufferedWriter(file)) {
            GSON.toJson(ids, writer);
        } catch (IOException e) {
            LOGGER.warn("Couldn't write {}", file, e);
        }
    }
}
