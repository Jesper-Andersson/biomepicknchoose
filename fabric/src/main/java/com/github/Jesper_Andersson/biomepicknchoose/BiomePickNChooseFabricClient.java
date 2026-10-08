package com.github.Jesper_Andersson.biomepicknchoose;

import com.github.Jesper_Andersson.biomepicknchoose.client.preview.BiomePreviewCapture;
import com.github.Jesper_Andersson.biomepicknchoose.client.preview.BiomePreviewCommand;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class BiomePickNChooseFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Frames are hooked by MinecraftMixin, since Fabric API has no event for the end of a frame
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> BiomePreviewCapture.onClientTick());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) ->
                dispatcher.register(BiomePreviewCommand.create(FabricClientCommandSource::sendError)));
    }
}
