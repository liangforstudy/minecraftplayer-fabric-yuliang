#!/usr/bin/env python3
"""Headless bot rig — one cross-platform tool (macOS, Windows, Linux). No absolute paths are stored:
every run detects Prism, Java 21 and this folder, and rewrites each rig's HeadlessMC config to match.

  bots.py setup                         first run on a new machine: find Java/Prism, install Fabric
  bots.py sync [--check]                mirror Prism mods+config into the rigs (see sync_bots.py)
  bots.py run <bot1|bot3> [heap] [host:port]
  bots.py stop                          stop headless bots only — never your Prism client

Overrides, if detection picks the wrong thing:
  BOTS_JAVA=<path to java 21 executable>    PRISM_DIR=<PrismLauncher data folder>
  or put the Prism path in headless/prism-dir.txt
"""
import glob
import os
import platform
import re
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
LAUNCHER = os.path.join(HERE, "headlessmc-launcher-2.10.0.jar")
MC_VERSION = "1.21.1"
FABRIC_LOADER = "0.19.5"
VERSION_NAME = f"fabric-loader-{FABRIC_LOADER}-{MC_VERSION}"
BRAND = "minecraft.launcher.brand=HeadlessMc"   # HeadlessMC stamps this on the game process
IS_WIN = platform.system() == "Windows"
IS_MAC = platform.system() == "Darwin"


# ---------------------------------------------------------------- where things live

def prism_dir():
    """PrismLauncher's data folder (the one containing instances/)."""
    cands = []
    if os.environ.get("PRISM_DIR"):
        cands.append(os.environ["PRISM_DIR"])
    override = os.path.join(HERE, "prism-dir.txt")
    if os.path.exists(override):
        cands.append(open(override).read().strip())
    home = os.path.expanduser("~")
    if IS_WIN:
        cands.append(os.path.join(os.environ.get("APPDATA", ""), "PrismLauncher"))
        cands += glob.glob(os.path.join(home, "scoop", "persist", "prismlauncher"))
    elif IS_MAC:
        cands.append(os.path.join(home, "Library", "Application Support", "PrismLauncher"))
    else:
        cands.append(os.path.join(home, ".local", "share", "PrismLauncher"))
        cands.append(os.path.join(home, ".var", "app", "org.prismlauncher.PrismLauncher", "data", "PrismLauncher"))
    for c in cands:
        if c and os.path.isdir(os.path.join(c, "instances")):
            return c
    sys.exit("Can't find PrismLauncher's data folder. Set PRISM_DIR, or write its path into "
             "headless/prism-dir.txt (Prism: Folders -> Launcher Root).")


def minecraft_dir():
    """HeadlessMC's default game-files folder (versions, libraries, assets)."""
    if IS_WIN:
        return os.path.join(os.environ.get("APPDATA", ""), ".minecraft")
    if IS_MAC:
        return os.path.expanduser("~/Library/Application Support/minecraft")
    return os.path.expanduser("~/.minecraft")


def _java_major(exe):
    try:
        out = subprocess.run([exe, "-version"], capture_output=True, text=True, timeout=20).stderr
    except (OSError, subprocess.TimeoutExpired):
        return None
    m = re.search(r'version "(\d+)', out)
    return int(m.group(1)) if m else None


