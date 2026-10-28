// The Fabric adapter — the only code that touches Minecraft. Built once per Minecraft version by
// Stonecutter (versions/<mc>/ reuse this script). The brain lives in :core and is bundled inside.
plugins {
    // Applies the right Loom variant for the Minecraft version
    id("dev.kikugie.loom-back-compat")
}

// DO NOT set group = ...!
version = "${property("mod.version")}+${sc.current.version}"
base.archivesName = property("mod.id") as String

val requiredJava: JavaVersion = when {
    sc.current.parsed >= "26.1" -> JavaVersion.VERSION_25
    else -> JavaVersion.VERSION_21
}

// Renames between 1.21.11 and 26.2, applied by Stonecutter to the 26.2 build only, so call sites
// don't each need a //? block (as in Chest Tracker). Each pattern is scoped (a receiver, a whole word)
// so it can't hit unrelated code, and the 26.2 side never appears in the sources, so the way back is
// exact. Real API changes stay in //? if >=26.2 blocks.
val mc26 = sc.eval(sc.current.version, ">=26.2")
// Fabric API's key binding module took Mojang's name from 26.x on; fabric.mod.json depends on it too
val keyModule = if (sc.current.parsed >= "26.1") "fabric-key-mapping-api-v1" else "fabric-key-binding-api-v1"
sc.replacements {
    fun rename(old: String, new: String) = regex(mc26) { replace(Regex.escape(old), new, Regex.escape(new), old) }
    fun word(old: String, new: String) = regex(mc26) { replace("\\b$old\\b", new, "\\b$new\\b", old) }

    // Fabric API moved to Mojang's names
    word("ClientCommandManager", "ClientCommands")
    rename("api.client.keybinding.v1.KeyBindingHelper", "api.client.keymapping.v1.KeyMappingHelper")
    rename("KeyBindingHelper.registerKeyBinding(", "KeyMappingHelper.registerKeyMapping(")
    // Slot clicks
    word("ClickType", "ContainerInput")
    rename(".handleInventoryMouseClick(", ".handleContainerInput(")
    // The current screen moved from Minecraft to Minecraft.gui; the boss bars to Minecraft.gui.hud
    regex(mc26) { replace("\\b(mc|minecraft)\\.screen\\b", "\$1.gui.screen()", "\\b(mc|minecraft)\\.gui\\.screen\\(\\)", "\$1.screen") }
    regex(mc26) { replace("\\b(mc|minecraft)\\.setScreen\\(", "\$1.gui.setScreen(", "\\b(mc|minecraft)\\.gui\\.setScreen\\(", "\$1.setScreen(") }
    rename("mc.gui.getBossOverlay()", "mc.gui.hud.getBossOverlay()")
    // Screens render through an extractor now
    word("GuiGraphics", "GuiGraphicsExtractor")
    rename("public void render(", "public void extractRenderState(")
    rename("super.render(g,", "super.extractRenderState(g,")
    rename("g.drawCenteredString(", "g.centeredText(")
    rename("g.drawString(", "g.text(")
}

repositories {
    // Mod Menu — optional: we only add a settings screen when it's installed
    exclusiveContent {
        forRepository { maven("https://maven.terraformersmc.com/releases") { name = "TerraformersMC" } }
        filter { includeGroup("com.terraformersmc") }
    }
}

dependencies {
    modCompileOnly("com.terraformersmc:modmenu:${property("deps.modmenu")}") { isTransitive = false }
    // Baritone — optional, installed by the user (LGPL-3.0; PHASE1.md P1-1). Compiled against, never
    // bundled. The jar is the one headless/ downloads; not in git.
    modCompileOnly(files(rootProject.file("../headless/baritone-api-fabric-${property("deps.baritone")}.jar")))

    // Only the Fabric API modules we actually use
    fun fapi(vararg modules: String) {
        for (it in modules) modImplementation(fabricApi.module(it, sc.properties["deps.fabric_api"]))
    }

    minecraft("com.mojang:minecraft:${sc.current.version}")
    loomx.applyMojangMappings()

    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    fapi(
        "fabric-api-base",
        "fabric-lifecycle-events-v1",   // client tick
        "fabric-networking-api-v1",     // join / disconnect
        "fabric-command-api-v2",        // client commands under a configurable root
        "fabric-message-api-v1",        // incoming chat (human syntax, bus over chat)
        keyModule,                      // the emergency stop key, in Options -> Controls
        "fabric-screen-api-v1",         // ...which works with a screen open, too (the bot's inventory)
    )
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")   // Baritone's API annotations

    implementation(project(":core"))
    include(project(":core"))           // jar-in-jar: the brain ships inside the mod
}

loom {
    fabricModJsonPath = rootProject.file("src/main/resources/fabric.mod.json")

    runConfigs.all {
        preferGradleTask = true
        generateRunConfig = true
        runDirectory = rootProject.file("run")
    }
}

java {
    targetCompatibility = requiredJava
    sourceCompatibility = requiredJava
    toolchain {
        languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
    }
}

tasks {
    processResources {
        fun MutableMap<String, String>.register(key: String, property: String) {
            val value: String = sc.properties[property]
            inputs.property(key, value)
            set(key, value)
        }

        val props = buildMap {
            register("id", "mod.id")
            register("name", "mod.name")
            register("version", "mod.version")
            register("minecraft", "mod.mc_compat")
            inputs.property("key_module", keyModule)
            set("key_module", keyModule)
        }

        filesMatching("fabric.mod.json") { expand(props) }
        val mixinJava = "JAVA_${requiredJava.majorVersion}"
        filesMatching("*.mixins.json") { expand("java" to mixinJava) }
    }

    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds mod jars and copies them to build/libs/{mod version}/"
        inputs.property("version", project.property("mod.version"))
        from(loomx.modJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.file("libs/${project.property("mod.version")}"))
    }
}
