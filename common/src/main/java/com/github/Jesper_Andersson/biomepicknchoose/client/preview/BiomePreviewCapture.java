package com.github.Jesper_Andersson.biomepicknchoose.client.preview;

import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeToggles;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.storage.ServerLevelData;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Photographs overworld biomes one at a time: locate a spot on a background thread, teleport there in spectator mode, wait
 * for the chunks to render, then screenshot without the HUD. Singleplayer only, since it drives the integrated server.
 */
public final class BiomePreviewCapture {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int WIDTH = 480;
    private static final int HEIGHT = 270;
    private static final int SETTLE_TICKS = 20;
    private static final int TIMEOUT_TICKS = 15 * 20;
    private static final long NOON = 6000;
    private static final int CAPTURE_RENDER_DISTANCE = 8;

    @Nullable
    private static BiomePreviewCapture active;

    private static final int STATUS_INTERVAL = 5;

    private enum Step { PREPARE, LOCATE, WAIT, CAPTURE }

    private enum WaitKind { MENU, TELEPORT, CHUNKS, RENDER, SETTLE }

    /** What WAIT is held up by; loaded and total are only set for CHUNKS. */
    private record WaitStatus(WaitKind kind, int loaded, int total) {
        boolean ready() {
            return kind == WaitKind.SETTLE;
        }
    }

    private record Saved(ResourceKey<Level> dimension, double x, double y, double z, float yaw, float pitch,
                         GameType gameMode, boolean daylight, boolean weatherCycle, long dayTime,
                         int clearTime, int rainTime, boolean raining, boolean thundering) {}