def find_java():
    """A Java 21 executable. Minecraft 1.21.1 needs exactly 21+."""
    exe = "java.exe" if IS_WIN else "java"
    cands = []
    if os.environ.get("BOTS_JAVA"):
        cands.append(os.environ["BOTS_JAVA"])
    if os.environ.get("JAVA_HOME"):
        cands.append(os.path.join(os.environ["JAVA_HOME"], "bin", exe))
    if IS_MAC:
        try:
            jh = subprocess.run(["/usr/libexec/java_home", "-v", "21"], capture_output=True, text=True).stdout.strip()
            if jh:
                cands.append(os.path.join(jh, "bin", exe))
        except OSError:
            pass
        cands += glob.glob("/Library/Java/JavaVirtualMachines/*21*/Contents/Home/bin/java")
    elif IS_WIN:
        for root in (os.environ.get("ProgramFiles", r"C:\Program Files"), os.environ.get("ProgramW6432", "")):
            if root:
                for vendor in ("Eclipse Adoptium", "Java", "Microsoft", "Zulu", "Amazon Corretto", "BellSoft"):
                    cands += glob.glob(os.path.join(root, vendor, "*21*", "bin", exe))
    else:
        cands += glob.glob("/usr/lib/jvm/*21*/bin/java")
    # Prism's own downloaded runtimes (works on every OS)
    try:
        cands += glob.glob(os.path.join(prism_dir(), "java", "*", "bin", exe))
        cands += glob.glob(os.path.join(prism_dir(), "java", "*", "*", "bin", exe))
    except SystemExit:
        pass
    w = shutil.which("java")
    if w:
        cands.append(w)
    for c in cands:
        if os.path.isfile(c) and (_java_major(c) or 0) >= 21:
            return c
    sys.exit("No Java 21 found. Install Temurin 21 (adoptium.net), or set BOTS_JAVA to a java 21 executable.")


def rigs():
    return sorted(d for d in os.listdir(HERE)
                  if re.fullmatch(r"bot\d+", d) and os.path.exists(os.path.join(HERE, d, "identity.properties")))


def _props_path(p):
    return p.replace("\\", "/")    # .properties treats '\' as an escape; Java accepts '/' on Windows


def write_config(rig, java, gameargs=None):
    """Rewrite the machine-specific keys of <rig>/HeadlessMC/config.properties, keep the rest."""
    cfg = os.path.join(HERE, rig, "HeadlessMC", "config.properties")
    os.makedirs(os.path.dirname(cfg), exist_ok=True)
    keep = []
    if os.path.exists(cfg):
        for line in open(cfg, encoding="utf-8"):
            if not re.match(r"hmc\.(gamedir|java\.versions|gameargs)=", line):
                keep.append(line.rstrip("\n"))
    # <rig>/identity.properties (tracked in git) wins: username, uuid, fixed HeadlessMC flags
    ident = os.path.join(HERE, rig, "identity.properties")
    if os.path.exists(ident):
        ids = [l.rstrip("\n") for l in open(ident, encoding="utf-8") if l.strip() and not l.startswith("#")]
        idkeys = {l.split("=", 1)[0] for l in ids}
        keep = ids + [l for l in keep if l.split("=", 1)[0] not in idkeys]
    keep = [l for l in keep if l.strip()]
    keep.insert(0, "hmc.gamedir=" + _props_path(os.path.join(HERE, rig, "gamedir")))
    keep.append("hmc.java.versions=" + _props_path(java))
    if gameargs:
        keep.append("hmc.gameargs=" + gameargs)
    with open(cfg, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(keep) + "\n")
    return cfg


def _read_prop(rig, key):
    cfg = os.path.join(HERE, rig, "HeadlessMC", "config.properties")
    for line in open(cfg, encoding="utf-8"):
        if line.startswith(key + "="):
            return line.split("=", 1)[1].strip()
    return None


def _hmc(rig, java, commands, log=None, wait=True):
    """Feed commands to the HeadlessMC launcher, running inside the rig folder."""
    stdin = "".join(c + "\n" for c in commands)
    out = open(log, "w") if log else subprocess.PIPE
    p = subprocess.Popen([java, "-jar", LAUNCHER], cwd=os.path.join(HERE, rig),
                         stdin=subprocess.PIPE, stdout=out, stderr=subprocess.STDOUT, text=True)
    if wait:
        return p.communicate(stdin)[0]
    p.stdin.write(stdin)
    p.stdin.close()
    return p


# ---------------------------------------------------------------- commands

