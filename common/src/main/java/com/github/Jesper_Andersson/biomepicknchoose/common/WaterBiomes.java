package com.github.Jesper_Andersson.biomepicknchoose.common;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Finds the ocean and river biomes, from the vanilla and common biome tags for them. */
public final class WaterBiomes {
    private static final List<TagKey<Biome>> TAGS = Stream.of(
                    "minecraft:is_ocean", "minecraft:is_deep_ocean", "minecraft:is_river",
                    "c:is_ocean", "c:is_deep_ocean", "c:is_river", "c:is_aquatic")
            .map(id -> TagKey.create(Registries.BIOME, Identifier.parse(id)))
            .toList();
    private static final Set<Identifier> VANILLA = Stream.of(
                    Biomes.OCEAN, Biomes.DEEP_OCEAN, Biomes.COLD_OCEAN, Biomes.DEEP_COLD_OCEAN, Biomes.FROZEN_OCEAN,
                    Biomes.DEEP_FROZEN_OCEAN, Biomes.LUKEWARM_OCEAN, Biomes.DEEP_LUKEWARM_OCEAN, Biomes.WARM_OCEAN,
                    Biomes.RIVER, Biomes.FROZEN_RIVER)
            .map(ResourceKey::identifier)
            .collect(Collectors.toUnmodifiableSet());

    private WaterBiomes() {}

    /** The vanilla ocean and river biomes, without a world. */
    public static Set<Identifier> vanilla() {
        return VANILLA;
    }

    /** The biomes in the server's ocean and river tags. */
    public static Set<Identifier> fromServer(MinecraftServer server) {
        Set<Identifier> ids = new TreeSet<>(VANILLA);
        var biomes = server.registryAccess().lookupOrThrow(Registries.BIOME);
        for (TagKey<Biome> tag : TAGS) {
            biomes.getTagOrEmpty(tag).forEach(biome -> biome.unwrapKey().ifPresent(key -> ids.add(key.identifier())));
        }
        return ids;
    }
}
