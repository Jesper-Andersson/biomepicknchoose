package com.github.Jesper_Andersson.biomepicknchoose.common;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What the config menu lists: the known biomes of each dimension, the biomes each dimension of the world can place,
 * and which biomes are caves or water.
 *
 * @param known      per dimension, every biome the menu lists
 * @param generating per dimension, the biomes the world can place. Dimensions where that isn't known, like all of them
 *                   before any world was loaded, are left out
 * @param caves      biomes that only generate underground
 * @param water      ocean and river biomes
 */
public record BiomeCatalog(Map<BiomeDimension, Set<ResourceLocation>> known, Map<BiomeDimension, Set<ResourceLocation>> generating,
                           Set<ResourceLocation> caves, Set<ResourceLocation> water) {

    /**
     * This game's catalog, without a running world: vanilla biomes, the ones other mods registered or define in their
     * files, and the ones the last loaded world could place.
     */
    public static BiomeCatalog local() {
        KnownBiomesFile.Contents cached = KnownBiomesFile.read();
        Set<BiomeDimension> dimensions = new TreeSet<>(BiomeDimension.VANILLA);
        dimensions.addAll(ModBiomeScan.dimensions());
        dimensions.addAll(cached.dimensions().keySet());

        Map<BiomeDimension, Set<ResourceLocation>> known = new TreeMap<>();
        for (BiomeDimension dimension : dimensions) {
            Set<ResourceLocation> ids = builtInBiomes(dimension);
            ids.addAll(ModBiomeScan.biomes(dimension));
            ids.addAll(cached.dimensions().getOrDefault(dimension, Set.of()));
            known.put(dimension, ids);
        }
        addUnlistedDisabled(known);

        Set<ResourceLocation> caves = new TreeSet<>(CaveBiomes.vanilla());
        caves.addAll(cached.caves());
        Set<ResourceLocation> water = new TreeSet<>(WaterBiomes.vanilla());
        water.addAll(ModBiomeScan.waterBiomes());
        water.addAll(cached.water());
        return new BiomeCatalog(known, cached.dimensions(), caves, water);
    }

    /** The server's catalog, from what its dimensions can place. Used when an operator edits its config from a client. */
    public static BiomeCatalog server(MinecraftServer server) {
        Set<BiomeDimension> dimensions = new TreeSet<>(BiomeDimension.VANILLA);
        dimensions.addAll(serverDimensions(server));

        Map<BiomeDimension, Set<ResourceLocation>> known = new TreeMap<>();
        for (BiomeDimension dimension : dimensions) {
            Set<ResourceLocation> ids = builtInBiomes(dimension);
            ids.addAll(possibleBiomes(server, dimension));
            known.put(dimension, ids);
        }
        addUnlistedDisabled(known);
        return new BiomeCatalog(known, serverGenerating(server), CaveBiomes.fromServer(server), WaterBiomes.fromServer(server));
    }

    /** The biomes each dimension of the server can place. */
    public static Map<BiomeDimension, Set<ResourceLocation>> serverGenerating(MinecraftServer server) {
        Map<BiomeDimension, Set<ResourceLocation>> generating = new TreeMap<>();
        for (BiomeDimension dimension : serverDimensions(server)) {
            generating.put(dimension, possibleBiomes(server, dimension));
        }
        return generating;
    }

    /** Every dimension the server has loaded, vanilla first. */
    public static Set<BiomeDimension> serverDimensions(MinecraftServer server) {
        Set<BiomeDimension> dimensions = new TreeSet<>();
        server.levelKeys().forEach(level -> dimensions.add(BiomeDimension.of(level)));
        return dimensions;
    }

    /** The known biomes of every dimension together, for saving the config. */
    public Set<ResourceLocation> allKnown() {
        Set<ResourceLocation> ids = new TreeSet<>();
        known.values().forEach(ids::addAll);
        return ids;
    }

    /** Whether the world is known not to place the biome in the dimension, so turning it off changes nothing. */
    public boolean isUnused(BiomeDimension dimension, ResourceLocation biome) {
        Set<ResourceLocation> placed = generating.get(dimension);
        return placed != null && !placed.contains(biome);
    }

    // Vanilla biomes, and in the overworld the ones other mods registered through IMC
    private static Set<ResourceLocation> builtInBiomes(BiomeDimension dimension) {
        Set<ResourceLocation> ids = dimension.vanilla();
        if (dimension.equals(BiomeDimension.OVERWORLD)) ids.addAll(BiomeToggles.registered());
        return ids;
    }

    // Lists disabled biomes that no dimension lists in the overworld, so they can always be turned back on
    private static void addUnlistedDisabled(Map<BiomeDimension, Set<ResourceLocation>> known) {
        Set<ResourceLocation> listed = new TreeSet<>();
        known.values().forEach(listed::addAll);
        for (ResourceLocation id : Identifiers.parseAll(BiomeConfig.disabledBiomes())) {
            if (!listed.contains(id)) known.get(BiomeDimension.OVERWORLD).add(id);
        }
    }

    // Empty if the server has no such level
    private static Set<ResourceLocation> possibleBiomes(MinecraftServer server, BiomeDimension dimension) {
        ServerLevel level = server.getLevel(dimension.level());
        if (level == null) return new TreeSet<>();
        return BiomeSources.possibleBiomeIds(level.getChunkSource().getGenerator().getBiomeSource());
    }
}
