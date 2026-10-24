package com.github.Jesper_Andersson.biomepicknchoose.common;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Finds the overworld biomes that only generate underground, like lush caves and the deep dark. Surface biomes are
 * placed at depth 0 (and again at 1, under the surface), cave biomes only at depths below the surface.
 */
public final class CaveBiomes {
    // Common tag for cave biomes, also covers biomes placed by TerraBlender regions, which aren't in the root parameters
    private static final TagKey<Biome> IS_CAVE = TagKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath("c", "is_cave"));

    // Climate noise stays well within -2 to 2, in the quantized units of Climate.Parameter
    private static final long UNREACHABLE = Climate.quantizeCoord(2);

    // Vanilla can't change while the game runs
    private static volatile Set<ResourceLocation> vanilla;

    private CaveBiomes() {}

    /** The vanilla cave biomes, without a world. */
    public static Set<ResourceLocation> vanilla() {
        Set<ResourceLocation> ids = vanilla;
        if (ids == null) {
            ids = new TreeSet<>();
            for (ResourceKey<Biome> key : find(MultiNoiseBiomeSourceParameterList.knownPresets()
                    .get(MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD).values())) {
                ids.add(key.location());
            }
            ids = Set.copyOf(ids);
            vanilla = ids;
        }
        return ids;
    }

    /** The cave biomes of the server's overworld, from its biome parameters and the c:is_cave tag. */
    public static Set<ResourceLocation> fromServer(MinecraftServer server) {
        Set<ResourceLocation> ids = new TreeSet<>();
        var source = server.overworld().getChunkSource().getGenerator().getBiomeSource();
        if (BiomeSources.root(source) instanceof BiomeToggleSource root && root.bpnc$parameters() != null) {
            for (Holder<Biome> biome : find(root.bpnc$parameters().values())) {
                biome.unwrapKey().ifPresent(key -> ids.add(key.location()));
            }
        }
        server.registryAccess().registryOrThrow(Registries.BIOME).getTagOrEmpty(IS_CAVE)
                .forEach(biome -> biome.unwrapKey().ifPresent(key -> ids.add(key.location())));
        return ids;
    }

    // Biomes with no parameter point at the surface depth
    private static <T> Set<T> find(List<Pair<Climate.ParameterPoint, T>> values) {
        Set<T> all = new HashSet<>();
        Set<T> surface = new HashSet<>();
        for (Pair<Climate.ParameterPoint, T> entry : values) {
            if (unreachable(entry.getFirst())) continue;
            all.add(entry.getSecond());
            Climate.Parameter depth = entry.getFirst().depth();
            if (depth.min() <= 0 && depth.max() >= 0) surface.add(entry.getSecond());
        }
        all.removeAll(surface);
        return all;
    }

    // Biolith lists the biomes it swaps in for part of another biome at a point far outside the climate noise, all
    // 3.01, so the noise never picks them there. That point says nothing about depth
    private static boolean unreachable(Climate.ParameterPoint point) {
        for (Climate.Parameter parameter : List.of(point.temperature(), point.humidity(), point.continentalness(),
                point.erosion(), point.weirdness())) {
            if (parameter.min() > UNREACHABLE || parameter.max() < -UNREACHABLE) return true;
        }
        return false;
    }
}
