# Moonlight Headless Patch

A tiny Fabric client mod that lets **Moonlight-based mods load under
[HeadlessMC](https://github.com/headlesshq/headlessmc)**.

Built for a bot project, but it's standalone and reusable — nothing in it is bot-specific.

## What this actually fixes, in plain English

**If you're here because Supplementaries crashes when you run it under HeadlessMC — this fixes
it.** Here's what's going on.

**HeadlessMC runs Minecraft with no graphics at all.** It does that by replacing every graphics
function with an empty stub — they get called, they do nothing, they hand back blanks. That's
the whole trick, and it's why you don't need a screen or a graphics card.

**Supplementaries reads its own texture files as data, not just as pictures.** A few of its
features need a list of colours — the globe needs colours per dimension, bubble blocks need
their tints. Rather than hardcode them, it ships tiny PNGs that are literally a strip of
coloured pixels, and reads the colours back out of them when the game loads. That read happens
through a shared library called Moonlight, in a function named `parsePaletteStrip`.

**Those two things collide.** Reading pixels out of a PNG needs the image-decoding code — which
is one of the things HeadlessMC stubbed out. So the decode returns a blank image with zero
colours in it. `parsePaletteStrip` counts the colours, finds 0 where it wanted 13, decides
something is badly wrong, and deliberately crashes:

```
java.lang.RuntimeException: Image at supplementaries:textures/entity/globes/palettes/overworld.png
    has too few colors! Expected at least 13 and got 0
```

Which is reasonable behaviour in a normal game — a texture that won't decode really is a
problem. It just isn't a problem when nothing is being drawn in the first place.

**What this mod does:** when it detects HeadlessMC, and *only* then, it hands back a list of
plain grey placeholder colours of exactly the length that was asked for, instead of crashing.
Nothing is being rendered, so the actual colour values are never seen by anyone — they only
need to exist and be the right count.

It also skips Supplementaries' globe colour refresh entirely, since that one is purely
decorative and there's no point running it even on fake colours.

**Why it's named after Moonlight, not Supplementaries:** the crash happens inside Moonlight's
`parsePaletteStrip`, which is shared. Supplementaries calls it in two different places, and any
other Moonlight-based mod that does palette work will hit exactly the same wall. Patching the
one shared function fixes all of them at once, instead of playing whack-a-mole mod by mod.

**Nothing changes on a normal client.** The check is a single string comparison against the
launcher brand; on a real client with real graphics, both mixins return immediately and
Supplementaries behaves exactly as it always has.

## The technical version

Moonlight's `SpriteUtils.parsePaletteStrip(ResourceManager, Identifier, int)` reads pixel data
during resource reload and throws when the decoded colour count is below the caller's minimum.
Under HeadlessMC, LWJGL and STB are stubbed, so every image decodes empty and it throws for any
caller. Supplementaries hits it via `GlobeManager.refreshColorsAndTextures` and
`ColorHelper.refreshBubbleColors`.

**No config avoids it.** Verified: real assets instead of dummy,
`dynamic_assets_generation_mode = NO_OP`, `building.globe.enabled = false`, and
`hmc-optimizations` 0.4.0 all still crash — the call is unconditional on resource reload.

Two mixins, both inert unless `minecraft.launcher.brand` is `HeadlessMc`:

| mixin | target | effect |
|---|---|---|
| `SpriteUtilsHeadlessMixin` | `moonlight` `SpriteUtils.parsePaletteStrip` | returns a grey ramp of exactly the requested length — fixes every caller at once |
| `GlobeManagerHeadlessMixin` | `supplementaries` `GlobeManager.refreshColorsAndTextures` | cancels it outright, so it doesn't do pointless image work on placeholder colours |

## Dependencies

**Runtime: none but Fabric Loader.** No Fabric API. No hard dependency on Moonlight or
Supplementaries — both are targeted by string, with `required: false` and `defaultRequire: 0`,
so the jar drops into any pack and no-ops where they're absent.

Build-time only: Minecraft, yarn mappings, Fabric Loader, plus `modCompileOnly` copies of
Moonlight and Supplementaries from Modrinth's maven so the Mixin annotation processor can
resolve the target classes. Neither is bundled.

## Build

```bash
./gradlew build          # -> build/libs/moonlight-headless-patch-1.0.0.jar  (~5 KB)
```

Gradle 8.10 via the wrapper (Loom 1.7 doesn't run on Gradle 9; Loom 1.18 needs Java 25).
Java 21.

## Verified

Full 84-mod ZymCiv 1.21.1 pack boots to the title screen under HeadlessMC 2.10.0 on macOS
arm64, Supplementaries included. Built against moonlight `1.21.1-3.6.3` and supplementaries
`1.21.1-3.9.7`; targeting is by string, so it should survive minor updates as long as those
method names hold — and fail silently rather than crash if they don't.

## Upstream

No existing fix was findable — nothing in the Supplementaries issue tracker, and
`hmc-specifics` turns out to be the HeadlessMC runtime rather than a per-mod compat collection.

[`BUG_REPORT.md`](BUG_REPORT.md) is a draft report of the **crash**, not the patch. Moonlight
and Supplementaries share an author, and the throw happens inside Moonlight, so that's the
better place to file it. Whether they want to support headless clients at all is their call —
this mod works regardless.

MIT.
