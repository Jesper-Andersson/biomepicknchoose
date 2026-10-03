package com.github.Jesper_Andersson.biomepicknchoose;

import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeToggles;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod(BiomePickNChoose.MODID)
public class BiomePickNChoose {
    public static final String MODID = "biomepicknchoose";

    public BiomePickNChoose(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        modEventBus.addListener(BiomeToggles::onInterModProcess);
    }
}
