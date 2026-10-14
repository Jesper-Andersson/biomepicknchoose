package com.github.Jesper_Andersson.biomepicknchoose.common;

import com.github.Jesper_Andersson.biomepicknchoose.Constants;
import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Set;

/**
 * Which biomes are turned off, in every dimension. The config is read when a world starts and again on
 * {@code /reload}, so changes apply to chunks generated after that. Chunks that already exist keep their biomes.
 * <p>
 * On NeoForge, other mods can list their overworld biomes in the menu before any world was loaded by sending an IMC
 * message to {@value Constants#MOD_ID} with method {@value #REGISTER_BIOMES} and a {@code Collection} of biome
 * {@code ResourceLocation}s, {@code ResourceKey}s or id strings, from {@code InterModEnqueueEvent}.
 */
public final class BiomeToggles {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String REGISTER_BIOMES = "register_biomes";

    // Biome ids sent by other mods, through IMC on NeoForge
    private static volatile Set<ResourceLocation> registered = Set.of();

    private static volatile Set<ResourceKey<Biome>> disabled = Set.of();
    // Bumped on every snapshot, so biome sources know to rebuild their filtered parameter lists
    private static volatile int version;

    private BiomeToggles() {}

    // Synchronized, since version++ isn't atomic
    public static synchronized void snapshot() {
        Set<ResourceKey<Biome>> keys = new HashSet<>();
        for (ResourceLocation id : Identifiers.parseAll(BiomeConfig.disabledBiomes())) keys.add(ResourceKey.create(Registries.BIOME, id));
        disabled = Set.copyOf(keys);
        version++;
        LOGGER.info("Disabled biomes: {}", keys.stream().map(ResourceKey::location).sorted().toList());
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

    /** Biome ids other mods asked to list in the menu, replacing any set before. */
    public static void setRegistered(Set<ResourceLocation> ids) {
        registered = Set.copyOf(ids);
    }

    static Set<ResourceLocation> registered() {
        return registered;
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
        KnownBiomesFile.write(server);
        SmokeTest.onServerStarted(server);
    }
}
