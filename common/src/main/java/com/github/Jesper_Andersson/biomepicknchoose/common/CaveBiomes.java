package com.github.Jesper_Andersson.biomepicknchoose.common;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
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
    private static final TagKey<Biome> IS_CAVE = TagKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("c", "is_cave"));

    // Vanilla can't change while the game runs
    private static volatile Set<Identifier> vanilla;

    private CaveBiomes() {}

    /** The vanilla cave biomes, without a world. */
    public static Set<Identifier> vanilla() {
        Set<Identifier> ids = vanilla;
        if (ids == null) {
            ids = new TreeSet<>();
            for (ResourceKey<Biome> key : find(MultiNoiseBiomeSourceParameterList.knownPresets()
                    .get(MultiNoiseBiomeSourceParameterList.Preset.OVERWORLD).values())) {
                ids.add(key.identifier());
            }
            ids = Set.copyOf(ids);
            vanilla = ids;
        }
        return ids;
    }

    /** The cave biomes of the server's overworld, from its biome parameters and the c:is_cave tag. */
    public static Set<Identifier> fromServer(MinecraftServer server) {
        Set<Identifier> ids = new TreeSet<>();
        var source = server.overworld().getChunkSource().getGenerator().getBiomeSource();
        if (BiomeSources.root(source) instanceof BiomeToggleSource root) {
            for (Holder<Biome> biome : find(root.bpnc$parameters().values())) {
                biome.unwrapKey().ifPresent(key -> ids.add(key.identifier()));
            }
        }
        server.registryAccess().lookupOrThrow(Registries.BIOME).getTagOrEmpty(IS_CAVE)
                .forEach(biome -> biome.unwrapKey().ifPresent(key -> ids.add(key.identifier())));
        return ids;
    }

    // Biomes with no parameter point at the surface depth
    private static <T> Set<T> find(List<Pair<Climate.ParameterPoint, T>> values) {
        Set<T> all = new HashSet<>();
        Set<T> surface = new HashSet<>();
        for (Pair<Climate.ParameterPoint, T> entry : values) {
            all.add(entry.getSecond());
            Climate.Parameter depth = entry.getFirst().depth();
            if (depth.min() <= 0 && depth.max() >= 0) surface.add(entry.getSecond());
        }
        all.removeAll(surface);
        return all;
    }
}
