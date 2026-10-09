package com.github.Jesper_Andersson.biomepicknchoose;

import com.github.Jesper_Andersson.biomepicknchoose.client.gui.BiomeToggleScreen;
import com.github.Jesper_Andersson.biomepicknchoose.client.preview.BiomePreviewCapture;
import com.github.Jesper_Andersson.biomepicknchoose.client.preview.BiomePreviewCommand;
import net.minecraft.commands.CommandSourceStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public class BiomePickNChooseClient {
    public BiomePickNChooseClient(ModContainer container) {
        // Config button in the Mods list
        container.registerExtensionPoint(IConfigScreenFactory.class, (c, parent) -> new BiomeToggleScreen(parent));
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> BiomePreviewCapture.onClientTick());
        NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post event) -> BiomePreviewCapture.onRenderFrame());
        NeoForge.EVENT_BUS.addListener((RegisterClientCommandsEvent event) ->
                event.getDispatcher().register(BiomePreviewCommand.create(CommandSourceStack::sendFailure)));
    }
}
