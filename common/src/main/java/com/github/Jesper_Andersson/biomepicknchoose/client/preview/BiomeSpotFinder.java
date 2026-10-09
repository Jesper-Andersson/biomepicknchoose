package com.github.Jesper_Andersson.biomepicknchoose.client.preview;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks a camera spot inside a biome from the biome source and noise heights alone, so no chunks are generated.
 * Thread safe, runs off the server thread: it only reads state that chunk generation workers also read concurrently.
 */
final class BiomeSpotFinder {
    private static final int SEARCH_RADIUS = 6400;
    private static final int MAX_CANDIDATES = 8;
    private static final double GOOD_ENOUGH = 0.85;
    private static final int INTERIOR_QUARTS = 16;
    private static final int CAMERA_BACK = 20;
    private static final int CAMERA_ABOVE = 24;
    private static final int WATER_CAMERA_ABOVE = 20;
    private static final float PITCH = 35;

    enum Failure { NOT_FOUND, UNDERGROUND }

    /** Where to put the camera. For cave biomes, only a point inside the biome, to look for a cave room around. */
    record Spot(double x, double y, double z, float yaw, float pitch, boolean cave) {}

    record Result(@Nullable Spot spot, @Nullable Failure failure) {}

    private final ServerLevel level;
    private final ChunkGenerator generator;
    private final BiomeSource source;
    private final RandomState randomState;
    private final Climate.Sampler sampler;
    private final ResourceKey<Biome> key;
    private final boolean cave;

    private BiomeSpotFinder(ServerLevel level, Holder<Biome> biome, boolean cave) {
        this.level = level;
        this.generator = level.getChunkSource().getGenerator();
        this.source = generator.getBiomeSource();
        this.randomState = level.getChunkSource().randomState();
        this.sampler = randomState.sampler();
        this.key = biome.unwrapKey().orElseThrow();
        this.cave = cave;
    }

    static Result find(ServerLevel level, Holder<Biome> biome, boolean cave, BiomeScan scan) {
        return new BiomeSpotFinder(level, biome, cave).find(scan);
    }

    private Result find(BiomeScan scan) {
        List<BiomeScan.Candidate> candidates = scan.candidates(key, cave);
        if (candidates.isEmpty()) return fallback();
        if (cave) return findCave(candidates);

        List<BlockPos> seen = new ArrayList<>();
        BlockPos best = null;
        double bestScore = -1;
        for (BiomeScan.Candidate candidate : candidates) {
            if (seen.size() >= MAX_CANDIDATES || bestScore >= GOOD_ENOUGH) break;
            BlockPos pos = candidate.pos();
            if (seen.stream().anyMatch(other -> other.distManhattan(pos.atY(other.getY())) < 128)) continue;
            seen.add(pos);

            int surface = surface(pos.getX(), pos.getZ());
            // Cave biomes are found below a surface that belongs to some other biome
            if (!isTarget(pos.getX(), surface, pos.getZ())) continue;
            double score = interiorScore(pos.getX(), surface, pos.getZ());
            if (score > bestScore) {
                bestScore = score;
                best = pos.atY(surface);
            }
        }
        if (best == null) return new Result(null, Failure.UNDERGROUND);
        return new Result(frame(best), null);
    }

    // The most surrounded candidate at its own height. The cave room itself is found once the chunks there exist
    private Result findCave(List<BiomeScan.Candidate> candidates) {
        BlockPos best = null;
        double bestScore = -1;
        for (int i = 0; i < Math.min(MAX_CANDIDATES, candidates.size()) && bestScore < GOOD_ENOUGH; i++) {
            BlockPos pos = candidates.get(i).pos();
            double score = interiorScore(pos.getX(), pos.getY(), pos.getZ());
            if (score > bestScore) {
                bestScore = score;
                best = pos;
            }
        }
        return new Result(caveSpot(best), null);
    }

    private static Spot caveSpot(BlockPos pos) {
        return new Spot(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0, true);
    }

    // The scan never saw the biome: it is below every scanned height, or farther out than the scan reaches
    private Result fallback() {
        Pair<BlockPos, Holder<Biome>> found = source.findClosestBiome3d(
                level.getRespawnData().pos().atY(generator.getSeaLevel()), SEARCH_RADIUS, 32, 64, holder -> holder.is(key), sampler, level);
        if (found == null) return new Result(null, Failure.NOT_FOUND);
        BlockPos pos = found.getFirst();
        if (cave) return new Result(caveSpot(pos), null);
        int surface = surface(pos.getX(), pos.getZ());
        if (!isTarget(pos.getX(), surface, pos.getZ())) return new Result(null, Failure.UNDERGROUND);
        return new Result(frame(pos.atY(surface)), null);
    }

    private double interiorScore(int x, int y, int z) {
        int qx = QuartPos.fromBlock(x);
        int qy = QuartPos.fromBlock(y);
        int qz = QuartPos.fromBlock(z);
        int hits = 0;
        int total = 0;
        int step = INTERIOR_QUARTS / 4;
        for (int dx = -INTERIOR_QUARTS; dx <= INTERIOR_QUARTS; dx += step) {
            for (int dz = -INTERIOR_QUARTS; dz <= INTERIOR_QUARTS; dz += step) {
                if (source.getNoiseBiome(qx + dx, qy, qz + dz, sampler).is(key)) hits++;
                total++;
            }
        }
        return (double) hits / total;
    }

    private Spot frame(BlockPos center) {
        float bestYaw = 0;
        int bestHits = -1;
        for (int i = 0; i < 8; i++) {
            float yaw = i * 45;
            double dx = -Mth.sin(yaw * Mth.DEG_TO_RAD);
            double dz = Mth.cos(yaw * Mth.DEG_TO_RAD);
            double camX = center.getX() - dx * CAMERA_BACK;
            double camZ = center.getZ() - dz * CAMERA_BACK;
            int hits = 0;
            for (int d = 8; d <= 72; d += 8) {
                int x = Mth.floor(camX + dx * d);
                int z = Mth.floor(camZ + dz * d);
                if (isTarget(x, surface(x, z), z)) hits++;
            }
            if (hits > bestHits) {
                bestHits = hits;
                bestYaw = yaw;
            }
        }

        double dx = -Mth.sin(bestYaw * Mth.DEG_TO_RAD);
        double dz = Mth.cos(bestYaw * Mth.DEG_TO_RAD);
        double camX = center.getX() + 0.5 - dx * CAMERA_BACK;
        double camZ = center.getZ() + 0.5 - dz * CAMERA_BACK;
        // Keep the camera clear of hills and trees in the foreground
        int highest = Integer.MIN_VALUE;
        for (int d = 0; d <= 32; d += 4) {
            highest = Math.max(highest, surface(Mth.floor(camX + dx * d), Mth.floor(camZ + dz * d)));
        }
        double camY = Math.max(highest + CAMERA_ABOVE, generator.getSeaLevel() + WATER_CAMERA_ABOVE);
        return new Spot(camX, camY, camZ, bestYaw, PITCH, false);
    }

    private int surface(int x, int z) {
        return generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
    }

    private boolean isTarget(int x, int y, int z) {
        return source.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), sampler).is(key);
    }
}
