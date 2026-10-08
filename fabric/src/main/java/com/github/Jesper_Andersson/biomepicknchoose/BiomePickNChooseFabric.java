package com.github.Jesper_Andersson.biomepicknchoose;

import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeToggles;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public class BiomePickNChooseFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        FabricConfig.load();
        ServerLifecycleEvents.SERVER_STARTING.register(server -> BiomeToggles.onServerAboutToStart());
        ServerLifecycleEvents.SERVER_STARTED.register(BiomeToggles::onServerStarted);
    }
}
