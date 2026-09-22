#!/usr/bin/env python3
"""Headless bot rig — one cross-platform tool (macOS, Windows, Linux). No absolute paths are stored:
every run detects Prism, Java 21 and this folder, and rewrites each rig's HeadlessMC config to match.

  bots.py setup                         first run on a new machine: find Java/Prism, install Fabric
  bots.py sync [--check]                mirror Prism mods+config into the rigs (see sync_bots.py)
  bots.py run <bot1|bot3> [heap] [host:port]   launch; join straight away if a server is given
  bots.py standby <bot> [heap]          launch to the title screen and wait
  bots.py connect <bot> <host:port> [--wait]   join now, or as soon as the world opens
  bots.py disconnect <bot>              leave the server, stay running
  bots.py gui <bot>                     what's on the bot's screen (buttons, text)
  bots.py send <bot> <command...>       any hmc-specifics console command (click, msg, / ...)
  bots.py team-key                      show the shared team key (for your own client's config)
  bots.py play "<world>" [bots...]      bots to standby + your Prism game straight into that world
  bots.py stop                          stop headless bots only — never your Prism client

Overrides, if detection picks the wrong thing:
  BOTS_JAVA=<path to java 21 executable>    PRISM_DIR=<PrismLauncher data folder>
  PRISM_BIN=<the prismlauncher program>      PRISM_INSTANCE=<instance folder name, for play>
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
    key = team_key()
    for rig in rigs():
        set_rig_team_key(rig, key)
    print(f"team key: shared by {len(rigs())} rig(s), each listed as a Bot — for your own client, see: bots.py team-key")
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
              "$_.CommandLine -match 'headlessmc-launcher' -or $_.CommandLine -match 'bots.py _relay') } "
              "| ForEach-Object { $_.ProcessId }")
        out = subprocess.run(["powershell", "-NoProfile", "-Command", ps], capture_output=True, text=True).stdout
    else:
        out = subprocess.run(["pgrep", "-f", f"{BRAND}|headlessmc-launcher|bots.py _relay"], capture_output=True, text=True).stdout
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


TEAM_KEY_FILE = os.path.join(HERE, "team-key.txt")   # gitignored — never commit it


def team_key():
    """The team key every local bot shares (created once). Signs and encrypts bus messages."""
    import secrets
    if os.path.exists(TEAM_KEY_FILE):
        k = open(TEAM_KEY_FILE).read().strip()
        if k:
            return k
    k = secrets.token_urlsafe(18)
    fd = os.open(TEAM_KEY_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(k + "\n")
    return k


def set_rig_team_key(rig, key):
    """Put the shared key into the rig's zymbot.json, and list the rig's own account as a Bot
    (by UUID, from identity.properties). The mod fills in every other setting."""
    import json
    p = os.path.join(HERE, rig, "gamedir", "config", "zymbot.json")
    os.makedirs(os.path.dirname(p), exist_ok=True)
    try:
        cfg = json.load(open(p, encoding="utf-8"))
    except (OSError, ValueError):
        cfg = {}
    before = json.dumps(cfg, sort_keys=True)
    cfg["team_key"] = key
    uuid = _ident(rig, "hmc.offline.uuid")
    name = _ident(rig, "hmc.offline.username") or rig
    if uuid:
        accounts = [a for a in cfg.get("accounts", []) if a.get("uuid", "").lower() != uuid.lower()]
        accounts.append({"uuid": uuid, "name": name, "role": "bot"})
        cfg["accounts"] = accounts
    if json.dumps(cfg, sort_keys=True) != before:
        with open(p, "w", encoding="utf-8") as f:
            json.dump(cfg, f, indent=2)


def _ident(rig, key):
    p = os.path.join(HERE, rig, "identity.properties")
    for line in open(p, encoding="utf-8") if os.path.exists(p) else []:
        if line.startswith(key + "="):
            return line.split("=", 1)[1].strip()
    return None


def key_fingerprint(key):
    """Same as ZymbotConfig.fingerprint in the mod: safe to show, compare it between games."""
    import hashlib
    return hashlib.sha256(("zymbot-fp:" + key).encode("utf-8")).hexdigest()[:6]


def cmd_team_key():
    key = team_key()
    print("Your team key (keep it private — anyone with it can read and send bot messages):")
    print(f"  {key}")
    print(f"fingerprint: {key_fingerprint(key)}   (safe to share; it must match in every game)")
    print("To add your own game to the team: Mod Menu -> Zymbot -> Team key: paste -> Save key,")
    print("then Accounts: + <you> as Teammate, and Servers: + this server.")
    print("(Or edit minecraft/config/zymbot.json -> \"team_key\" and restart.)")
    return 0


def _check_rig(bot):
    if not os.path.exists(os.path.join(HERE, bot, "identity.properties")):
        sys.exit(f"no rig at {bot}/ (needs {bot}/identity.properties)")
    if os.path.exists(os.path.join(HERE, bot, ".unsynced")):
        print(f"[{bot}] WARNING: this rig is marked UNSYNCED — its mods may not match the host.")
        print("        " + open(os.path.join(HERE, bot, ".unsynced")).read().strip())
    if not os.path.isdir(os.path.join(minecraft_dir(), "versions", VERSION_NAME)):
        sys.exit(f"{VERSION_NAME} isn't installed on this machine — run setup first.")


def cmd_run(bot, heap="3G", addr=None):
    """Launch through the console relay, so the bot keeps a console we can send commands to."""
    _check_rig(bot)
    if _console(bot):
        sys.exit(f"[{bot}] is already running — stop it first, or use connect/send")
    java = find_java()
    write_config(bot, java, f"--quickPlayMultiplayer {addr}" if addr else None)
    name = _read_prop(bot, "hmc.offline.username") or bot
    log = os.path.join(HERE, bot, "run-" + time.strftime("%H%M%S") + ".log")
    print(f"[{name}] launching headless, heap={heap}{', joining ' + addr if addr else ' (standby at title screen)'}"
          f" -> {os.path.relpath(log, HERE)}")
    _spawn_relay(bot, java, heap, log)
    return _await_verdict(bot, name, log, addr, offset=0)


def _await_verdict(bot, name, log, addr, offset, timeout=300):
    """Watch the log from `offset` until the join (or title screen) succeeds or fails. Covers every
    failure path we've met, not just success."""
    since = None
    until = time.time() + timeout
    while time.time() < until:
        time.sleep(2)
        try:
            text = open(log, encoding="utf-8", errors="replace").read()[offset:]
        except FileNotFoundError:
            continue
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
                print(f"        No reason logged — try: bots.py gui {bot}   (shows the screen it's stuck on)")
                return 5
        else:
            since = None
        if addr and joined:
            pid, rss = _game_pid(name)
            print(f"[{name}] IN THE WORLD" + (f" (rss {rss}MB)" if rss else "")); return 0
        if not addr and "Realms" in text:
            print(f"[{name}] at title screen — standing by. Next: bots.py connect {bot} <host:port> [--wait]")
            return 0
    print(f"[{name}] no verdict after {timeout}s — check {log}"); return 4


