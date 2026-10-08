package com.github.Jesper_Andersson.biomepicknchoose.platform;

import com.github.Jesper_Andersson.biomepicknchoose.FabricConfig;
import com.github.Jesper_Andersson.biomepicknchoose.platform.services.IPlatformHelper;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public class FabricPlatformHelper implements IPlatformHelper {
    @Override
    public Path getConfigDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public Optional<String> getModName(String modId) {
        return FabricLoader.getInstance().getModContainer(modId).map(container -> container.getMetadata().getName());
    }

    @Override
    public List<String> getDisabledBiomes() {
        return FabricConfig.disabledBiomes();
    }

    @Override
    public void setDisabledBiomes(List<String> biomes) {
        FabricConfig.save(biomes);
    }
}
