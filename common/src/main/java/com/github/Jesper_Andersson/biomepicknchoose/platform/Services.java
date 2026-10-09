package com.github.Jesper_Andersson.biomepicknchoose.platform;

import com.github.Jesper_Andersson.biomepicknchoose.platform.services.IPlatformHelper;

import java.util.ServiceLoader;

/** Loader specific code, implemented in each loader project and found through {@link ServiceLoader}. */
public final class Services {
    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);

    private Services() {}

    public static <T> T load(Class<T> type) {
        return ServiceLoader.load(type, Services.class.getClassLoader()).findFirst()
                .orElseThrow(() -> new NullPointerException("Failed to load service for " + type.getName()));
    }
}