    private final Minecraft minecraft;
    private final MinecraftServer server;
    private final UUID player;
    private final List<Holder<Biome>> biomes;
    private final boolean hideGui;
    private final boolean pauseOnLostFocus;
    private final int renderDistance;
    // Half the cores, leaving the rest to the integrated server's chunk generation
    private final ExecutorService locator = Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors() / 2), runnable -> {
                Thread thread = new Thread(runnable, "Biome Pick'n'Choose preview locator");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicInteger scanProgress = new AtomicInteger();
    private CompletableFuture<BiomeScan> scan;
    // Indexed like biomes, null for disabled biomes
    private final List<CompletableFuture<BiomeSpotFinder.Result>> searches = new ArrayList<>();
    private final List<ResourceLocation> captured = new ArrayList<>();
    private final List<Component> skipped = new ArrayList<>();
    private final List<ResourceLocation> timedOut = new ArrayList<>();
    // How long each captured biome took, for the time left estimate
    private final List<Long> durations = new ArrayList<>();

    private Step step = Step.PREPARE;
    private int index = -1;
    private CompletableFuture<Saved> prepare;
    @Nullable
    private Saved saved;
    private CompletableFuture<BiomeSpotFinder.Result> locate;
    private BiomeSpotFinder.Spot spot;
    private int waited;
    private int settled;
    private int frames;
    private long runStart;
    private long biomeStart;
    private long stepStart;
    // Restarts on step changes and on WAIT status changes, for the status line
    private long phaseStart;
    private long locateTime;
    private long waitTime;
    @Nullable
    private WaitStatus waitStatus;
    private int ticks;

    private BiomePreviewCapture(Minecraft minecraft, MinecraftServer server, List<Holder<Biome>> biomes) {
        this.minecraft = minecraft;
        this.server = server;
        this.player = minecraft.player.getUUID();
        this.biomes = biomes;
        this.hideGui = minecraft.options.hideGui;
        this.pauseOnLostFocus = minecraft.options.pauseOnLostFocus;
        this.renderDistance = minecraft.options.renderDistance().get();
    }

    /** Starts a run, or returns why it can't. With missingOnly, biomes that already have a picture are left out. */
    @Nullable
    static Component start(@Nullable String namespace, boolean missingOnly) {
        Minecraft minecraft = Minecraft.getInstance();
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null || minecraft.player == null) return Component.translatable("biomepicknchoose.command.preview.singleplayer_only");
        if (active != null) return Component.translatable("biomepicknchoose.command.preview.running");

        List<Holder<Biome>> biomes = server.overworld().getChunkSource().getGenerator().getBiomeSource().possibleBiomes().stream()
                .filter(biome -> biome.unwrapKey().isPresent())
                .filter(biome -> namespace == null || biome.unwrapKey().get().location().getNamespace().equals(namespace))
                .filter(biome -> !missingOnly || !BiomePreviews.hasPicture(biome.unwrapKey().get().location()))
                .sorted(Comparator.comparing(biome -> biome.unwrapKey().get().location()))
                .toList();
        if (biomes.isEmpty()) {
            return Component.translatable(missingOnly ? "biomepicknchoose.command.preview.none_missing" : "biomepicknchoose.command.preview.none");
        }

        BiomePreviewCapture capture = new BiomePreviewCapture(minecraft, server, biomes);
        capture.runStart = Util.getMillis();
        capture.setStep(Step.PREPARE);
        capture.prepare = server.submit(capture::prepareServer);
        // Scan the biomes around spawn once, then search every spot from it up front so later spots are ready while
        // earlier chunks load
        ServerLevel overworld = server.overworld();
        long scanStart = Util.getMillis();
        capture.scan = BiomeScan.run(overworld, capture.locator, 
                // Rows finish on several threads, so a lower percentage can arrive after a higher one
                percent -> capture.scanProgress.accumulateAndGet(percent, Math::max));
        capture.scan.thenRun(() -> LOGGER.info("Biome scan: {} columns in {}", BiomeScan.columns(),
                formatDuration(Util.getMillis() - scanStart)));
        for (Holder<Biome> biome : biomes) {
            capture.searches.add(BiomeToggles.isDisabled(biome) ? null
                    : capture.scan.thenApplyAsync(scan -> BiomeSpotFinder.find(overworld, biome, scan), capture.locator));
        }
        // Fewer chunks to generate and render per spot; the integrated server follows the client's view distance
        if (capture.renderDistance > CAPTURE_RENDER_DISTANCE) minecraft.options.renderDistance().set(CAPTURE_RENDER_DISTANCE);
        // An unfocused window would pause the integrated server and stall chunk generation
        minecraft.options.pauseOnLostFocus = false;
        active = capture;
        return null;
    }

    static boolean cancel() {
        BiomePreviewCapture capture = active;
        if (capture == null) return false;
        capture.end();
        capture.message(Component.translatable("biomepicknchoose.command.preview.cancelled"));
        return true;
    }

    /** Called by each loader at the end of every client tick. */
    public static void onClientTick() {
        BiomePreviewCapture capture = active;
        if (capture == null) return;
        if (capture.minecraft.level == null || capture.minecraft.getSingleplayerServer() != capture.server) {
            // World closed mid-run: nothing to restore on the server side any more
            capture.locator.shutdownNow();
            capture.restoreClient();
            active = null;
            return;
        }
        capture.tick();
    }

    /** Called by each loader after every rendered frame. */
    public static void onRenderFrame() {
        BiomePreviewCapture capture = active;
        if (capture != null && capture.step == Step.CAPTURE) capture.frame();
    }

    private void tick() {
        tickStep();
        if (active == this && step != Step.CAPTURE && ++ticks % STATUS_INTERVAL == 0) showStatus();
    }

    private void tickStep() {
        switch (step) {
            case PREPARE -> {
                if (!prepare.isDone()) return;
                saved = prepare.join();
                next();
            }
            case LOCATE -> {
                if (!locate.isDone()) return;
                BiomeSpotFinder.Result result;
                try {
                    result = locate.join();
                } catch (CompletionException e) {
                    LOGGER.warn("Couldn't locate a spot for biome preview {}", currentId(), e.getCause());
                    locateTime = Util.getMillis() - stepStart;
                    skip("not_found");
                    next();
                    return;
                }
                locateTime = Util.getMillis() - stepStart;
                if (result.spot() == null) {
                    String reason = result.failure() == BiomeSpotFinder.Failure.UNDERGROUND ? "underground" : "not_found";
                    skip(reason);
                    next();
                    return;
                }
                spot = result.spot();
                server.execute(() -> teleportServer(spot));
                waited = 0;
                settled = 0;
                waitStatus = null;
                setStep(Step.WAIT);
            }
            case WAIT -> {
                waited++;
                WaitStatus status = waitStatus();
                settled = status.ready() ? settled + 1 : 0;
                boolean changed = waitStatus == null || waitStatus.kind() != status.kind();
                waitStatus = status;
                if (changed) {
                    phaseStart = Util.getMillis();
                    showStatus();
                }
                if (settled >= SETTLE_TICKS) {
                    shoot();
                } else if (waited >= TIMEOUT_TICKS) {
                    LOGGER.warn("Biome preview for {} timed out, capturing anyway", currentId());
                    timedOut.add(currentId());
                    shoot();
                }
            }
            case CAPTURE -> {}
        }
    }

    private void next() {
        index++;
        if (index >= biomes.size()) {
            finish();
            return;
        }
        locate = searches.get(index);
        if (locate == null) {
            locateTime = 0;
            skip("disabled");
            next();
            return;
        }
        biomeStart = Util.getMillis();
        setStep(Step.LOCATE);
    }

    private void setStep(Step step) {
        this.step = step;
        stepStart = Util.getMillis();
        phaseStart = stepStart;
        if (step != Step.CAPTURE) showStatus();
    }

    private void skip(String reason) {
        LOGGER.info("Biome preview {}: skipped ({}), locate {}", currentId(), reason, formatDuration(locateTime));
        skipped.add(Component.translatable("biomepicknchoose.command.preview.reason." + reason, currentId().toString()));
    }

    private WaitStatus waitStatus() {
        var localPlayer = minecraft.player;
        if (minecraft.screen != null) return new WaitStatus(WaitKind.MENU, 0, 0);
        if (localPlayer == null || minecraft.level.dimension() != Level.OVERWORLD
                || localPlayer.distanceToSqr(spot.x(), spot.y(), spot.z()) > 4) {
            return new WaitStatus(WaitKind.TELEPORT, 0, 0);
        }
        int radius = Math.max(2, minecraft.options.getEffectiveRenderDistance() - 1);
        int cx = localPlayer.chunkPosition().x;
        int cz = localPlayer.chunkPosition().z;
        int loaded = 0;
        int total = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                total++;
                if (minecraft.level.getChunkSource().hasChunk(cx + dx, cz + dz)) loaded++;
            }
        }
        if (loaded < total) return new WaitStatus(WaitKind.CHUNKS, loaded, total);
        if (!minecraft.levelRenderer.hasRenderedAllSections()) return new WaitStatus(WaitKind.RENDER, 0, 0);
        return new WaitStatus(WaitKind.SETTLE, 0, 0);
    }

    private void shoot() {
        waitTime = Util.getMillis() - stepStart;
        minecraft.options.hideGui = true;
        frames = 0;
        setStep(Step.CAPTURE);
    }

    private void showStatus() {
        long now = Util.getMillis();
        String biome = index >= 0 && index < biomes.size() ? currentId().toString() : "-";
        message(Component.translatable("biomepicknchoose.command.preview.status", Math.max(index + 1, 0), biomes.size(), biome,
                stepText(), formatDuration(now - phaseStart), eta(now)), true);
    }

    private Component stepText() {
        return switch (step) {
            case PREPARE -> Component.translatable("biomepicknchoose.command.preview.step.prepare");
            case LOCATE -> scan.isDone() ? Component.translatable("biomepicknchoose.command.preview.step.locate")
                    : Component.translatable("biomepicknchoose.command.preview.step.scan", scanProgress.get());
            case CAPTURE -> Component.translatable("biomepicknchoose.command.preview.step.capture");
            case WAIT -> {
                WaitStatus status = waitStatus;
                if (status == null) yield Component.translatable("biomepicknchoose.command.preview.step.teleport");
                yield switch (status.kind()) {
                    case MENU -> Component.translatable("biomepicknchoose.command.preview.step.menu");
                    case TELEPORT -> Component.translatable("biomepicknchoose.command.preview.step.teleport");
                    case CHUNKS -> Component.translatable("biomepicknchoose.command.preview.step.chunks", status.loaded(), status.total());
                    case RENDER -> Component.translatable("biomepicknchoose.command.preview.step.render");
                    case SETTLE -> Component.translatable("biomepicknchoose.command.preview.step.settle", settled, SETTLE_TICKS);
                };
            }
        };
    }

    // Average capture time for each biome still to come, plus whatever is left of the average for the current one
    private Component eta(long now) {
        if (durations.isEmpty()) return Component.translatable("biomepicknchoose.command.preview.eta_unknown");
        long average = durations.stream().mapToLong(Long::longValue).sum() / durations.size();
        int left = 0;
        for (int i = Math.max(index + 1, 0); i < searches.size(); i++) {
            if (searches.get(i) != null) left++;
        }
        long current = Math.max(0, average - (now - biomeStart));
        return Component.translatable("biomepicknchoose.command.preview.eta", formatDuration(average * left + current));
    }

    private static String formatDuration(long ms) {
        if (ms < 60_000) return String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
        long seconds = ms / 1000;
        if (seconds < 3600) return seconds / 60 + "m " + seconds % 60 + "s";
        return seconds / 3600 + "h " + seconds % 3600 / 60 + "m";
    }

    // The first frame after hiding the HUD may have started before the tick that hid it
    private void frame() {
        if (++frames < 2) return;
        ResourceLocation id = currentId();
        NativeImage screen = Screenshot.takeScreenshot(minecraft.getMainRenderTarget());
        minecraft.options.hideGui = false;
        NativeImage preview = new NativeImage(WIDTH, HEIGHT, false);
        int w = screen.getWidth();
        int h = screen.getHeight();
        int cropW = Math.min(w, h * 16 / 9);
        int cropH = Math.min(h, w * 9 / 16);
        screen.resizeSubRectTo((w - cropW) / 2, (h - cropH) / 2, cropW, cropH, preview);
        screen.close();
        Path file = BiomePreviews.captureFile(id);
        Util.ioPool().execute(() -> {
            try {
                Files.createDirectories(file.getParent());
                preview.writeToFile(file);
            } catch (IOException e) {
                LOGGER.warn("Couldn't write biome preview {}", file, e);
            } finally {
                preview.close();
            }
        });
        captured.add(id);
        durations.add(Util.getMillis() - biomeStart);
        LOGGER.info("Biome preview {}: locate {}, wait {}", id, formatDuration(locateTime), formatDuration(waitTime));
        next();
    }

    private void finish() {
        end();
        Path dir = BiomePreviews.captureDir();
        Component folder = Component.literal(dir.toString()).withStyle(style -> style
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, dir.toAbsolutePath().toString())));
        message(Component.translatable("biomepicknchoose.command.preview.done", captured.size(), biomes.size(), folder,
                formatDuration(Util.getMillis() - runStart)));
        if (!skipped.isEmpty()) {
            message(Component.translatable("biomepicknchoose.command.preview.skipped",
                    ComponentUtils.formatList(skipped, Component.literal(", "))).withStyle(ChatFormatting.GRAY));
        }
        if (!timedOut.isEmpty()) {
            message(Component.translatable("biomepicknchoose.command.preview.timed_out",
                    ComponentUtils.formatList(timedOut, id -> Component.literal(id.toString()))).withStyle(ChatFormatting.YELLOW));
        }
    }

    private void end() {
        active = null;
        locator.shutdownNow();
        restoreClient();
        BiomePreviews.release();
        Saved state = saved;
        if (state != null) {
            server.execute(() -> restoreServer(state));
        } else if (prepare != null) {
            // Cancelled before the server saved its state: restore once it has
            prepare.thenAccept(state2 -> server.execute(() -> restoreServer(state2)));
        }
    }

    private void restoreClient() {
        minecraft.options.hideGui = hideGui;
        minecraft.options.pauseOnLostFocus = pauseOnLostFocus;
        minecraft.options.renderDistance().set(renderDistance);
    }

    private ResourceLocation currentId() {
        return biomes.get(index).unwrapKey().orElseThrow().location();
    }

    private void message(Component component) {
        message(component, false);
    }

    private void message(Component component, boolean actionBar) {
        if (minecraft.player != null) minecraft.player.displayClientMessage(component, actionBar);
    }

    // Server thread

    private Saved prepareServer() {
        ServerLevel overworld = server.overworld();
        ServerPlayer serverPlayer = server.getPlayerList().getPlayer(player);
        GameRules rules = overworld.getGameRules();
        ServerLevelData data = server.getWorldData().overworldData();
        Saved state = new Saved(serverPlayer.level().dimension(), serverPlayer.getX(), serverPlayer.getY(), serverPlayer.getZ(),
                serverPlayer.getYRot(), serverPlayer.getXRot(), serverPlayer.gameMode.getGameModeForPlayer(),
                rules.getBoolean(GameRules.RULE_DAYLIGHT), rules.getBoolean(GameRules.RULE_WEATHER_CYCLE), overworld.getDayTime(),
                data.getClearWeatherTime(), data.getRainTime(), data.isRaining(), data.isThundering());
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        overworld.setWeatherParameters(0, 0, false, false);
        overworld.setDayTime(NOON);
        return state;
    }

    private void teleportServer(BiomeSpotFinder.Spot target) {
        ServerPlayer serverPlayer = server.getPlayerList().getPlayer(player);
        if (serverPlayer == null) return;
        ServerLevel overworld = server.overworld();
        serverPlayer.setGameMode(GameType.SPECTATOR);
        serverPlayer.teleportTo(overworld, target.x(), target.y(), target.z(), target.yaw(), target.pitch());
        overworld.setDayTime(NOON);
        overworld.setWeatherParameters(0, 0, false, false);
    }

    private void restoreServer(Saved state) {
        ServerLevel overworld = server.overworld();
        GameRules rules = overworld.getGameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(state.daylight(), server);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(state.weatherCycle(), server);
        overworld.setDayTime(state.dayTime());
        overworld.setWeatherParameters(state.clearTime(), state.rainTime(), state.raining(), state.thundering());
        ServerPlayer serverPlayer = server.getPlayerList().getPlayer(player);
        ServerLevel level = server.getLevel(state.dimension());
        if (serverPlayer == null || level == null) return;
        serverPlayer.teleportTo(level, state.x(), state.y(), state.z(), state.yaw(), state.pitch());
        serverPlayer.setGameMode(state.gameMode());
    }
}
