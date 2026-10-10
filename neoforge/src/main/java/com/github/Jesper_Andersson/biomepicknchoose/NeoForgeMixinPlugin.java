package com.github.Jesper_Andersson.biomepicknchoose;

import com.github.Jesper_Andersson.biomepicknchoose.compat.CompatMixinPlugin;
import net.neoforged.fml.loading.LoadingModList;

public class NeoForgeMixinPlugin extends CompatMixinPlugin {
    // The version range in neoforge.mods.toml already stops older versions from loading
    @Override
    protected boolean isModLoaded(String modId, String minVersion) {
        return LoadingModList.get().getModFileById(modId) != null;
    }
}
