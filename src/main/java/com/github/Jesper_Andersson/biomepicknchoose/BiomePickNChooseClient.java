package com.github.Jesper_Andersson.biomepicknchoose;

import com.github.Jesper_Andersson.biomepicknchoose.client.gui.BiomeToggleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = BiomePickNChoose.MODID, dist = Dist.CLIENT)
public class BiomePickNChooseClient {
    public BiomePickNChooseClient(ModContainer container) {
        // Config button in the Mods list
        container.registerExtensionPoint(IConfigScreenFactory.class, (c, parent) -> new BiomeToggleScreen(parent));
    }
}
