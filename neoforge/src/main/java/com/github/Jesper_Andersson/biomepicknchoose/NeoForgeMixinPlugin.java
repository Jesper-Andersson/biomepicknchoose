package com.github.Jesper_Andersson.biomepicknchoose;

import com.github.Jesper_Andersson.biomepicknchoose.compat.CompatMixinPlugin;
import net.neoforged.fml.loading.FMLLoader;

public class NeoForgeMixinPlugin extends CompatMixinPlugin {
    @Override
    protected boolean isModLoaded(String modId) {
        return FMLLoader.getCurrent().getLoadingModList().getModFileById(modId) != null;
    }
}
