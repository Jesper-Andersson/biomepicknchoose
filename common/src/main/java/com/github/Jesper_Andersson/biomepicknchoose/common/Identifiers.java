package com.github.Jesper_Andersson.biomepicknchoose.common;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;
import java.util.TreeSet;

public final class Identifiers {
    private Identifiers() {}

    /** Parses each id, sorted, leaving out the ones that aren't valid ids. */
    public static Set<ResourceLocation> parseAll(Iterable<String> ids) {
        Set<ResourceLocation> parsed = new TreeSet<>();
        for (String id : ids) {
            ResourceLocation location = ResourceLocation.tryParse(id);
            if (location != null) parsed.add(location);
        }
        return parsed;
    }
}
