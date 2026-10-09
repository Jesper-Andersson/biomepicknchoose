package com.github.Jesper_Andersson.biomepicknchoose.compat;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Only applies the mixins in {@code mixin.compat} when Lithostitched, the mod they target, is installed in a version
 * they support. Each loader checks for it its own way, since mixins load before the regular mod lists exist.
 */
public abstract class CompatMixinPlugin implements IMixinConfigPlugin {
    private static final String COMPAT_PACKAGE = "com.github.Jesper_Andersson.biomepicknchoose.mixin.compat.";

    // The oldest Lithostitched with the InjectorBiomeSource fields and methods the compat mixin targets
    private static final String LITHOSTITCHED_MIN_VERSION = "1.7.2";

    protected abstract boolean isModLoaded(String modId, String minVersion);

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!mixinClassName.startsWith(COMPAT_PACKAGE)) return true;
        return isModLoaded("lithostitched", LITHOSTITCHED_MIN_VERSION);
    }

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