# ---------------------------------------------------------------- console relay
#
# HeadlessMC reads commands on its console, and hmc-specifics turns them into game actions
# (connect, disconnect, gui, click, msg, /cmd). The relay is a small background process that owns
# that console and accepts lines on 127.0.0.1 only, with a random token from <rig>/console.json
# (readable by this user only). Nothing on another machine can reach it.

def _spawn_relay(bot, java, heap, log):
    args = [sys.executable, os.path.abspath(__file__), "_relay", bot, java, heap, log]
    kw = dict(stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, cwd=HERE)
    if IS_WIN:
        kw["creationflags"] = 0x00000008 | 0x00000200   # DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP
    else:
        kw["start_new_session"] = True                  # survives this script and its terminal
    subprocess.Popen(args, **kw)
    for _ in range(50):                                 # wait for it to publish its port
        if _console(bot):
            return
        time.sleep(0.1)
    sys.exit(f"[{bot}] console relay didn't start — see {log}")


def _relay_main(bot, java, heap, log):
    import secrets
    import socket
    import threading
    info = os.path.join(HERE, bot, "console.json")
    logf = open(log, "w", encoding="utf-8")
    p = subprocess.Popen([java, "-jar", LAUNCHER], cwd=os.path.join(HERE, bot), stdin=subprocess.PIPE,
                         stdout=logf, stderr=subprocess.STDOUT, text=True, bufsize=1)
    # -Dhmc.jline.enabled=false: the game's console is our pipe, not a terminal (JLine would crash)
    p.stdin.write(f"launch {VERSION_NAME} -lwjgl -offline --jvm \"-Xmx{heap} -Dhmc.jline.enabled=false\"\n")
    p.stdin.flush()
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.bind(("127.0.0.1", 0))                          # loopback only, any free port
    srv.listen(4)
    token = secrets.token_urlsafe(24)
    import json
    fd = os.open(info, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump({"port": srv.getsockname()[1], "token": token, "relay_pid": os.getpid(),
                   "launcher_pid": p.pid, "log": log}, f)
    lock = threading.Lock()

    def serve(conn):
        with conn, conn.makefile("r", encoding="utf-8") as r, conn.makefile("w", encoding="utf-8") as w:
            if r.readline().strip() != token:
                w.write("denied\n"); return
            for line in r:
                line = line.rstrip("\n")
                if not line or "\n" in line:
                    continue
                with lock:
                    p.stdin.write(line + "\n"); p.stdin.flush()
                w.write("ok\n"); w.flush()

    def accept():
        while True:
            try:
                conn, _ = srv.accept()
            except OSError:
                return
            threading.Thread(target=serve, args=(conn,), daemon=True).start()

    threading.Thread(target=accept, daemon=True).start()
    p.wait()                                            # the game (and launcher) ended
    srv.close()
    try:
        os.remove(info)
    except OSError:
        pass


def _console(bot):
    """The running bot's relay info, or None."""
    import json
    info = os.path.join(HERE, bot, "console.json")
    try:
        c = json.load(open(info))
    except (OSError, ValueError):
        return None
    if not _pid_alive(c.get("relay_pid", 0)):
        try:
            os.remove(info)
        except OSError:
            pass
        return None
    return c


def _pid_alive(pid):
    if not pid:
        return False
    if IS_WIN:
        out = subprocess.run(["tasklist", "/FI", f"PID eq {pid}", "/NH"], capture_output=True, text=True).stdout
        return str(pid) in out
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def _send_line(bot, line):
    import socket
    c = _console(bot)
    if not c:
        sys.exit(f"[{bot}] isn't running (start it: bots.py standby {bot})")
    with socket.create_connection(("127.0.0.1", c["port"]), timeout=5) as s:
        s.sendall((c["token"] + "\n" + line + "\n").encode("utf-8"))
        reply = s.makefile("r").readline().strip()
    if reply != "ok":
        sys.exit(f"[{bot}] console refused the command ({reply or 'no reply'})")
    return c


def cmd_send(bot, words, show_secs=3):
    """Send one console command (hmc-specifics: gui, click, connect, disconnect, msg, / ...)."""
    line = " ".join(words)
    c = _console(bot)
    if not c:
        sys.exit(f"[{bot}] isn't running (start it: bots.py standby {bot})")
    offset = os.path.getsize(c["log"])
    _send_line(bot, line)
    time.sleep(show_secs)
    new = open(c["log"], encoding="utf-8", errors="replace").read()[offset:]
    shown = [l for l in new.splitlines() if l.strip() and "Missing sound" not in l]
    print("\n".join(shown[-60:]) if shown else f"(sent '{line}'; no console output within {show_secs}s)")
    return 0


def cmd_connect(bot, addr, wait=False, wait_secs=900):
    """Join a server from the title screen. --wait: keep checking until the world is open, then join."""
    import socket
    host, _, port = addr.rpartition(":") if ":" in addr else (addr, "", "25565")
    port = int(port)
    name = _read_prop(bot, "hmc.offline.username") or bot
    c = _console(bot)
    if not c:
        sys.exit(f"[{bot}] isn't running (start it: bots.py standby {bot})")
    if wait:
        print(f"[{name}] waiting for {host}:{port} to open (up to {wait_secs // 60} min)...")
        until = time.time() + wait_secs
        while True:
            try:
                socket.create_connection((host, port), timeout=2).close()
                break
            except OSError:
                if time.time() > until:
                    print(f"[{name}] {host}:{port} never opened"); return 3
                time.sleep(3)
        time.sleep(2)                                   # let the LAN server finish opening
    offset = os.path.getsize(c["log"])
    print(f"[{name}] connecting to {host}:{port}")
    _send_line(bot, f"connect {host} {port}")
    return _await_verdict(bot, name, c["log"], f"{host}:{port}", offset, timeout=180)


def prism_bin():
    """The Prism Launcher program (not its data folder)."""
    if os.environ.get("PRISM_BIN"):
        return os.environ["PRISM_BIN"]
    if IS_MAC:
        cands = ["/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher",
                 os.path.expanduser("~/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher")]
    elif IS_WIN:
        cands = [os.path.expandvars(r"%LOCALAPPDATA%\Programs\PrismLauncher\prismlauncher.exe"),
                 os.path.expandvars(r"%ProgramFiles%\PrismLauncher\prismlauncher.exe")]
    else:
        cands = [shutil.which("prismlauncher") or ""]
    for c in cands:
        if c and os.path.exists(c):
            return c
    sys.exit("Can't find the Prism Launcher program. Set PRISM_BIN to it.")


def _game_running(instance):
    """Is this Prism instance's game already running? Looks for a java process started from it."""
    marker = os.path.join("instances", instance)
    try:
        if IS_WIN:
            out = subprocess.run(["powershell", "-NoProfile", "-Command",
                                  "Get-CimInstance Win32_Process -Filter \"Name like 'java%'\" | "
                                  "Select-Object -ExpandProperty CommandLine"],
                                 capture_output=True, text=True, timeout=20).stdout
        else:
            out = subprocess.run(["ps", "-ax", "-o", "command="], capture_output=True, text=True, timeout=10).stdout
    except (OSError, subprocess.SubprocessError):
        return False                                     # can't tell: launch as asked
    return any(marker in line and "java" in line.lower() and "headless" not in line.lower()
               for line in out.splitlines())


def cmd_play(world, bots):
    """Bots to standby (in the background) and your own game straight into a world. The rest is
    Zymbot on your client, set once in game: /zbot lan auto on (the world opens to LAN by itself)
    and /zbot summon auto on (waiting bots are called in, repeatedly for 5 minutes)."""
    instance = os.environ.get("PRISM_INSTANCE")
    if not instance:
        src = os.path.join(HERE, (bots or ["bot1"])[0], ".source")
        instance = open(src).read().strip() if os.path.exists(src) else None
    if not instance:
        sys.exit("Which Prism instance? Set PRISM_INSTANCE to its folder name.")
    for bot in bots or ["bot1"]:
        if _console(bot):
            print(f"[{bot}] already running")
            continue
        subprocess.Popen([sys.executable, "-u", os.path.abspath(__file__), "standby", bot],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        print(f"[{bot}] starting in standby (about a minute)")
    if _game_running(instance):
        print(f"[you] '{instance}' is already running — not launching it again")
        return 0
    subprocess.Popen([prism_bin(), "-l", instance, "-w", world],
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    print(f"[you] launching '{instance}' straight into '{world}'")
    print("      With /zbot lan auto on + /zbot summon auto on set in that world, the rest is automatic.")
    return 0


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
    if cmd == "standby":
        if not rest:
            sys.exit("usage: bots.py standby <bot> [heap]")
        return cmd_run(rest[0], rest[1] if len(rest) > 1 else "3G", None)
    if cmd == "connect":
        if len(rest) < 2:
            sys.exit("usage: bots.py connect <bot> <host:port> [--wait]")
        return cmd_connect(rest[0], rest[1], wait="--wait" in rest)
    if cmd == "disconnect":
        return cmd_send(rest[0], ["disconnect"])
    if cmd == "gui":
        return cmd_send(rest[0], ["gui"])
    if cmd == "send":
        if len(rest) < 2:
            sys.exit("usage: bots.py send <bot> <console command...>")
        return cmd_send(rest[0], rest[1:])
    if cmd == "team-key":
        return cmd_team_key()
    if cmd == "_relay":
        _relay_main(*rest[:4]); return 0
    if cmd == "play":
        if not rest:
            sys.exit('usage: bots.py play "<world name>" [bot1 bot3 ...]')
        return cmd_play(rest[0], rest[1:])
    if cmd == "stop":
        cmd_stop(); return 0
    sys.exit(f"unknown command '{cmd}' — try: setup, sync, run, stop")


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
