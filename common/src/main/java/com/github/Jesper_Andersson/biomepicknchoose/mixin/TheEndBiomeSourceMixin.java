package com.github.Jesper_Andersson.biomepicknchoose.mixin;

import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeToggleSource;
import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeToggles;
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

@Mixin(TheEndBiomeSource.class)
public abstract class TheEndBiomeSourceMixin implements BiomeToggleSource {
    // Vanilla picks the outer End biomes by erosion: highlands above the first limit, then midlands, barrens, and
    // small islands below the last
    @Unique private static final double bpnc$HIGHLANDS_MIN = 0.25;
    @Unique private static final double bpnc$MIDLANDS_MIN = -0.0625;
    @Unique private static final double bpnc$BARRENS_MIN = -0.21875;

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
    private Holder<Biome> bpnc$replaceDisabled(int x, int y, int z, Climate.Sampler sampler, Operation<Holder<Biome>> original) {
        Holder<Biome> biome = original.call(x, y, z, sampler);
        if (!bpnc$active || !BiomeToggles.isDisabled(biome)) return biome;
        // Where vanilla samples erosion: the middle of the section
        int centerX = (SectionPos.blockToSectionCoord(QuartPos.toBlock(x)) * 2 + 1) * 8;
        int centerZ = (SectionPos.blockToSectionCoord(QuartPos.toBlock(z)) * 2 + 1) * 8;
        double erosion = sampler.erosion().compute(new DensityFunction.SinglePointContext(centerX, QuartPos.toBlock(y), centerZ));
        return bpnc$nearest(biome, erosion);
    }

    @Override
    public Holder<Biome> bpnc$replace(Holder<Biome> biome, Climate.TargetPoint point) {
        if (!bpnc$active || !BiomeToggles.isDisabled(biome)) return biome;
        return bpnc$nearest(biome, Climate.unquantizeCoord(point.erosion()));
    }

    @Override
    @Nullable
    public Climate.ParameterList<Holder<Biome>> bpnc$parameters() {
        return null;
    }

    // The enabled outer End biome nearest by erosion, or the biome itself if they are all disabled
    @Unique
    private Holder<Biome> bpnc$nearest(Holder<Biome> biome, double erosion) {
        Holder<Biome> best = biome;
        double bestDistance = Double.MAX_VALUE;
        Holder<Biome>[] candidates = bpnc$ladder();
        double[] mins = {bpnc$HIGHLANDS_MIN, bpnc$MIDLANDS_MIN, bpnc$BARRENS_MIN, Double.NEGATIVE_INFINITY};
        double[] maxes = {Double.POSITIVE_INFINITY, bpnc$HIGHLANDS_MIN, bpnc$MIDLANDS_MIN, bpnc$BARRENS_MIN};
        for (int i = 0; i < candidates.length; i++) {
            if (BiomeToggles.isDisabled(candidates[i])) continue;
            double distance = erosion < mins[i] ? mins[i] - erosion : erosion > maxes[i] ? erosion - maxes[i] : 0;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidates[i];
            }
        }
        return best;
    }

    @Unique
    @SuppressWarnings("unchecked")
    private Holder<Biome>[] bpnc$ladder() {
        return new Holder[]{highlands, midlands, barrens, islands};
    }
}
