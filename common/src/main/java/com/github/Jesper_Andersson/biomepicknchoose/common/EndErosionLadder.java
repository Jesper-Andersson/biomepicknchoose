package com.github.Jesper_Andersson.biomepicknchoose.common;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import java.util.List;

/**
 * How vanilla picks the outer End biomes: by the erosion noise, from highlands at the top to small islands at the
 * bottom. A disabled End biome is replaced by the enabled step whose erosion range is nearest.
 */
public final class EndErosionLadder {
    // The lowest erosion of each step, from TheEndBiomeSource.getNoiseBiome
    private static final double HIGHLANDS_MIN = 0.25;
    private static final double MIDLANDS_MIN = -0.0625;
    private static final double BARRENS_MIN = -0.21875;

    /** One step of the ladder: the biome vanilla places for erosion from minErosion to maxErosion. */
    public record Step(Holder<Biome> biome, double minErosion, double maxErosion) {
        double distanceTo(double erosion) {
            if (erosion < minErosion) return minErosion - erosion;
            if (erosion > maxErosion) return erosion - maxErosion;
            return 0;
        }
    }

    private EndErosionLadder() {}

    public static List<Step> of(Holder<Biome> highlands, Holder<Biome> midlands, Holder<Biome> barrens, Holder<Biome> islands) {
        return List.of(
                new Step(highlands, HIGHLANDS_MIN, Double.POSITIVE_INFINITY),
                new Step(midlands, MIDLANDS_MIN, HIGHLANDS_MIN),
                new Step(barrens, BARRENS_MIN, MIDLANDS_MIN),
                new Step(islands, Double.NEGATIVE_INFINITY, BARRENS_MIN));
    }

    /** The enabled step's biome nearest to the erosion, or the disabled biome itself if every step is disabled. */
    public static Holder<Biome> nearestEnabled(List<Step> ladder, Holder<Biome> disabled, double erosion) {
        Holder<Biome> nearest = disabled;
        double nearestDistance = Double.MAX_VALUE;
        for (Step step : ladder) {
            if (BiomeToggles.isDisabled(step.biome())) continue;
            double distance = step.distanceTo(erosion);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = step.biome();
            }
        }
        return nearest;
    }
}
