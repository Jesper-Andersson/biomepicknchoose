package com.github.Jesper_Andersson.biomepicknchoose;

import com.github.Jesper_Andersson.biomepicknchoose.compat.CompatMixinPlugin;
import net.neoforged.fml.loading.LoadingModList;

public class NeoForgeMixinPlugin extends CompatMixinPlugin {
    @Override
    protected boolean isModLoaded(String modId) {
        return LoadingModList.get().getModFileById(modId) != null;
    }
}
