package com.github.Jesper_Andersson.biomepicknchoose.common;

import net.minecraft.resources.Identifier;

import java.util.Set;
import java.util.TreeSet;

public final class Identifiers {
    private Identifiers() {}

    /** Parses each id, sorted, leaving out the ones that aren't valid ids. */
    public static Set<Identifier> parseAll(Iterable<String> ids) {
        Set<Identifier> parsed = new TreeSet<>();
        for (String id : ids) {
            Identifier location = Identifier.tryParse(id);
            if (location != null) parsed.add(location);
        }
        return parsed;
    }
}