def cmd_setup():
    java = find_java()
    print(f"java 21 : {java}")
    print(f"prism   : {prism_dir()}")
    print(f"mc files: {minecraft_dir()}")
    if not os.path.isfile(LAUNCHER):
        sys.exit(f"missing {os.path.basename(LAUNCHER)} — copy it into {HERE}")
    for rig in rigs():
        write_config(rig, java)
        print(f"[{rig}] config written for this machine")
    if os.path.isdir(os.path.join(minecraft_dir(), "versions", VERSION_NAME)):
        print(f"{VERSION_NAME} already installed")
    else:
        print(f"installing {VERSION_NAME} via HeadlessMC (downloads Minecraft {MC_VERSION} + Fabric) ...")
        print(_hmc(rigs()[0], java, [f"fabric {MC_VERSION} --uid {FABRIC_LOADER}", "versions", "exit"]))
        if not os.path.isdir(os.path.join(minecraft_dir(), "versions", VERSION_NAME)):
            sys.exit("Fabric install didn't produce the expected version folder — check the output above.")
    print("setup done. Next: sync, then run.")


def _headless_pids():
    """PIDs of headless bot game processes and HeadlessMC launchers — never Prism."""
    if IS_WIN:
        ps = ("Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -and "
              "($_.CommandLine -match 'minecraft.launcher.brand=HeadlessMc' -or "
              "$_.CommandLine -match 'headlessmc-launcher') } | ForEach-Object { $_.ProcessId }")
        out = subprocess.run(["powershell", "-NoProfile", "-Command", ps], capture_output=True, text=True).stdout
    else:
        out = subprocess.run(["pgrep", "-f", f"{BRAND}|headlessmc-launcher"], capture_output=True, text=True).stdout
    return [int(x) for x in out.split() if x.isdigit() and int(x) != os.getpid()]


