# Biome Pick'n'Choose [![Publish](https://github.com/Jesper-Andersson/biomepicknchoose/actions/workflows/publish.yml/badge.svg?branch=master)](https://github.com/Jesper-Andersson/biomepicknchoose/actions/workflows/publish.yml)

Available on [Modrinth](https://modrinth.com/mod/biome-picknchoose) and [CurseForge](https://www.curseforge.com/minecraft/mc-mods/biome-picknchoose).
Turn individual overworld biomes on and off, from vanilla and other mods.

Requires Minecraft 1.21.1 and either NeoForge 21.1+ or Fabric with Fabric API.

<img width="2258" height="1364" alt="image" src="https://github.com/user-attachments/assets/0438277e-343b-4aba-aabc-a9c3ae83c791" />


## Features

- **Biome toggles.** Turn any overworld biome on or off, including biomes added by other mods. A disabled biome is replaced by the **nearest enabled biome by climate**, so terrain still blends naturally. Only the overworld is affected.
- **Applies to new chunks.** Changes take effect after `/reload` or on the next world load, and only affect chunks generated after that. Chunks that already exist are not changed.
- **In-game config menu.** Open it from Mods → Biome Pick'n'Choose → Config. On Fabric, this needs [Mod Menu](https://modrinth.com/mod/modmenu).
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
  - Presets use the same format as the config, so a preset file can be copied over `config/biomepicknchoose.json`, on either loader, client or server.
- **Biome preview pictures.** These are client commands for singleplayer only:
  - `/biomepick_preview capture [namespace]` photographs each biome. It finds a spot, teleports there in spectator mode, waits for chunks to load, and takes a screenshot without the HUD. Progress and time left show while it runs.
  - `/biomepick_preview capture_missing [namespace]` only captures biomes that don't have a picture yet.
  - `/biomepick_preview cancel` stops a capture.
  - Pictures are saved to `config/biomepicknchoose/biome_previews/<namespace>/<biome>.png`. Each one can be removed from the menu ("Remove picture").
- **Server-side.** It works on dedicated servers, and players don't need the mod to join. Biome choice happens during server worldgen. Set biomes via the config file (see [Configuration](#configuration)), or from a client:
  - `/biomepicknchoose config` (operators, permission level 2) opens the config menu with the server's biomes and config. It needs the mod on the client too.
  - Done saves the server's `config/biomepicknchoose.json`. Run `/reload` to apply it.
- **Mod compatibility.**
  - TerraBlender regions: replacement wraps the whole biome lookup.
  - Lithostitched biome injectors: a compat mixin is applied only when Lithostitched is installed.
  - Blueprint's `ModdedBiomeSource`: unwrapped by reflection.
- **Languages:** English, Swedish.

## Configuration

Biomes are set in `config/biomepicknchoose.json`. The file is the same on clients and servers, and shares the same format:

```json
{
  "biomes": {
    "minecraft:dark_forest": false,
    "minecraft:forest": true,
    "minecraft:plains": false
  }
}
```

Each biome set to `false` is replaced by the nearest enabled biome by climate. Biomes that aren't listed stay enabled. Changes apply to newly generated chunks after `/reload` or on the next world load.

Configs from older versions (`biomepicknchoose-common.toml` on NeoForge, `biomepicknchoose-common.json` on Fabric) are moved over automatically on the first start, and renamed to `.old`.

Other files under `config/`:

| Path | Contents |
| --- | --- |
| `biomepicknchoose-known-biomes.json` | Cache of every overworld biome seen, so the menu can list modded biomes outside a world |
| `biomepicknchoose/presets/` | Saved presets (JSON) |
| `biomepicknchoose/biome_previews/` | Captured preview pictures |

## For mod developers

On NeoForge, to list your biomes in the menu before any world has been loaded, send an IMC message to `biomepicknchoose` with method `register_biomes` during `InterModEnqueueEvent`. The payload is a `Collection` of `ResourceLocation`, `ResourceKey`, or id `String`:

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

The project follows the [MultiLoader Template](https://github.com/jaredlll08/MultiLoader-Template): shared code is in `common/`, and loader specific code in `neoforge/` and `fabric/`. The jars are written to `neoforge/build/libs/` and `fabric/build/libs/`.

## License

CC0 1.0 Universal
