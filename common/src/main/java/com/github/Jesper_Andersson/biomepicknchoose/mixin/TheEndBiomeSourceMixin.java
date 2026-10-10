package com.github.Jesper_Andersson.biomepicknchoose.mixin;

import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeToggleSource;
import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeToggles;
import com.github.Jesper_Andersson.biomepicknchoose.common.EndErosionLadder;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.TheEndBiomeSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

@Mixin(TheEndBiomeSource.class)
public abstract class TheEndBiomeSourceMixin implements BiomeToggleSource {
    @Shadow @Final private Holder<Biome> highlands;
    @Shadow @Final private Holder<Biome> midlands;
    @Shadow @Final private Holder<Biome> islands;
    @Shadow @Final private Holder<Biome> barrens;

    @Unique private volatile boolean bpnc$active;

    @Override
    public void bpnc$activate() {
        bpnc$active = true;
    }

    // Replace disabled biomes with the enabled outer End biome whose erosion range is nearest. Wraps the whole method,
    // so End biomes added by Fabric API or TerraBlender, which hook into it, are replaced too
    @WrapMethod(method = "getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;")
    private Holder<Biome> bpnc$replaceDisabled(int quartX, int quartY, int quartZ, Climate.Sampler sampler, Operation<Holder<Biome>> original) {
        Holder<Biome> biome = original.call(quartX, quartY, quartZ, sampler);
        if (!bpnc$active || !BiomeToggles.isDisabled(biome)) return biome;
        return EndErosionLadder.nearestEnabled(bpnc$ladder(), biome, bpnc$erosion(quartX, quartY, quartZ, sampler));
    }

    @Override
    public Holder<Biome> bpnc$replace(Holder<Biome> biome, Climate.TargetPoint point) {
        if (!bpnc$active || !BiomeToggles.isDisabled(biome)) return biome;
        return EndErosionLadder.nearestEnabled(bpnc$ladder(), biome, Climate.unquantizeCoord(point.erosion()));
    }

    @Override
    @Nullable
    public Climate.ParameterList<Holder<Biome>> bpnc$parameters() {
        return null;
    }

    // The erosion vanilla picks the biome by, sampled in the middle of the section like vanilla does
    @Unique
    private static double bpnc$erosion(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
        int sectionMiddleX = (SectionPos.blockToSectionCoord(QuartPos.toBlock(quartX)) * 2 + 1) * 8;
        int sectionMiddleZ = (SectionPos.blockToSectionCoord(QuartPos.toBlock(quartZ)) * 2 + 1) * 8;
        return sampler.erosion().compute(new DensityFunction.SinglePointContext(sectionMiddleX, QuartPos.toBlock(quartY), sectionMiddleZ));
    }

    @Unique
    private List<EndErosionLadder.Step> bpnc$ladder() {
        return EndErosionLadder.of(highlands, midlands, barrens, islands);
    }
}