def _game_pid(name):
    """PID of the running game process for bot <name>, if any."""
    if IS_WIN:
        ps = ("Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -match "
              f"'minecraft.launcher.brand=HeadlessMc' -and $_.CommandLine -match '--username {name}( |$)' }} "
              "| Select-Object -First 1 | ForEach-Object { \"$($_.ProcessId) $($_.WorkingSetSize)\" }")
        out = subprocess.run(["powershell", "-NoProfile", "-Command", ps], capture_output=True, text=True).stdout.split()
        return (int(out[0]), int(out[1]) // 1048576) if len(out) == 2 else (None, None)
    out = subprocess.run(["pgrep", "-f", f"{BRAND}.*--username {name}"], capture_output=True, text=True).stdout.split()
    if not out:
        return None, None
    rss = subprocess.run(["ps", "-o", "rss=", "-p", out[0]], capture_output=True, text=True).stdout.strip()
    return int(out[0]), (int(rss) // 1024 if rss.isdigit() else None)


def cmd_stop():
    pids = _headless_pids()
    for pid in pids:
        if IS_WIN:
            subprocess.run(["taskkill", "/PID", str(pid), "/F"], capture_output=True)
        else:
            try:
                os.kill(pid, 15)
            except OSError:
                pass
    for rig in rigs():
        try:
            os.remove(os.path.join(HERE, rig, "launcher.pid"))
        except OSError:
            pass
    print(f"stopped headless bots ({len(pids)} process{'es' if len(pids) != 1 else ''})")


def _grep(text, pattern, flags=re.I):
    return re.search(pattern, text, flags)


def cmd_run(bot, heap="3G", addr=None):
    if not os.path.exists(os.path.join(HERE, bot, "identity.properties")):
        sys.exit(f"no rig at {bot}/ (needs {bot}/identity.properties)")
    if os.path.exists(os.path.join(HERE, bot, ".unsynced")):
        print(f"[{bot}] WARNING: this rig is marked UNSYNCED — its mods may not match the host.")
        print("        " + open(os.path.join(HERE, bot, ".unsynced")).read().strip())
    if not os.path.isdir(os.path.join(minecraft_dir(), "versions", VERSION_NAME)):
        sys.exit(f"{VERSION_NAME} isn't installed on this machine — run setup first.")
    java = find_java()
    write_config(bot, java, f"--quickPlayMultiplayer {addr}" if addr else None)
    name = _read_prop(bot, "hmc.offline.username") or bot

    log = os.path.join(HERE, bot, "run-" + time.strftime("%H%M%S") + ".log")
    print(f"[{name}] launching headless, heap={heap}{', joining ' + addr if addr else ''} -> {os.path.relpath(log, HERE)}")
    p = _hmc(bot, java, [f"launch {VERSION_NAME} -lwjgl -offline --jvm -Xmx{heap}", "exit"], log=log, wait=False)
    with open(os.path.join(HERE, bot, "launcher.pid"), "w") as f:
        f.write(str(p.pid))

    # wait for a verdict; cover failure paths, not just success
    since = None
    for _ in range(100):
        time.sleep(3)
        text = open(log, encoding="utf-8", errors="replace").read()
        norealms = "\n".join(l for l in text.splitlines() if "realms" not in l.lower())
        if _grep(text, r"Game crashed|OutOfMemory", 0):
            print(f"[{name}] CRASHED — see {log} and {bot}/gamedir/crash-reports/"); return 1
        if addr and "registry entries that are unknown to this client" in text:
            after = text.split("may be related", 1)[1].splitlines()[1:14] if "may be related" in text else []
            ns = sorted({l.strip() for l in after if re.fullmatch(r"[a-z0-9_.-]+", l.strip())})
            print(f"[{name}] MOD MISMATCH — host sent registry entries this bot doesn't have. Namespaces:")
            for n in ns:
                print(f"          {n}")
            print("        Remove those mod ids from sync-exclude.txt, run sync, relaunch."); return 6
        if addr and _grep(norealms, r"Failed to verify username|unverified_username|multiplayer\.disconnect|Disconnected from server"):
            print(f"[{name}] REJECTED by server:")
            for l in norealms.splitlines():
                if _grep(l, r"verify username|unverified|multiplayer\.disconnect|Disconnected from server"):
                    print("        " + l)
            return 2
        if addr and _grep(norealms, r"Connection refused|Couldn.t connect|connect\.failed"):
            print(f"[{name}] could not reach {addr} — is the world open to LAN on that port?"); return 3
        joined = _grep(text, r"joined the game|Loaded [0-9]+ advancements")
        if addr and "Connecting to" in text and not joined:
            since = since or time.time()
            if time.time() - since > 60:
                print(f"[{name}] CONNECTED THEN DROPPED — reached {addr} but never entered the world.")
                print("        No reason logged. Usual causes: the world is online-mode (needs OfflineLAN,")
                print("        online mode off), or the host has mods the bot lacks (run sync).")
                return 5
        else:
            since = None
        if addr and joined:
            pid, rss = _game_pid(name)
            print(f"[{name}] IN THE WORLD" + (f" (rss {rss}MB)" if rss else "")); return 0
        if not addr and "Realms" in text:
            print(f"[{name}] at title screen (no server given)"); return 0
    print(f"[{name}] no verdict after 5 min — check {log}"); return 4


def main(argv):
    if not argv or argv[0] in ("-h", "--help", "help"):
        print(__doc__); return 0
    cmd, rest = argv[0], argv[1:]
    if cmd == "setup":
        cmd_setup(); return 0
    if cmd == "sync":
        import sync_bots
        return sync_bots.main(rest)
    if cmd == "run":
        if not rest:
            sys.exit("usage: bots.py run <bot1|bot3> [heap] [host:port]")
        return cmd_run(*rest[:3])
    if cmd == "stop":
        cmd_stop(); return 0
    sys.exit(f"unknown command '{cmd}' — try: setup, sync, run, stop")


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
