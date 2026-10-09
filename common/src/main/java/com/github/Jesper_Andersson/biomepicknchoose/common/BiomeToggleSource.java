package com.github.Jesper_Andersson.biomepicknchoose.common;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

/**
 * Implemented by MultiNoiseBiomeSource through a mixin. Disabled biomes are only replaced in the source of the
 * overworld, which is marked when its ChunkMap is created.
 */
public interface BiomeToggleSource {
    void bpnc$setOverworld();

    /**
     * Returns the nearest enabled biome by climate if the biome is disabled and this is the overworld source,
     * otherwise the biome itself. Also used for biomes placed by wrapping sources, like Lithostitched injectors.
     */
    Holder<Biome> bpnc$replace(Holder<Biome> biome, Climate.TargetPoint point);

    /** The source's biome parameters, with disabled biomes. */
    Climate.ParameterList<Holder<Biome>> bpnc$parameters();
}
