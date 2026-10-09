package com.github.Jesper_Andersson.biomepicknchoose;

import com.github.Jesper_Andersson.biomepicknchoose.compat.CompatMixinPlugin;
import net.fabricmc.loader.api.FabricLoader;

public class FabricMixinPlugin extends CompatMixinPlugin {
    @Override
    protected boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }
}
