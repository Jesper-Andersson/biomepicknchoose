package com.github.Jesper_Andersson.biomepicknchoose.client.gui;

import com.github.Jesper_Andersson.biomepicknchoose.common.BiomeDimension;
import com.github.Jesper_Andersson.biomepicknchoose.common.ServerConfigEditing;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** The client side of {@link ServerConfigEditing}. Only loaded on clients. */
public final class ServerConfigClient {
    private ServerConfigClient() {}

    /** Called on the client thread when the server sends its config. */
    public static void open(ServerConfigEditing.OpenPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        Set<Identifier> caves = parse(payload.caves());
        Set<Identifier> water = parse(payload.water());
        minecraft.setScreen(BiomeToggleScreen.forServer(minecraft.screen, byDimension(payload.known()), byDimension(payload.generating()),
                payload.disabled(), caves, water, disabled -> {
            ClientPacketListener connection = minecraft.getConnection();
            if (connection != null) connection.send(new ServerboundCustomPayloadPacket(new ServerConfigEditing.SavePayload(disabled)));
        }));
    }

    private static Map<BiomeDimension, Set<Identifier>> byDimension(Map<String, List<String>> lists) {
        Map<BiomeDimension, Set<Identifier>> result = new TreeMap<>();
        lists.forEach((key, ids) -> {
            BiomeDimension dimension = BiomeDimension.byKey(key);
            if (dimension != null) result.put(dimension, parse(ids));
        });
        return result;
    }

    private static Set<Identifier> parse(List<String> ids) {
        return ids.stream()
                .map(Identifier::tryParse)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
