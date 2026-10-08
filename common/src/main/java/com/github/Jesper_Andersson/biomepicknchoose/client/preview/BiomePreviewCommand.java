package com.github.Jesper_Andersson.biomepicknchoose.client.preview;

import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeToggles;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiConsumer;

/**
 * {@code /biomepick_preview capture [namespace]} photographs overworld biomes for the biome menu,
 * {@code /biomepick_preview capture_missing [namespace]} only those without a shipped or captured picture, and
 * {@code /biomepick_preview cancel} stops a run and restores the player.
 * <p>
 * Generic over the command source, since each loader has its own client command source. sendFailure shows an error
 * to the player.
 */
public final class BiomePreviewCommand<S> {
    private final BiConsumer<S, Component> sendFailure;

    private BiomePreviewCommand(BiConsumer<S, Component> sendFailure) {
        this.sendFailure = sendFailure;
    }

    public static <S> LiteralArgumentBuilder<S> create(BiConsumer<S, Component> sendFailure) {
        BiomePreviewCommand<S> command = new BiomePreviewCommand<>(sendFailure);
        return LiteralArgumentBuilder.<S>literal("biomepick_preview")
                .then(command.captureCommand("capture", false))
                .then(command.captureCommand("capture_missing", true))
                .then(LiteralArgumentBuilder.<S>literal("cancel")
                        .executes(command::cancel));
    }

    private LiteralArgumentBuilder<S> captureCommand(String name, boolean missingOnly) {
        return LiteralArgumentBuilder.<S>literal(name)
                .executes(context -> capture(context, null, missingOnly))
                .then(RequiredArgumentBuilder.<S, String>argument("namespace", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                BiomeToggles.knownBiomes().stream().map(ResourceLocation::getNamespace).distinct(), builder))
                        .executes(context -> capture(context, StringArgumentType.getString(context, "namespace"), missingOnly)));
    }

    private int capture(CommandContext<S> context, @Nullable String namespace, boolean missingOnly) {
        Component error = BiomePreviewCapture.start(namespace, missingOnly);
        if (error != null) {
            sendFailure.accept(context.getSource(), error);
            return 0;
        }
        return 1;
    }

    private int cancel(CommandContext<S> context) {
        if (BiomePreviewCapture.cancel()) return 1;
        sendFailure.accept(context.getSource(), Component.translatable("biomepicknchoose.command.preview.not_running"));
        return 0;
    }
}
