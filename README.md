# Halite

A Sodium-style rendering engine for **Minecraft Beta 1.7.3** on **Babric + StationAPI**.

Halite replaces the vanilla terrain renderer (display lists, per-chunk occlusion queries, an unculled block
entity list) with the architecture popularised by Sodium:

- **Graph-based occlusion culling.** Sections that are hidden behind solid terrain are never drawn, and the
  search only walks outwards from the camera through faces that are actually connected.
- **Compact vertex buffers.** Chunk meshes use 16-byte vertices (half of vanilla's) in VBOs, drawn with a
  shared quad index buffer.
- **Face culling.** Geometry is grouped by facing; groups that face away from the camera are not submitted.
- **Translucency sorting.** Water and other translucent geometry is sorted back to front per section.
- **Entity, block entity and particle culling.** Only things in visible sections, or inside the view
  frustum, get rendered.
- **Configurable render distance** (2–32 chunks), with fog that follows it.
- **Frame-budgeted chunk rebuilds**, nearest visible sections first.
- **Lower memory and startup cost**: the 786k display lists and 262k occlusion queries vanilla reserves at
  startup are skipped.

### Compatibility

- Requires StationAPI. Every block is still meshed through the regular (vanilla/StationAPI) block renderers,
  so modded blocks and StationAPI block models keep working.
- Only uses OpenGL features available on OpenGL 1.5 and GLES 2 translation layers, so it also runs in
  **PojavLauncher** (gl4es and similar). If buffer objects misbehave on a driver, turn off **VBOs** in the
  settings to fall back to client-side vertex arrays.
- Mods that replace `WorldRenderer`'s chunk rendering themselves (other renderer/optimisation mods) are not
  compatible.

### Settings

*Options → Video Settings → Halite Settings...* The settings are also stored in `config/halite.properties`.

| Setting | Default | Notes |
|---|---|---|
| Render Distance | Vanilla | `Vanilla` follows the vanilla button (Far = 16, Normal = 8, Short = 4, Tiny = 2 chunks) |
| Occlusion Culling | ON | |
| Face Culling | ON | |
| Entity / Block Entity / Particle Culling | ON | |
| Translucency Sorting | ON | |
| Build Budget | 4 ms | Time per frame spent rebuilding chunk meshes |
| VBOs | ON | Requires a restart |

## Building

```sh
./gradlew build          # jar in build/libs/
./gradlew runClient      # development client
```

Gradle itself needs Java 21; the mod targets Java 17.

`./gradlew runAutoWorldClient` starts a client that opens a test world straight away, slowly turns the camera,
logs renderer statistics and saves screenshots (see `MinecraftDebugMixin`). That is only for development and
does nothing in normal play.

## License and credits

Halite is a derivative work of [Sodium](https://github.com/CaffeineMC/sodium) **0.5.11** by JellySquid and the
CaffeineMC contributors, which was released under the GNU LGPLv3. Halite is distributed under the same license;
see [`COPYING`](COPYING), [`COPYING.LESSER`](COPYING.LESSER) and [`NOTICE.md`](NOTICE.md).

Halite is **not** affiliated with or endorsed by CaffeineMC. Please don't report Halite issues to the Sodium
issue tracker.
