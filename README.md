# Biome Pick'n'Choose

[![Modrinth](https://img.shields.io/modrinth/dt/biome-picknchoose?logo=modrinth&label=Modrinth)](https://modrinth.com/mod/biome-picknchoose)
[![CurseForge](https://img.shields.io/curseforge/dt/1724905?logo=curseforge&label=CurseForge)](https://www.curseforge.com/minecraft/mc-mods/biome-picknchoose)

Turn any biome on or off, vanilla or modded, in any dimension. Disabled biomes are replaced by the closest match, so terrain still blends naturally.

<img width="2258" height="1364" alt="The Biome Pick'n'Choose config menu" src="https://github.com/user-attachments/assets/0438277e-343b-4aba-aabc-a9c3ae83c791" />

## Download

Get it from [Modrinth](https://modrinth.com/mod/biome-picknchoose) or [CurseForge](https://www.curseforge.com/minecraft/mc-mods/biome-picknchoose).

Requires Minecraft 1.21.1 with NeoForge 21.1+ or Fabric with Fabric API. On Fabric, [Mod Menu](https://modrinth.com/mod/modmenu) is needed to open the config screen.

## Features

- **Toggle any biome** in the overworld, the Nether, the End and modded dimensions.
- **In-game menu** with search, a tab per mod, bulk on/off and biome previews.
- **Presets** you can save, load and share as JSON files.
- **Works on servers.** Players don't need the mod to join.
- **Compatible** with TerraBlender, Lithostitched and Blueprint.

Changes apply to **newly generated chunks** after `/reload` or a world reload. Existing chunks aren't touched.

## Configuration

Open the menu from **Mods → Biome Pick'n'Choose → Config**. On a server, operators can run `/biomepicknchoose config` (needs the mod on the client).

Or edit `config/biomepicknchoose.json` directly. Unlisted biomes stay enabled:

```json
{
  "biomes": {
    "minecraft:dark_forest": false,
    "minecraft:plains": false
  }
}
```

Presets use the same format, so a preset file can be dropped in as the config on any loader.

<details>
<summary>Biome preview pictures</summary>

The side panel can show a picture of each biome. To capture them in singleplayer:

- `/biomepick_preview capture [namespace]` photographs every biome.
- `/biomepick_preview capture_missing [namespace]` only does the ones without a picture.
- `/biomepick_preview cancel` stops a capture.

Pictures are saved in `config/biomepicknchoose/biome_previews/`.
</details>

<details>
<summary>How replacement works</summary>

- **Overworld, Nether and other multi-noise dimensions:** a disabled biome becomes the nearest enabled biome by climate.
- **The End:** a disabled outer biome becomes the nearest enabled one by erosion.
- **Dimensions with their own biome source** (like Twilight Forest) or a single fixed biome aren't affected. The log warns if one of them could place a disabled biome.
</details>

## For developers

<details>
<summary>Register biomes before a world loads (NeoForge)</summary>

Modded biomes show up in the menu after a world has loaded once. To list yours earlier, send an IMC message:

```java
@SubscribeEvent
static void enqueueIMC(InterModEnqueueEvent event) {
    InterModComms.sendTo("biomepicknchoose", "register_biomes", () -> List.of(
            ResourceLocation.fromNamespaceAndPath("mymod", "crystal_fields")));
}
```

The payload can be a `Collection` of `ResourceLocation`, `ResourceKey` or `String`.
</details>

<details>
<summary>Check if a biome is disabled</summary>

```java
BiomeToggles.isDisabled(Holder<Biome>)
BiomeToggles.isDisabled(ResourceKey<Biome>)
```
</details>

### Building

```sh
./gradlew build
```

Jars end up in `neoforge/build/libs/` and `fabric/build/libs/`. The project uses the [MultiLoader Template](https://github.com/jaredlll08/MultiLoader-Template).

## License

[MIT](LICENSE)
