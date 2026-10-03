# Biome Pick'n'Choose

Turn individual overworld biomes on and off, from vanilla and other mods.

Requires Minecraft 1.21.1 and NeoForge 21.1+.

## Features

- **Biome toggles.** Turn any overworld biome on or off, including biomes added by other mods. A disabled biome is replaced by the **nearest enabled biome by climate**, so terrain still blends naturally. Only the overworld is affected.
- **Applies to new chunks.** Changes take effect on the next world load and only affect chunks generated after that. Chunks that already exist are not changed.
- **In-game config menu.** Open it from Mods → Biome Pick'n'Choose → Config.
  - One tab per mod, with Minecraft first.
  - A green On / red Off toggle per biome. Hovering a toggle shows the biome id.
  - Enable all / Disable all buttons for each tab.
  - A sort button: A-Z, On first, or Off first.
  - A side panel that previews the hovered biome (on wide screens).
  - Done saves; Cancel discards.
  - Note: biomes from other mods show up after you've loaded a world once. They are cached in `config/biomepicknchoose-known-biomes.json`.
- **Presets.**
  - Save, load, and delete named on/off sets.
  - Stored as JSON in `config/biomepicknchoose/presets/`.
  - Browse Folder opens that folder. Files you drop in appear in the list automatically.
  - Presets keep entries for mods that aren't installed. When loading, only biomes that are known now are applied.
- **Biome preview pictures.** These are client commands for singleplayer only:
  - `/biomepick_preview capture [namespace]` photographs each biome. It finds a spot, teleports there in spectator mode, waits for chunks to load, and takes a screenshot without the HUD. Progress and time left show while it runs.
  - `/biomepick_preview capture_missing [namespace]` only captures biomes that don't have a picture yet.
  - `/biomepick_preview cancel` stops a capture.
  - Pictures are saved to `config/biomepicknchoose/biome_previews/<namespace>/<biome>.png`. Each one can be removed from the menu ("Remove picture").
- **Server-side.** It works on dedicated servers, and players don't need the mod to join. Biome choice happens during server worldgen. Set biomes via `config/biomepicknchoose-common.toml`.
- **Mod compatibility.**
  - TerraBlender regions: replacement wraps the whole biome lookup.
  - Lithostitched biome injectors: a compat mixin is applied only when Lithostitched is installed.
  - Blueprint's `ModdedBiomeSource`: unwrapped by reflection.
- **Languages:** English, Swedish.

## Configuration

Disabled biomes are stored in `config/biomepicknchoose-common.toml`:

```toml
disabledBiomes = ["minecraft:plains", "minecraft:dark_forest"]
```

Each listed biome is replaced by the nearest enabled biome by climate. The list applies to newly generated chunks on the next world load.

Other files under `config/`:

| Path | Contents |
| --- | --- |
| `biomepicknchoose-known-biomes.json` | Cache of every overworld biome seen, so the menu can list modded biomes outside a world |
| `biomepicknchoose/presets/` | Saved presets (JSON) |
| `biomepicknchoose/biome_previews/` | Captured preview pictures |

## For mod developers

To list your biomes in the menu before any world has been loaded, send an IMC message to `biomepicknchoose` with method `register_biomes` during `InterModEnqueueEvent`. The payload is a `Collection` of `ResourceLocation`, `ResourceKey`, or id `String`:

```java
@SubscribeEvent
static void enqueueIMC(InterModEnqueueEvent event) {
    InterModComms.sendTo("biomepicknchoose", "register_biomes", () -> List.of(
            ResourceLocation.fromNamespaceAndPath("mymod", "crystal_fields"),
            ResourceLocation.fromNamespaceAndPath("mymod", "ash_plains")));
}
```

To check whether a biome is disabled, use the stable API in `BiomeToggles`:

- `BiomeToggles.isDisabled(Holder<Biome>)`
- `BiomeToggles.isDisabled(ResourceKey<Biome>)`

## Building

```sh
./gradlew build
```

The jar is written to `build/libs/`.

## License

All Rights Reserved.
