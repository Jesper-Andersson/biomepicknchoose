package com.github.Jesper_Andersson.biomepicknchoose.common;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import org.jetbrains.annotations.Nullable;

/**
 * Implemented by MultiNoiseBiomeSource and TheEndBiomeSource through mixins. Disabled biomes are only replaced in the
 * sources of the overworld, the Nether and the End, which are activated when their ChunkMap is created.
 */
public interface BiomeToggleSource {
    void bpnc$activate();

    /**
     * Returns an enabled replacement if the biome is disabled and this source is active, otherwise the biome itself.
     * Also used for biomes placed by wrapping sources, like Lithostitched injectors.
     */
    Holder<Biome> bpnc$replace(Holder<Biome> biome, Climate.TargetPoint point);

    /** The source's biome parameters, with disabled biomes, or null for sources without them, like the End's. */
    @Nullable
    Climate.ParameterList<Holder<Biome>> bpnc$parameters();
}
