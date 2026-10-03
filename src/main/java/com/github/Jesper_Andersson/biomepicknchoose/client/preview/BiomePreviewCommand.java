package com.github.Jesper_Andersson.biomepicknchoose.client.preview;

import com.github.Jesper_Andersson.biomepicknchoose.BiomePickNChoose;
import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeToggles;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /biomepick_preview capture [namespace]} photographs overworld biomes for the biome menu,
 * {@code /biomepick_preview capture_missing [namespace]} only those without a shipped or captured picture, and
 * {@code /biomepick_preview cancel} stops a run and restores the player.
 */
@EventBusSubscriber(modid = BiomePickNChoose.MODID, value = Dist.CLIENT)
public final class BiomePreviewCommand {
    private BiomePreviewCommand() {}

    @SubscribeEvent
    static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("biomepick_preview")
                .then(captureCommand("capture", false))
                .then(captureCommand("capture_missing", true))
                .then(Commands.literal("cancel")
                        .executes(BiomePreviewCommand::cancel)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> captureCommand(String name, boolean missingOnly) {
        return Commands.literal(name)
                .executes(context -> capture(context, null, missingOnly))
                .then(Commands.argument("namespace", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                BiomeToggles.knownBiomes().stream().map(ResourceLocation::getNamespace).distinct(), builder))
                        .executes(context -> capture(context, StringArgumentType.getString(context, "namespace"), missingOnly)));
    }

    private static int capture(CommandContext<CommandSourceStack> context, @Nullable String namespace, boolean missingOnly) {
        Component error = BiomePreviewCapture.start(namespace, missingOnly);
        if (error != null) {
            context.getSource().sendFailure(error);
            return 0;
        }
        return 1;
    }

    private static int cancel(CommandContext<CommandSourceStack> context) {
        if (BiomePreviewCapture.cancel()) return 1;
        context.getSource().sendFailure(Component.translatable("biomepicknchoose.command.preview.not_running"));
        return 0;
    }
}
