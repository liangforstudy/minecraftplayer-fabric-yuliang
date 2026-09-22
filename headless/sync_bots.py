#!/usr/bin/env python3
"""Mirror a Prism instance's mods + config into each headless bot rig.

Per rig (bot1/, bot3/, ...):
  <rig>/.source    name of the Prism instance to mirror (default: Minimal)
  <rig>/.unsynced  if present, the rig is skipped and flagged — its mods are deliberately stale

Mods whose fabric.mod.json id is listed in sync-exclude.txt are never copied.
The moonlight-headless-patch jar is always added.

  bots.py sync             sync every rig      (wrappers: sync-bots.sh / sync-bots.bat)
  bots.py sync --check     report drift only; exit 1 if any rig drifted
"""
import glob
import json
import os
import re
import shutil
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_SOURCE = "1.21.1 ZymCivModded Server Minimal"
# Our own mod, freshly built (mod/versions/<mc>/build/libs/) — always added to every rig, so a
# sync never deletes it. Skipped with a note if it hasn't been built yet.
ZYMBOT_GLOB = os.path.join(HERE, "..", "mod", "versions", "1.21.1", "build", "libs", "zymbot-*.jar")
PATCH_NAME = "moonlight-headless-patch-1.0.0.jar"
# built jar, or a prebuilt copy kept next to this script (for machines without the build)
PATCH_CANDIDATES = [os.path.join(HERE, "..", "moonlight-headless-patch", "build", "libs", PATCH_NAME),
                    os.path.join(HERE, PATCH_NAME)]
IGNORED_DEPS = {"minecraft", "fabricloader", "java", "fabric", "fabric-api", "fabric-language-kotlin"}

# Registries whose entries are sent to every joining client (MC 1.21.1 intermediary field -> name).
# A client missing an entry the host registered disconnects itself — silently, with no reason on
# either side. Seen for real: Cosy Critters (a "client-only" visual mod) registers particle types,
# which an integrated/LAN host loads because it runs inside the host's client.
SYNCED_REGISTRIES = {
    "field_41190": "attribute", "field_41175": "block", "field_41181": "block_entity_type",
    "field_41192": "command_argument_type", "field_41183": "custom_stat",
    "field_49658": "data_component_type", "field_51832": "enchantment_effect_component_type",
    "field_41177": "entity_type", "field_41173": "fluid", "field_41178": "item", "field_41187": "menu",
    "field_41174": "mob_effect", "field_41180": "particle_type", "field_41179": "potion",
    "field_41189": "recipe_serializer", "field_41172": "sound_event", "field_41193": "stat_type",
    "field_41195": "villager_profession", "field_41194": "villager_type",
}
_REGISTER_CALLS = (b"method_10230", b"method_39197")


def synced_registrations(jar):
    """Names of client-synced registries this jar registers into (heuristic, per class)."""
    found = set()
    z = zipfile.ZipFile(jar)
    for n in z.namelist():
        if not n.endswith(".class"):
            continue
        b = z.read(n)
        if b"class_7923" not in b or not any(c in b for c in _REGISTER_CALLS):
            continue
        for f in re.findall(rb"field_\d+", b):
            name = SYNCED_REGISTRIES.get(f.decode())
            if name:
                found.add(name)
    return found


def mod_meta(jar):
    try:
        raw = zipfile.ZipFile(jar).read("fabric.mod.json").decode("utf-8", "replace")
        d = json.loads(re.sub(r",\s*([}\]])", r"\1", raw))
        return d.get("id"), set(d.get("depends") or {}) - IGNORED_DEPS
    except Exception:
        return None, set()


def load_excludes():
    ids = set()
    with open(os.path.join(HERE, "sync-exclude.txt")) as f:
        for line in f:
            line = line.split("#", 1)[0].strip()
            if line:
                ids.add(line)
    return ids


def wanted_jars(source_mods, excludes):
    keep, dropped, deps, forced = [], [], {}, []
    for j in sorted(os.listdir(source_mods)):
        if not j.endswith(".jar"):
            continue
        mid, d = mod_meta(os.path.join(source_mods, j))
        regs = synced_registrations(os.path.join(source_mods, j)) if mid in excludes else set()
        if mid in excludes and regs:
            forced.append((mid, sorted(regs)))   # excluded by taste, but the host will sync it
            keep.append(j)
            deps[mid] = d
        elif mid in excludes:
            dropped.append((mid, j))
        else:
            keep.append(j)
            deps[mid] = d
    # a kept mod must not depend on something we excluded
    excluded_ids = {m for m, _ in dropped}
    broken = [(m, sorted(d & excluded_ids)) for m, d in deps.items() if d & excluded_ids]
    return keep, dropped, broken, forced


# Config files that belong to the rig, not to Prism: a sync never deletes or overwrites them.
RIG_OWNED_CONFIG = {"zymbot.json"}


