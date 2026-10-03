package com.github.Jesper_Andersson.biomepicknchoose.mixin.compat;

import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeToggleSource;
import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeToggles;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.worldgen.lithostitched.impl.worldgen.biomeinjector.internal.InjectorBiomeSource;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(InjectorBiomeSource.class)
public abstract class InjectorBiomeSourceMixin {
    // Biome injectors (force_placement, dispatch_alternate_layout, replace_partially, replace_fully) pick biomes after
    // the wrapped multi-noise source returns, so replace disabled biomes in their output too
    @WrapMethod(method = "getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;")
    private Holder<Biome> bpnc$replaceDisabled(int x, int y, int z, Climate.Sampler sampler, Operation<Holder<Biome>> original) {
        Holder<Biome> biome = original.call(x, y, z, sampler);
        if (!BiomeToggles.isDisabled(biome)) return biome;
        if (!(((InjectorBiomeSource) (Object) this).rootDelegate() instanceof BiomeToggleSource source)) return biome;
        return source.bpnc$replace(biome, sampler.sample(x, y, z));
    }
}
