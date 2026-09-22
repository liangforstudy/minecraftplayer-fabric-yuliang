# Draft bug report

Both Moonlight and Supplementaries are by **MehVahdJukaar**, so one report reaches the right
person either way. The crash is thrown inside Moonlight, so
[MehVahdJukaar/Moonlight](https://github.com/MehVahdJukaar/Moonlight/issues) is the better home;
Supplementaries is just the mod that calls it.

Edit freely — it's meant to sound like you, and it deliberately reports the *problem* rather
than proposing a fix. If they ask how you worked around it, that's the moment to mention the
patch, not before.

---

**Title:** `parsePaletteStrip` crashes when images can't be decoded (headless clients)

---

I run a Minecraft client headlessly using [HeadlessMC](https://github.com/headlesshq/headlessmc)
— it launches the game with no window and no graphics, for bots and automated testing. It does
that by stubbing out LWJGL, so any image decoding returns a blank image.

With Supplementaries installed, the client crashes on startup:

```
java.lang.RuntimeException: Image at supplementaries:textures/entity/globes/palettes/overworld.png has too few colors! Expected at least 13 and got 0
	at net.mehvahdjukaar.moonlight.api.resources.textures.SpriteUtils.parsePaletteStrip(SpriteUtils.java:273)
	at net.mehvahdjukaar.supplementaries.client.GlobeManager.refreshColorsAndTextures(GlobeManager.java:99)
	at net.mehvahdjukaar.supplementaries.dynamicpack.ModClientDynamicResources.reload(ModClientDynamicResources.java:146)
```

If I get past that one, the next one is the same thing from a different caller:

```
java.lang.RuntimeException: Image at supplementaries:textures/block/bubble_block_colors.png has too few colors! Expected at least 6 and got 0
	at net.mehvahdjukaar.moonlight.api.resources.textures.SpriteUtils.parsePaletteStrip(SpriteUtils.java:273)
	at net.mehvahdjukaar.supplementaries.client.renderers.color.ColorHelper.refreshBubbleColors(ColorHelper.java:91)
```

The textures themselves are fine — it's the image decoding that returns nothing in this
environment, so `parsePaletteStrip` sees 0 colours and throws.

**What I'd expect:** on a client that isn't rendering anything, failing to read a colour palette
shouldn't be fatal. Falling back to a placeholder palette (or letting the caller decide) would
let the game continue, since the colours are never displayed.

**What I tried, none of which avoided it:**

- downloading real assets instead of HeadlessMC's dummy ones — no effect, the textures are in
  the mod jar, not the assets folder
- `dynamic_assets_generation_mode = NO_OP` in the Supplementaries config
- `building.globe.enabled = false` in the Supplementaries config
- `hmc-optimizations` 0.4.0, which skips some rendering code paths

The call seems to happen on every resource reload regardless of whether globes are enabled.

**Environment**

| | |
|---|---|
| Minecraft | 1.21.1 |
| Loader | Fabric 0.19.5 |
| Moonlight | 1.21.1-3.6.1 |
| Supplementaries | 1.21.1-3.9.7 |
| Launcher | HeadlessMC 2.10.0 (`-lwjgl` mode) |
| OS | macOS, Apple Silicon |

I do have a local workaround so I'm not blocked — happy to share it if it's useful, but I
wasn't sure whether supporting headless clients is something you'd want to take on, so I
figured I'd report the crash first.