def mirror_dir(src, dst):
    """Make dst an exact copy of src (like rsync -a --delete), in pure Python for Windows.
    Files named in RIG_OWNED_CONFIG (top level) are left alone."""
    os.makedirs(dst, exist_ok=True)
    for root, dirs, files in os.walk(dst, topdown=False):
        rel = os.path.relpath(root, dst)
        for f in files:
            if rel == "." and f in RIG_OWNED_CONFIG:
                continue
            if not os.path.exists(os.path.join(src, rel, f)):
                os.remove(os.path.join(root, f))
        for d in dirs:
            if not os.path.isdir(os.path.join(src, rel, d)):
                shutil.rmtree(os.path.join(root, d), ignore_errors=True)
    for root, dirs, files in os.walk(src):
        rel = os.path.relpath(root, src)
        os.makedirs(os.path.join(dst, rel), exist_ok=True)
        for f in files:
            if rel == "." and f in RIG_OWNED_CONFIG:
                continue
            s, d = os.path.join(root, f), os.path.join(dst, rel, f)
            st = os.stat(s)
            if not os.path.exists(d) or os.path.getsize(d) != st.st_size or int(os.path.getmtime(d)) != int(st.st_mtime):
                shutil.copy2(s, d)


def main(argv=None):
    from bots import prism_dir
    argv = sys.argv[1:] if argv is None else argv
    check = "--check" in argv
    instances = os.path.join(prism_dir(), "instances")
    PATCH = next((p for p in PATCH_CANDIDATES if os.path.isfile(p)), None)
    if not PATCH:
        sys.exit("patch jar missing — build it (moonlight-headless-patch: gradlew build) "
                 f"or copy {PATCH_NAME} next to this script")
    excludes = load_excludes()
    drift = False

    for rig in sorted(d for d in os.listdir(HERE) if re.fullmatch(r"bot\d+", d)):
        rig_dir = os.path.join(HERE, rig)
        if os.path.exists(os.path.join(rig_dir, ".unsynced")):
            note = open(os.path.join(rig_dir, ".unsynced")).read().strip()
            print(f"[{rig}] UNSYNCED — skipped ({note})")
            continue

        src_file = os.path.join(rig_dir, ".source")
        source = open(src_file).read().strip() if os.path.exists(src_file) else DEFAULT_SOURCE
        src = os.path.join(instances, source, "minecraft")
        if not os.path.isdir(os.path.join(src, "mods")):
            print(f"[{rig}] source instance not found: {source}")
            drift = True
            continue

        keep, dropped, broken, forced = wanted_jars(os.path.join(src, "mods"), excludes)
        for mid, regs in forced:
            print(f"[{rig}] keeping '{mid}' despite sync-exclude.txt — it registers synced content "
                  f"({', '.join(regs)}); without it the bot is dropped on join")
        if broken:
            print(f"[{rig}] REFUSING: kept mods depend on excluded ones: {broken}")
            drift = True
            continue

        mods = os.path.join(rig_dir, "gamedir", "mods")
        os.makedirs(mods, exist_ok=True)      # absent on a fresh clone (gitignored)
        ours = lambda j: j == PATCH_NAME or (j.startswith("zymbot-") and j.endswith(".jar"))
        have = {j for j in os.listdir(mods) if j.endswith(".jar") and not ours(j)}
        want = set(keep)
        add, remove = sorted(want - have), sorted(have - want)
        has_patch = os.path.exists(os.path.join(mods, PATCH_NAME))
        zymbot = sorted(glob.glob(ZYMBOT_GLOB), key=os.path.getmtime)[-1:] if glob.glob(ZYMBOT_GLOB) else []
        zymbot_current = all(os.path.exists(os.path.join(mods, os.path.basename(z))) and
                             os.path.getmtime(os.path.join(mods, os.path.basename(z))) >= os.path.getmtime(z)
                             for z in zymbot)

        if not add and not remove and has_patch and zymbot_current:
            print(f"[{rig}] in sync with '{source}' — {len(want)} mods (+patch), {len(dropped)} excluded")
            continue

        drift = True
        print(f"[{rig}] drift vs '{source}':")
        for j in add:
            print(f"   + {j}")
        for j in remove:
            print(f"   - {j}")
        if not has_patch:
            print(f"   + {PATCH_NAME} (headless patch)")
        if not zymbot_current:
            print(f"   + {os.path.basename(zymbot[0])} (our mod, rebuilt)")
        if check:
            continue

        for j in remove:
            os.remove(os.path.join(mods, j))
        for j in add:
            shutil.copy2(os.path.join(src, "mods", j), mods)
        shutil.copy2(PATCH, mods)
        for old in [j for j in os.listdir(mods) if j.startswith("zymbot-") and j.endswith(".jar")]:
            os.remove(os.path.join(mods, old))
        for z in zymbot:
            shutil.copy2(z, mods)
        mirror_dir(os.path.join(src, "config"), os.path.join(rig_dir, "gamedir", "config"))
        print(f"[{rig}] synced — {len(want)} mods + patch{' + zymbot' if zymbot else ' (zymbot not built)'}; excluded {len(dropped)}: "
              + ", ".join(m for m, _ in dropped))

    return 1 if (check and drift) else 0


if __name__ == "__main__":
    sys.exit(main())
