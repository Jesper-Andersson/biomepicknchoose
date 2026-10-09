package com.github.Jesper_Andersson.biomepicknchoose.client.preview;

import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeDimension;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * A coarse map of the biomes around spawn in one dimension, sampled once per run so each biome's spot search can start
 * from known patches instead of searching outward from spawn on its own. The Nether and the End are scanned around
 * their origin. Only reads the biome source, so it is as thread safe as {@link BiomeSpotFinder}.
 */
final class BiomeScan {
    private static final int SCAN_RADIUS = 4096;
    private static final int SCAN_STEP = 32;
    private static final int SIZE = SCAN_RADIUS / SCAN_STEP * 2 + 1;
    // Block heights relative to sea level to sample at. The ones below it find cave biomes, down to the deep dark
    private static final int[] OVERWORLD_HEIGHTS = {-112, -80, -48, -16, 0, 32, 64, 112};
    private static final int OVERWORLD_SURFACE_LAYER = 4;
    // Above the lava sea at 32 and below the bedrock roof at 128, all of it underground
    private static final int[] NETHER_HEIGHTS = {8, 24, 40, 56, 72};
    // The outer End biomes don't change with height
    private static final int[] END_HEIGHTS = {64};
    private static final int ROWS_PER_TASK = 8;

    record Candidate(BlockPos pos, int neighbours, double distance) {}

    private final BlockPos center;
    private final int seaLevel;
    private final int[] heights;
    // Heights before this index are underground, where cave biomes are looked for
    private final int surfaceLayer;
    private final List<Holder<Biome>> biomes;
    private final Map<Holder<Biome>, Short> indices = new HashMap<>();
    // One grid per height, row major in z then x, -1 for biomes outside the source's possible biomes
    private final short[][] grid;

    private BiomeScan(ServerLevel level, BiomeDimension dimension) {
        this.center = center(level, dimension);
        this.seaLevel = level.getChunkSource().getGenerator().getSeaLevel();
        if (dimension.equals(BiomeDimension.NETHER)) {
            this.heights = NETHER_HEIGHTS;
            this.surfaceLayer = NETHER_HEIGHTS.length;
        } else if (dimension.equals(BiomeDimension.END)) {
            this.heights = END_HEIGHTS;
            this.surfaceLayer = 0;
        } else {
            // Other dimensions are scanned like the overworld, since most of them have a surface
            this.heights = OVERWORLD_HEIGHTS;
            this.surfaceLayer = OVERWORLD_SURFACE_LAYER;
        }
        this.grid = new short[heights.length][SIZE * SIZE];
        this.biomes = List.copyOf(level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes());
        for (int i = 0; i < biomes.size(); i++) indices.put(biomes.get(i), (short) i);
    }

    static int columns() {
        return SIZE * SIZE;
    }

    BlockPos center() {
        return center;
    }

    /** Where the search starts: spawn in the overworld, the origin elsewhere. */
    static BlockPos center(ServerLevel level, BiomeDimension dimension) {
        return dimension.equals(BiomeDimension.OVERWORLD) ? level.getRespawnData().pos() : BlockPos.ZERO;
    }

    /** Scans in row batches spread over the pool; progress gets the percentage of rows done. */
    static CompletableFuture<BiomeScan> run(ServerLevel level, BiomeDimension dimension, ExecutorService pool, IntConsumer progress) {
        BiomeScan scan = new BiomeScan(level, dimension);
        BiomeSource source = level.getChunkSource().getGenerator().getBiomeSource();
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        AtomicInteger rowsDone = new AtomicInteger();
        List<CompletableFuture<Void>> tasks = new ArrayList<>();
        for (int from = 0; from < SIZE; from += ROWS_PER_TASK) {
            int start = from;
            int end = Math.min(SIZE, from + ROWS_PER_TASK);
            tasks.add(CompletableFuture.runAsync(() -> {
                for (int row = start; row < end; row++) {
                    if (Thread.currentThread().isInterrupted()) return;
                    scan.scanRow(source, sampler, row);
                    progress.accept(rowsDone.incrementAndGet() * 100 / SIZE);
                }
            }, pool));
        }
        return CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).thenApply(ignored -> scan);
    }

    private void scanRow(BiomeSource source, Climate.Sampler sampler, int row) {
        int qz = QuartPos.fromBlock(center.getZ() - SCAN_RADIUS + row * SCAN_STEP);
        for (int h = 0; h < heights.length; h++) {
            int qy = QuartPos.fromBlock(seaLevel + heights[h]);
            for (int col = 0; col < SIZE; col++) {
                int qx = QuartPos.fromBlock(center.getX() - SCAN_RADIUS + col * SCAN_STEP);
                grid[h][row * SIZE + col] = indices.getOrDefault(source.getNoiseBiome(qx, qy, qz, sampler), (short) -1);
            }
        }
    }

    /**
     * Every column where the biome was seen, most surrounded first, then nearest to spawn. Cave biomes, and every
     * Nether biome, are looked for underground, other biomes at and above sea level.
     */
    List<Candidate> candidates(ResourceKey<Biome> key, boolean cave) {
        short target = -1;
        for (int i = 0; i < biomes.size(); i++) {
            if (biomes.get(i).is(key)) target = (short) i;
        }
        List<Candidate> candidates = new ArrayList<>();
        if (target < 0) return candidates;
        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                // A column can match at several heights; keep the height where it is most surrounded
                int bestNeighbours = -1;
                int bestHeight = 0;
                for (int h = cave ? 0 : surfaceLayer; h < (cave ? surfaceLayer : heights.length); h++) {
                    short[] layer = grid[h];
                    if (layer[row * SIZE + col] != target) continue;
                    int neighbours = 0;
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int r = row + dz;
                            int c = col + dx;
                            if ((dx != 0 || dz != 0) && r >= 0 && r < SIZE && c >= 0 && c < SIZE
                                    && layer[r * SIZE + c] == target) neighbours++;
                        }
                    }
                    if (neighbours > bestNeighbours) {
                        bestNeighbours = neighbours;
                        bestHeight = seaLevel + heights[h];
                    }
                }
                if (bestNeighbours < 0) continue;
                BlockPos pos = new BlockPos(center.getX() - SCAN_RADIUS + col * SCAN_STEP, bestHeight,
                        center.getZ() - SCAN_RADIUS + row * SCAN_STEP);
                double distance = Math.sqrt(pos.distSqr(center.atY(bestHeight)));
                candidates.add(new Candidate(pos, bestNeighbours, distance));
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::neighbours).reversed().thenComparingDouble(Candidate::distance));
        return candidates;
    }
}
