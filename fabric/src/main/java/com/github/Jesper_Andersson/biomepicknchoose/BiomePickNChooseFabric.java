package com.github.Jesper_Andersson.biomepicknchoose;

import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeConfig;
import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeToggles;
import com.github.Jesper_Andersson.biomepicknchoose.common.ServerConfigEditing;
import com.github.Jesper_Andersson.biomepicknchoose.common.SmokeTest;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public class BiomePickNChooseFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        BiomeConfig.init();
        ServerLifecycleEvents.SERVER_STARTING.register(server -> BiomeToggles.onServerAboutToStart());
        ServerLifecycleEvents.SERVER_STARTED.register(BiomeToggles::onServerStarted);
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> BiomeToggles.onReload(server));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> SmokeTest.onServerStopped());

        // Editing the server's config from a client, see ServerConfigEditing
        PayloadTypeRegistry.playS2C().register(ServerConfigEditing.OpenPayload.TYPE, ServerConfigEditing.OpenPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ServerConfigEditing.SavePayload.TYPE, ServerConfigEditing.SavePayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ServerConfigEditing.SavePayload.TYPE,
                (payload, context) -> ServerConfigEditing.handleSave(context.player(), payload));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> ServerConfigEditing.registerCommand(dispatcher));
    }
}
