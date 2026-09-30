# Headless bot rig for Windows - a PowerShell port of bots.py + sync_bots.py, so Windows needs no
# Python. Same commands, same files (console.json, team-key.txt, config.properties), same exit codes.
# macOS/Linux keep using bots.py through the .sh wrappers. Keep the two in step when changing logic.
#
#   setup                         first run on a new machine: find Java/Prism, install Fabric
#   sync [--check]                mirror Prism mods+config into the rigs
#   run <bot> [heap] [host:port]  launch; join straight away if a server is given
#   standby <bot> [heap]          launch to the title screen and wait
#   connect <bot> <host:port> [--wait]   join now, or as soon as the world opens
#   disconnect <bot>              leave the server, stay running
#   gui <bot>                     what's on the bot's screen (buttons, text)
#   send <bot> <command...>       any hmc-specifics console command (click, msg, / ...)
#   team-key                      show the shared team key (for your own client's config)
#   play "<world>" [bots...]      bots to standby + your Prism game straight into that world
#   stop                          stop headless bots only - never your Prism client
#
# Overrides, if detection picks the wrong thing:
#   headless\prism-dir.txt   PrismLauncher data folder (the one containing instances\)
#   headless\java-path.txt   a Java 21 java.exe, or the JDK folder
#   env: BOTS_JAVA, PRISM_DIR, PRISM_BIN, PRISM_INSTANCE

$ErrorActionPreference = 'Stop'
$HERE = $PSScriptRoot
$SELF = $PSCommandPath
$LAUNCHER = Join-Path $HERE 'headlessmc-launcher-2.10.0.jar'
$MC_VERSION = '1.21.1'
$FABRIC_LOADER = '0.19.5'
$VERSION_NAME = "fabric-loader-$FABRIC_LOADER-$MC_VERSION"
$UTF8 = New-Object System.Text.UTF8Encoding $false
$LATIN1 = [Text.Encoding]::GetEncoding(28591)
# Child stdin inherits this encoding, and .NET Framework writes its BOM the moment a child starts;
# HeadlessMC would then read "\uFEFFfabric ..." and reject the first command.
try { [Console]::InputEncoding = $UTF8 } catch {}

function Say($m) { [Console]::Out.WriteLine($m) }
function Die($m) { [Console]::Error.WriteLine($m); exit 1 }
function Read-Trimmed($p) { ([IO.File]::ReadAllText($p)).Trim() }
function Get-OptFile($name) {
    $p = Join-Path $HERE $name
    if (Test-Path -LiteralPath $p -PathType Leaf) { $v = Read-Trimmed $p; if ($v) { return $v } }
    $null
}

# ---------------------------------------------------------------- where things live

function Get-PrismDir([switch]$Quiet) {
    $c = @()
    if ($env:PRISM_DIR) { $c += $env:PRISM_DIR }
    $o = Get-OptFile 'prism-dir.txt'; if ($o) { $c += $o }
    if ($env:APPDATA) { $c += (Join-Path $env:APPDATA 'PrismLauncher') }
    $c += (Join-Path $HOME 'scoop\persist\prismlauncher')
    foreach ($x in $c) {
        if ($x -and (Test-Path -LiteralPath (Join-Path $x 'instances') -PathType Container)) { return $x }
    }
    if ($Quiet) { return $null }
    Die "Can't find PrismLauncher's data folder. Set PRISM_DIR, or write its path into headless\prism-dir.txt (Prism: Folders -> Launcher Root)."
}

function Get-McDir { Join-Path $env:APPDATA '.minecraft' }   # HeadlessMC's game files (versions, libraries, assets)

function Get-JavaMajor($exe) {
    try {
        $psi = New-Object Diagnostics.ProcessStartInfo $exe, '-version'
        $psi.UseShellExecute = $false; $psi.CreateNoWindow = $true
        $psi.RedirectStandardError = $true; $psi.RedirectStandardOutput = $true
        $p = [Diagnostics.Process]::Start($psi)
        $err = $p.StandardError.ReadToEnd(); $null = $p.StandardOutput.ReadToEnd(); $null = $p.WaitForExit(20000)
        if ($err -match 'version "(\d+)') { return [int]$matches[1] }
    } catch {}
    $null
}

function Find-Java {
    $c = New-Object Collections.Generic.List[string]
    if ($env:BOTS_JAVA) { $c.Add($env:BOTS_JAVA) }
    $o = Get-OptFile 'java-path.txt'
    if ($o) {
        if (Test-Path -LiteralPath $o -PathType Container) { $o = Join-Path $o 'bin\java.exe' }
        $c.Add($o)
    }
    if ($env:JAVA_HOME) { $c.Add((Join-Path $env:JAVA_HOME 'bin\java.exe')) }
    foreach ($root in (@($env:ProgramFiles, $env:ProgramW6432) | Where-Object { $_ } | Select-Object -Unique)) {
        foreach ($v in 'Eclipse Adoptium', 'Java', 'Microsoft', 'Zulu', 'Amazon Corretto', 'BellSoft') {
            Get-ChildItem -Path (Join-Path $root $v) -Directory -Filter '*21*' -ErrorAction SilentlyContinue |
                ForEach-Object { $c.Add((Join-Path $_.FullName 'bin\java.exe')) }
        }
    }
    $pd = Get-PrismDir -Quiet                                   # Prism's own downloaded runtimes
    if ($pd) {
        Get-ChildItem -Path (Join-Path $pd 'java') -Recurse -Depth 3 -Filter 'java.exe' -ErrorAction SilentlyContinue |
            ForEach-Object { $c.Add($_.FullName) }
    }
    $w = Get-Command java.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($w) { $c.Add($w.Source) }
    foreach ($x in $c) {
        if ((Test-Path -LiteralPath $x -PathType Leaf) -and ((Get-JavaMajor $x) -ge 21)) { return $x }
    }
    Die 'No Java 21 found. Install Temurin 21 (adoptium.net), set BOTS_JAVA, or put its path in headless\java-path.txt.'
}

function Get-Rigs {
    @(Get-ChildItem -LiteralPath $HERE -Directory |
        Where-Object { $_.Name -match '^bot\d+$' -and (Test-Path -LiteralPath (Join-Path $_.FullName 'identity.properties')) } |
        Sort-Object Name | ForEach-Object { $_.Name })
}

function Write-Config($rig, $java, $gameargs) {
    # Rewrite the machine-specific keys of <rig>\HeadlessMC\config.properties, keep the rest.
    $cfg = Join-Path $HERE "$rig\HeadlessMC\config.properties"
    $null = New-Item -ItemType Directory -Force (Split-Path $cfg)
    $keep = New-Object Collections.Generic.List[string]
    if (Test-Path -LiteralPath $cfg) {
        foreach ($l in [IO.File]::ReadAllLines($cfg)) {
            if ($l -notmatch '^hmc\.(gamedir|java\.versions|gameargs)=') { $keep.Add($l) }
        }
    }
    # <rig>\identity.properties (tracked in git) wins: username, uuid, fixed HeadlessMC flags
    $ident = Join-Path $HERE "$rig\identity.properties"
    if (Test-Path -LiteralPath $ident) {
        $ids = @([IO.File]::ReadAllLines($ident) | Where-Object { $_.Trim() -and -not $_.StartsWith('#') })
        $idkeys = New-Object Collections.Generic.HashSet[string]
        foreach ($l in $ids) { $null = $idkeys.Add($l.Split([char]'=', 2)[0]) }
        $merged = New-Object Collections.Generic.List[string]
        foreach ($l in $ids) { $merged.Add($l) }
        foreach ($l in $keep) { if (-not $idkeys.Contains($l.Split([char]'=', 2)[0])) { $merged.Add($l) } }
        $keep = $merged
    }
    $out = New-Object Collections.Generic.List[string]
    $out.Add('hmc.gamedir=' + ((Join-Path $HERE "$rig\gamedir") -replace '\\', '/'))   # '\' is an escape in .properties
    foreach ($l in $keep) { if ($l.Trim()) { $out.Add($l) } }
    $out.Add('hmc.java.versions=' + ($java -replace '\\', '/'))
    if ($gameargs) { $out.Add("hmc.gameargs=$gameargs") }
    [IO.File]::WriteAllText($cfg, (($out -join "`n") + "`n"), $UTF8)
}

function Read-PropFile($path, $key) {
    if (-not (Test-Path -LiteralPath $path)) { return $null }
    foreach ($l in [IO.File]::ReadAllLines($path)) {
        if ($l.StartsWith("$key=")) { return $l.Split([char]'=', 2)[1].Trim() }
    }
    $null
}
function Read-Prop($rig, $key) { Read-PropFile (Join-Path $HERE "$rig\HeadlessMC\config.properties") $key }
function Get-Ident($rig, $key) { Read-PropFile (Join-Path $HERE "$rig\identity.properties") $key }

function Get-BotName($rig) { $n = Read-Prop $rig 'hmc.offline.username'; if ($n) { $n } else { $rig } }

function New-StdinWriter($p) {
    # BOM-less UTF-8 with plain \n line ends, whatever the console encoding is.
    $w = New-Object IO.StreamWriter($p.StandardInput.BaseStream, $UTF8)
    $w.AutoFlush = $true; $w.NewLine = "`n"
    $w
}

function Invoke-Hmc($rig, $java, [string[]]$commands) {
    # Feed commands to the HeadlessMC launcher, running inside the rig folder; return its output.
    $psi = New-Object Diagnostics.ProcessStartInfo
    $psi.FileName = $env:ComSpec
    $psi.Arguments = '/s /c ""' + $java + '" -jar "' + $LAUNCHER + '" 2>&1"'
    $psi.WorkingDirectory = Join-Path $HERE $rig
    $psi.UseShellExecute = $false; $psi.CreateNoWindow = $true
    $psi.RedirectStandardInput = $true; $psi.RedirectStandardOutput = $true
    $p = [Diagnostics.Process]::Start($psi)
    $in = New-StdinWriter $p
    foreach ($c in $commands) { $in.WriteLine($c) }
    $in.Close()
    $o = $p.StandardOutput.ReadToEnd()
    $p.WaitForExit()
    $o
}

# ---------------------------------------------------------------- team key

function New-UrlToken([int]$n) {
    $b = New-Object byte[] $n
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
    [Convert]::ToBase64String($b).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Get-TeamKey {
    # The team key every local bot shares (created once). Signs and encrypts bus messages.
    $f = Join-Path $HERE 'team-key.txt'   # gitignored - never commit it
    if (Test-Path -LiteralPath $f) { $k = Read-Trimmed $f; if ($k) { return $k } }
    $k = New-UrlToken 18
    [IO.File]::WriteAllText($f, "$k`n", $UTF8)
    $k
}

function Get-KeyFingerprint($key) {
    # Same as ZymbotConfig.fingerprint in the mod: safe to show, compare it between games.
    $h = [Security.Cryptography.SHA256]::Create().ComputeHash($UTF8.GetBytes("zymbot-fp:$key"))
    (($h | ForEach-Object { $_.ToString('x2') }) -join '').Substring(0, 6)
}

function Set-RigTeamKey($rig, $key) {
    # Put the shared key into the rig's zymbot.json, and list the rig's own account as a Bot.
    $p = Join-Path $HERE "$rig\gamedir\config\zymbot.json"
    $null = New-Item -ItemType Directory -Force (Split-Path $p)
    $cfg = $null
    try { $cfg = [IO.File]::ReadAllText($p) | ConvertFrom-Json } catch {}
    if ($cfg -isnot [Management.Automation.PSCustomObject]) { $cfg = New-Object PSObject }
    $before = ConvertTo-Json -InputObject $cfg -Depth 50 -Compress
    $cfg | Add-Member -NotePropertyName team_key -NotePropertyValue $key -Force
    $uuid = Get-Ident $rig 'hmc.offline.uuid'
    $name = Get-Ident $rig 'hmc.offline.username'; if (-not $name) { $name = $rig }
    if ($uuid) {
        $acc = @()
        if ($cfg.accounts) { $acc = @($cfg.accounts | Where-Object { ([string]$_.uuid).ToLower() -ne $uuid.ToLower() }) }
        $acc += [pscustomobject][ordered]@{ uuid = $uuid; name = $name; role = 'bot' }
        $cfg | Add-Member -NotePropertyName accounts -NotePropertyValue $acc -Force
    }
    if ((ConvertTo-Json -InputObject $cfg -Depth 50 -Compress) -ne $before) {
        [IO.File]::WriteAllText($p, (ConvertTo-Json -InputObject $cfg -Depth 50), $UTF8)
    }
}

function Cmd-TeamKey {
    $key = Get-TeamKey
    Say 'Your team key (keep it private - anyone with it can read and send bot messages):'
    Say "  $key"
    Say "fingerprint: $(Get-KeyFingerprint $key)   (safe to share; it must match in every game)"
    Say 'To add your own game to the team: Mod Menu -> Zymbot -> Team key: paste -> Save key,'
    Say 'then Accounts: + <you> as Teammate, and Servers: + this server.'
    Say '(Or edit minecraft/config/zymbot.json -> "team_key" and restart.)'
    0
}

# ---------------------------------------------------------------- setup / stop

function Cmd-Setup {
    $java = Find-Java
    Say "java 21 : $java"
    Say "prism   : $(Get-PrismDir)"
    Say "mc files: $(Get-McDir)"
    if (-not (Test-Path -LiteralPath $LAUNCHER -PathType Leaf)) { Die "missing $(Split-Path $LAUNCHER -Leaf) - copy it into $HERE" }
    $rigs = Get-Rigs
    foreach ($rig in $rigs) { Write-Config $rig $java $null; Say "[$rig] config written for this machine" }
    $key = Get-TeamKey
    foreach ($rig in $rigs) { Set-RigTeamKey $rig $key }
    Say "team key: shared by $($rigs.Count) rig(s), each listed as a Bot - for your own client, see: team-key.bat"
    $vdir = Join-Path (Get-McDir) "versions\$VERSION_NAME"
    if (Test-Path -LiteralPath $vdir -PathType Container) {
        Say "$VERSION_NAME already installed"
    } else {
        Say "installing $VERSION_NAME via HeadlessMC (downloads Minecraft $MC_VERSION + Fabric) ..."
        Say (Invoke-Hmc $rigs[0] $java @("fabric $MC_VERSION --uid $FABRIC_LOADER", 'versions', 'exit'))
        if (-not (Test-Path -LiteralPath $vdir -PathType Container)) { Die "Fabric install didn't produce the expected version folder - check the output above." }
    }
    Say 'setup done. Next: sync, then run.'
    0
}

function Get-HeadlessPids {
    # Headless bot game processes, HeadlessMC launchers and console relays - never Prism.
    @(Get-CimInstance Win32_Process | Where-Object {
        $_.ProcessId -ne $PID -and $_.CommandLine -and (
            $_.CommandLine -match 'minecraft\.launcher\.brand=HeadlessMc' -or
            $_.CommandLine -match 'headlessmc-launcher' -or
            $_.CommandLine -match 'bots\.(py|ps1)"?\s+_relay') } | ForEach-Object { [int]$_.ProcessId })
}

function Get-GamePid($name) {
    # (pid, working set MB) of the running game process for bot <name>, or ($null, $null).
    $rx = '--username ' + [regex]::Escape($name) + '( |$)'
    $p = Get-CimInstance Win32_Process | Where-Object {
        $_.CommandLine -match 'minecraft\.launcher\.brand=HeadlessMc' -and $_.CommandLine -match $rx } | Select-Object -First 1
    if ($p) { return @([int]$p.ProcessId, [long][math]::Floor($p.WorkingSetSize / 1MB)) }
    @($null, $null)
}

function Cmd-Stop([switch]$check) {
    $pids = Get-HeadlessPids
    if ($check) {
        # stop-bots-check.bat: what stop-bots would stop, touching nothing - like sync-bots --check (owner, 2026-09-28)
        foreach ($rig in Get-Rigs) { Say "[$rig] $(if (Get-Console $rig) { 'running' } else { 'not running' })" }
        foreach ($id in $pids) {
            $c = (Get-CimInstance Win32_Process -Filter "ProcessId=$id" -ErrorAction SilentlyContinue).CommandLine
            $what = if ($c -match '_relay') { 'console relay' } elseif ($c -match 'headlessmc-launcher') { 'HeadlessMC launcher' } else { 'bot game' }
            Say "  $id  $what"
        }
        Say "$($pids.Count) headless process$(if ($pids.Count -ne 1) { 'es' }) - stop-bots.bat would stop $(if ($pids.Count -eq 1) { 'it' } else { 'them' }); your Prism game is never on this list"
        return 0
    }
    foreach ($id in $pids) { try { Stop-Process -Id $id -Force -ErrorAction Stop } catch {} }
    # Don't say "stopped" until they're really gone: Stop-Process only asks, and a game can take a
    # moment to let go (owner, 2026-09-28). Backstop 30 s, then say which are left.
    $until = (Get-Date).AddSeconds(30)
    while (($left = @(Get-HeadlessPids)).Count -and (Get-Date) -lt $until) {
        foreach ($id in $left) { try { Stop-Process -Id $id -Force -ErrorAction Stop } catch {} }
        Start-Sleep -Milliseconds 500
    }
    foreach ($rig in Get-Rigs) { Remove-Item -LiteralPath (Join-Path $HERE "$rig\launcher.pid") -Force -ErrorAction SilentlyContinue }
    if ($left.Count) { Say "NOT all stopped after 30 s - still running: $($left -join ', ')"; return 1 }
    Say "stopped headless bots ($($pids.Count) process$(if ($pids.Count -ne 1) { 'es' })) - none left running"
    0
}

# ---------------------------------------------------------------- run / verdict

function Get-LogSize($log) { try { (New-Object IO.FileInfo $log).Length } catch { 0 } }

function Read-LogFrom($log, [long]$offset) {
    # The game is still writing the log, so open it shared.
    try { $fs = [IO.File]::Open($log, 'Open', 'Read', 'ReadWrite, Delete') } catch { return $null }
    try {
        $null = $fs.Seek([math]::Min($offset, $fs.Length), 'Begin')
        (New-Object IO.StreamReader($fs, $UTF8)).ReadToEnd()
    } finally { $fs.Dispose() }
}

function Test-Rig($bot) {
    if (-not (Test-Path -LiteralPath (Join-Path $HERE "$bot\identity.properties"))) { Die "no rig at $bot\ (needs $bot\identity.properties)" }
    $u = Join-Path $HERE "$bot\.unsynced"
    if (Test-Path -LiteralPath $u) {
        Say "[$bot] WARNING: this rig is marked UNSYNCED - its mods may not match the host."
        Say ('        ' + (Read-Trimmed $u))
    }
    if (-not (Test-Path -LiteralPath (Join-Path (Get-McDir) "versions\$VERSION_NAME") -PathType Container)) {
        Die "$VERSION_NAME isn't installed on this machine - run setup first."
    }
}

function Cmd-Run($bot, $heap, $addr) {
    # Launch through the console relay, so the bot keeps a console we can send commands to.
    if (-not $heap) { $heap = '3G' }
    Test-Rig $bot
    if (Get-Console $bot) { Die "[$bot] is already running - stop it first, or use connect/send" }
    $java = Find-Java
    $gameargs = $null; if ($addr) { $gameargs = "--quickPlayMultiplayer $addr" }
    Write-Config $bot $java $gameargs
    $name = Get-BotName $bot
    $log = Join-Path $HERE ("$bot\run-" + (Get-Date -Format 'HHmmss') + '.log')
    $what = if ($addr) { ", joining $addr" } else { ' (standby at title screen)' }
    Say "[$name] launching headless, heap=$heap$what -> $bot\$(Split-Path $log -Leaf)"
    Start-Relay $bot $java $heap $log
    Wait-Verdict $bot $name $log $addr 0 300
}

function Wait-Verdict($bot, $name, $log, $addr, [long]$offset, [int]$timeout) {
    # Watch the log from $offset until the join (or title screen) succeeds or fails.
    $since = $null
    $shown = 0                                                   # ZBOT_SHOW_LOG: how much of $text is printed
    $until = (Get-Date).AddSeconds($timeout)
    while ((Get-Date) -lt $until) {
        Start-Sleep -Seconds 2
        $text = Read-LogFrom $log $offset
        if ($null -eq $text) { continue }
        # ZBOT_SHOW_LOG=1: echo the bot's HeadlessMC log live while we wait, so a double-clicked
        # standby-bot1-singleplayer.bat shows what the bot is doing (owner, 2026-09-28)
        if ($env:ZBOT_SHOW_LOG -and $text.Length -gt $shown) {
            $new = $text.Substring($shown)
            $cut = $new.LastIndexOf("`n")                        # whole lines only; the rest next time
            if ($cut -ge 0) {
                foreach ($l in ($new.Substring(0, $cut) -split "\r?\n")) { if ($l) { Write-Host "  | $l" -ForegroundColor DarkGray } }
                $shown += $cut + 1
            }
        }
        $lines = $text -split "\r?\n"
        $norealms = @($lines | Where-Object { $_ -notmatch 'realms' })
        $nr = $norealms -join "`n"
        if ($text -cmatch 'Game crashed|OutOfMemory') {
            Say "[$name] CRASHED - see $log and $bot\gamedir\crash-reports\"; return 1
        }
        if ($addr -and $text.Contains('registry entries that are unknown to this client')) {
            $ns = @()
            $i = $text.IndexOf('may be related')
            if ($i -ge 0) {
                $after = @(($text.Substring($i) -split "\r?\n") | Select-Object -Skip 1 -First 13)
                $ns = @($after | ForEach-Object { $_.Trim() } | Where-Object { $_ -cmatch '^[a-z0-9_.-]+$' } | Sort-Object -Unique)
            }
            Say "[$name] MOD MISMATCH - host sent registry entries this bot doesn't have. Namespaces:"
            foreach ($n in $ns) { Say "          $n" }
            Say '        Remove those mod ids from sync-exclude.txt, run sync, relaunch.'; return 6
        }
        if ($addr -and $nr -match 'Failed to verify username|unverified_username|multiplayer\.disconnect|Disconnected from server') {
            Say "[$name] REJECTED by server:"
            foreach ($l in $norealms) {
                if ($l -match 'verify username|unverified|multiplayer\.disconnect|Disconnected from server') { Say "        $l" }
            }
            return 2
        }
        if ($addr -and $nr -match "Client disconnected with reason") {
            Say "[$name] DROPPED while joining: $((($norealms | Where-Object { $_ -match 'Client disconnected' }) | Select-Object -First 1) -replace '^.*reason: ', '')"
            return 7
        }
        if ($addr -and $nr -match "Connection refused|Couldn.t connect|connect\.failed") {
            Say "[$name] could not reach $addr - is the world open to LAN on that port?"; return 3
        }
        $joined = $text -match 'joined the game|Loaded [0-9]+ advancements'
        if ($addr -and $text.Contains('Connecting to') -and -not $joined) {
            if (-not $since) { $since = Get-Date }
            if (((Get-Date) - $since).TotalSeconds -gt 60) {
                Say "[$name] CONNECTED THEN DROPPED - reached $addr but never entered the world."
                Say "        No reason logged - try: gui.bat $bot   (shows the screen it's stuck on)"
                return 5
            }
        } else { $since = $null }
        if ($addr -and $joined) {
            $g = Get-GamePid $name
            if ($g[1]) { Say "[$name] IN THE WORLD (rss $($g[1])MB)" } else { Say "[$name] IN THE WORLD" }
            return 0
        }
        if (-not $addr -and $text.Contains('Realms')) {
            Say "[$name] at title screen - standing by. Next: connect-bot1.bat (or connect-with-arg.bat $bot <host:port> [--wait])"
            return 0
        }
    }
    Say "[$name] no verdict after ${timeout}s - check $log"
    4
}

# ---------------------------------------------------------------- console relay
#
# HeadlessMC reads commands on its console, and hmc-specifics turns them into game actions
# (connect, disconnect, gui, click, msg, /cmd). The relay is a hidden background PowerShell that
# owns that console and accepts lines on 127.0.0.1 only, with a random token from
# <rig>\console.json. Nothing on another machine can reach it.

function Start-Relay($bot, $java, $heap, $log) {
    $a = '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + $SELF + '" _relay "' +
         $bot + '" "' + $java + '" "' + $heap + '" "' + $log + '"'
    Start-Process -FilePath 'powershell.exe' -ArgumentList $a -WindowStyle Hidden
    for ($i = 0; $i -lt 150; $i++) {                            # wait for it to publish its port
        if (Get-Console $bot) { return }
        Start-Sleep -Milliseconds 100
    }
    Die "[$bot] console relay didn't start - see $log"
}

function Invoke-Relay($bot, $java, $heap, $log) {
    $info = Join-Path $HERE "$bot\console.json"
    $psi = New-Object Diagnostics.ProcessStartInfo
    $psi.FileName = $env:ComSpec
    $psi.Arguments = '/s /c ""' + $java + '" -jar "' + $LAUNCHER + '" > "' + $log + '" 2>&1"'
    $psi.WorkingDirectory = Join-Path $HERE $bot
    $psi.UseShellExecute = $false; $psi.CreateNoWindow = $true; $psi.RedirectStandardInput = $true
    $p = [Diagnostics.Process]::Start($psi)
    $in = New-StdinWriter $p
    # -Dhmc.jline.enabled=false: the game's console is our pipe, not a terminal (JLine would crash)
    $in.WriteLine("launch $VERSION_NAME -lwjgl -offline --jvm `"-Xmx$heap -Dhmc.jline.enabled=false`"")
    $srv = New-Object Net.Sockets.TcpListener([Net.IPAddress]::Loopback, 0)   # loopback only, any free port
    $srv.Start()
    $token = New-UrlToken 24
    $json = ConvertTo-Json -InputObject ([ordered]@{ port = $srv.LocalEndpoint.Port; token = $token;
        relay_pid = $PID; launcher_pid = $p.Id; log = $log })
    [IO.File]::WriteAllText($info, $json, $UTF8)
    try {
        while (-not $p.HasExited) {                             # until the game (and launcher) ends
            if (-not $srv.Pending()) { Start-Sleep -Milliseconds 100; continue }
            $cl = $srv.AcceptTcpClient()
            try {
                $cl.ReceiveTimeout = 5000
                $s = $cl.GetStream()
                $r = New-Object IO.StreamReader($s, $UTF8)
                $w = New-Object IO.StreamWriter($s, $UTF8); $w.AutoFlush = $true; $w.NewLine = "`n"
                if ($r.ReadLine() -cne $token) { $w.WriteLine('denied') }
                else {
                    while ($null -ne ($line = $r.ReadLine())) {
                        if (-not $line) { continue }
                        $in.WriteLine($line)
                        $w.WriteLine('ok')
                    }
                }
            } catch {} finally { $cl.Close() }
        }
    } finally {
        $srv.Stop()
        Remove-Item -LiteralPath $info -Force -ErrorAction SilentlyContinue
    }
}

function Test-Relay([int]$procId, $bot) {
    # Is this PID our console relay for this bot (bots.ps1/bots.py _relay "<bot>")?
    $p = Get-CimInstance Win32_Process -Filter "ProcessId=$procId" -ErrorAction SilentlyContinue
    [bool]($p -and $p.CommandLine -and
           $p.CommandLine -match ('bots\.(py|ps1)"?\s+_relay\s+"?' + [regex]::Escape($bot) + '\b'))
}

function Get-Console($bot) {
    # The running bot's relay info, or $null.
    $info = Join-Path $HERE "$bot\console.json"
    try { $c = [IO.File]::ReadAllText($info) | ConvertFrom-Json } catch { return $null }
    # Not just "a process with that PID": Windows reuses PIDs, and a console.json left from yesterday
    # pointed at some other program, so play.bat said "[bot1] already running" and skipped it (2026-09-28).
    if (-not $c.relay_pid -or -not (Test-Relay ([int]$c.relay_pid) $bot)) {
        Remove-Item -LiteralPath $info -Force -ErrorAction SilentlyContinue
        return $null
    }
    $c
}

function Send-Line($bot, $line) {
    $c = Get-Console $bot
    if (-not $c) { Die "[$bot] isn't running (start it: standby.bat $bot)" }
    $reply = $null
    $cl = New-Object Net.Sockets.TcpClient
    try {
        $cl.ReceiveTimeout = 5000
        $cl.Connect('127.0.0.1', [int]$c.port)
        $s = $cl.GetStream()
        $b = $UTF8.GetBytes("$($c.token)`n$line`n")
        $s.Write($b, 0, $b.Length)
        $reply = (New-Object IO.StreamReader($s, $UTF8)).ReadLine()
    } catch {} finally { $cl.Close() }
    if ($reply -ne 'ok') { Die "[$bot] console refused the command ($(if ($reply) { $reply } else { 'no reply' }))" }
}

function Cmd-Send($bot, [string[]]$words, [int]$show = 3) {
    # Send one console command (hmc-specifics: gui, click, connect, disconnect, msg, / ...).
    $line = $words -join ' '
    $c = Get-Console $bot
    if (-not $c) { Die "[$bot] isn't running (start it: standby.bat $bot)" }
    $offset = Get-LogSize $c.log
    Send-Line $bot $line
    Start-Sleep -Seconds $show
    $new = Read-LogFrom $c.log $offset
    $shown = @(($new -split "\r?\n") | Where-Object { $_.Trim() -and $_ -cnotmatch 'Missing sound' })
    if ($shown.Count) { Say (($shown | Select-Object -Last 60) -join "`n") }
    else { Say "(sent '$line'; no console output within ${show}s)" }
    0
}

function Test-Port($h, [int]$port) {
    $t = New-Object Net.Sockets.TcpClient
    try { $ar = $t.BeginConnect($h, $port, $null, $null); ($ar.AsyncWaitHandle.WaitOne(2000) -and $t.Connected) }
    catch { $false } finally { $t.Close() }
}

function Cmd-Connect($bot, $addr, [bool]$wait, [int]$waitSecs = 900) {
    # Join a server from the title screen. --wait: keep checking until the world is open, then join.
    if ($addr.Contains(':')) { $i = $addr.LastIndexOf(':'); $h = $addr.Substring(0, $i); $port = [int]$addr.Substring($i + 1) }
    else { $h = $addr; $port = 25565 }
    $name = Get-BotName $bot
    $c = Get-Console $bot
    if (-not $c) { Die "[$bot] isn't running (start it: standby.bat $bot)" }
    $start = Get-LogSize $c.log                                 # a summon after this point counts
    # Already in a world (the last "started:" is newer than the last "stopped")? Don't kick it out to rejoin.
    $all = Read-LogFrom $c.log 0
    if ($all -and $all.LastIndexOf('[decision] started:') -gt [math]::Max($all.LastIndexOf('[decision] stopped'), -1) -and
        $all.LastIndexOf('[decision] started:') -ge 0) {
        Say "[$name] already in a world - not connecting again (disconnect-with-arg.bat $bot first to switch)"; return 0
    }
    if ($wait) {
        Say "[$name] waiting for ${h}:$port to open (up to $([int]($waitSecs / 60)) min)..."
        $until = (Get-Date).AddSeconds($waitSecs)
        while (-not (Test-Port $h $port)) {
            if ((Get-Date) -gt $until) { Say "[$name] ${h}:$port never opened"; return 3 }
            Start-Sleep -Seconds 3
        }
        Start-Sleep -Seconds 2                                  # let the LAN server finish opening
    }
    # The first join after a boot often times out: the host allows 15 s, and the bot spends ~16 s on
    # the pack's config data (3 times on 2026-09-26). The second try, with that data cached, works.
    # A summon (/zbot summon auto on in the host's world) may be joining the bot already. A second
    # "connect" on top of a join still loading collided with it: "Failed to decode packet
    # 'clientbound/minecraft:update_recipes'", kicked, and standby-bot1-singleplayer.bat gave up while the
    # summon got Bot1 in 10 s later (2026-09-28). So before each try, look: in already -> done; a summon
    # joining -> wait for that join instead of sending another.
    $seen = $start
    for ($try = 1; $try -le 2; $try++) {
        $offset = Get-LogSize $c.log
        $since = Read-LogFrom $c.log $seen
        if ($since -match '\[decision\] started:|joined the game') {
            Say "[$name] already in the world (summoned)"; return 0
        }
        if ($since -match '\[zymbot\] summoned') {
            Say "[$name] a summon is already joining it - waiting for that join instead of connecting again"
        } else {
            Say "[$name] connecting to ${h}:$port$(if ($try -gt 1) { ' (retry)' })"
            Send-Line $bot "connect $h $port"
        }
        $v = Wait-Verdict $bot $name $c.log "${h}:$port" $offset 180
        if ($v -ne 7 -and $v -ne 5) { return $v }
        $seen = Get-LogSize $c.log
        Start-Sleep -Seconds 3
    }
    $v
}

# ---------------------------------------------------------------- play

function Get-PrismBin {
    if ($env:PRISM_BIN) { return $env:PRISM_BIN }
    $c = @()
    $pd = Get-PrismDir -Quiet
    if ($pd) { $c += (Join-Path $pd 'prismlauncher.exe') }      # portable install: program sits in the data folder
    $c += "$env:LOCALAPPDATA\Programs\PrismLauncher\prismlauncher.exe"
    $c += "$env:ProgramFiles\PrismLauncher\prismlauncher.exe"
    foreach ($x in $c) { if (Test-Path -LiteralPath $x -PathType Leaf) { return $x } }
    Die "Can't find the Prism Launcher program. Set PRISM_BIN to it."
}

function Test-GameRunning($instance) {
    # Is this Prism instance's game already running? Looks for a java process started from it.
    $marker = "instances\$instance"
    try { $cmds = @(Get-CimInstance Win32_Process -Filter "Name like 'java%'" | ForEach-Object { $_.CommandLine }) }
    catch { return $false }                                     # can't tell: launch as asked
    foreach ($l in $cmds) {
        if ($l -and ($l -replace '/', '\').Contains($marker) -and $l -notmatch 'headless') { return $true }
    }
    $false
}

function Cmd-Play($world, [string[]]$bots, [switch]$gameOnly) {
    # $gameOnly: prism.bat - just your game into the world, no bots (owner, 2026-09-28)
    if (-not $bots -or $bots.Count -eq 0) { $bots = @('bot1') }
    $instance = $env:PRISM_INSTANCE
    if (-not $instance) {
        $src = Join-Path $HERE "$($bots[0])\.source"
        if (Test-Path -LiteralPath $src) { $instance = Read-Trimmed $src }
    }
    if (-not $instance) { Die 'Which Prism instance? Set PRISM_INSTANCE to its folder name.' }
    # Your game first, bots once your world is loading: booting both at once on this laptop took
    # ~3 min each instead of ~1.5 (2026-09-26), so the bot joined almost 5 min after launch.
    if (Test-GameRunning $instance) {
        Say "[you] '$instance' is already running - not launching it again"
    } else {
        $log = Join-Path (Get-PrismDir) "instances\$instance\minecraft\logs\latest.log"
        $since = Get-Date
        Start-Process -FilePath (Get-PrismBin) -ArgumentList ('-l "' + $instance + '" -w "' + $world + '"')
        if ($gameOnly) { Say "[you] launching '$instance' straight into '$world' (no bots)"; return 0 }
        Say "[you] Prism is starting your game (instance '$instance') and loading the singleplayer world '$world'."
        Say "      The bots start once that world is loading; the Zymbot console opens after."
        Say "      Your game's live log: in Prism, right-click '$instance' -> Edit -> Minecraft Log"
        Say "      (to open it with every launch: Prism Settings -> Minecraft -> show the console while the game is running)."
        $until = $since.AddMinutes(6)
        while ((Get-Date) -lt $until) {
            Start-Sleep -Seconds 3
            if ((Test-Path -LiteralPath $log) -and (Get-Item -LiteralPath $log).LastWriteTime -gt $since -and
                (Read-LogFrom $log 0) -match 'Starting integrated minecraft server|Game crashed') { break }
        }
        if ((Get-Date) -ge $until) { Say "[you] no world after 6 min - starting the bots anyway" }
    }
    if ($gameOnly) { return 0 }
    foreach ($b in $bots) {
        if (Get-Console $b) { Say "[$b] already running"; continue }
        # standby, then join by itself: with your game already open, its auto-summon had long finished and
        # Bot1 sat at its title screen (owner, 2026-09-28). A summon arriving first is fine: connect waits for it.
        Start-Process -FilePath 'powershell.exe' -WindowStyle Hidden -ArgumentList (
            '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + $SELF + '" _standby-join "' + $b + '"')
        Say "[$b] starting in standby (about 1.5 min), then joining 127.0.0.1:25565"
    }
    Say '      Your world opens to LAN by itself with /zbot lan auto on; the bots join it once it is open.'
    0
}

# ---------------------------------------------------------------- sync (port of sync_bots.py)
#
# Per rig: <rig>\.source names the Prism instance to mirror; <rig>\.unsynced skips it.
# Mods whose fabric.mod.json id is in sync-exclude.txt are never copied - unless they register
# client-synced registry content, in which case the bot would be dropped on join without them.

$DEFAULT_SOURCE = '1.21.1 ZymCivModded Server Minimal'
$PATCH_NAME = 'moonlight-headless-patch-1.0.0.jar'
$PATCH_CANDIDATES = @((Join-Path $HERE "..\moonlight-headless-patch\build\libs\$PATCH_NAME"), (Join-Path $HERE $PATCH_NAME))
$ZYMBOT_LIBS = Join-Path $HERE '..\mod\versions\1.21.1\build\libs'
$IGNORED_DEPS = @('minecraft', 'fabricloader', 'java', 'fabric', 'fabric-api', 'fabric-language-kotlin')
$RIG_OWNED_CONFIG = @('zymbot.json')   # belong to the rig, not to Prism: never deleted or overwritten
# Registries sent to every joining client (MC 1.21.1 intermediary field -> name).
$SYNCED_REGISTRIES = @{
    'field_41190' = 'attribute'; 'field_41175' = 'block'; 'field_41181' = 'block_entity_type'
    'field_41192' = 'command_argument_type'; 'field_41183' = 'custom_stat'
    'field_49658' = 'data_component_type'; 'field_51832' = 'enchantment_effect_component_type'
    'field_41177' = 'entity_type'; 'field_41173' = 'fluid'; 'field_41178' = 'item'; 'field_41187' = 'menu'
    'field_41174' = 'mob_effect'; 'field_41180' = 'particle_type'; 'field_41179' = 'potion'
    'field_41189' = 'recipe_serializer'; 'field_41172' = 'sound_event'; 'field_41193' = 'stat_type'
    'field_41195' = 'villager_profession'; 'field_41194' = 'villager_type'
}

function Open-Zip($jar) {
    Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::OpenRead($jar)
}

function Get-SyncedRegs($jar) {
    # Names of client-synced registries this jar registers into (heuristic, per class).
    $found = New-Object Collections.Generic.HashSet[string]
    $z = Open-Zip $jar
    try {
        foreach ($e in $z.Entries) {
            if (-not $e.FullName.EndsWith('.class')) { continue }
            $ms = New-Object IO.MemoryStream
            $st = $e.Open(); $st.CopyTo($ms); $st.Close()
            $s = $LATIN1.GetString($ms.ToArray())
            if (-not $s.Contains('class_7923') -or -not ($s.Contains('method_10230') -or $s.Contains('method_39197'))) { continue }
            foreach ($m in [regex]::Matches($s, 'field_\d+')) {
                $n = $SYNCED_REGISTRIES[$m.Value]
                if ($n) { $null = $found.Add($n) }
            }
        }
    } finally { $z.Dispose() }
    @($found | Sort-Object)
}

function Get-ModMeta($jar) {
    $raw = $null
    try {
        $z = Open-Zip $jar
        try {
            $e = $z.GetEntry('fabric.mod.json')
            if ($e) { $sr = New-Object IO.StreamReader($e.Open(), $UTF8); $raw = $sr.ReadToEnd(); $sr.Close() }
        } finally { $z.Dispose() }
    } catch {}
    if (-not $raw) { return [pscustomobject]@{ Id = $null; Deps = @() } }
    $raw = $raw -replace ',\s*([}\]])', '$1'
    $d = $null
    try { $d = $raw | ConvertFrom-Json } catch {}
    if ($d) {
        $deps = @()
        if ($d.depends) { $deps = @($d.depends.PSObject.Properties | ForEach-Object { $_.Name } | Where-Object { $IGNORED_DEPS -notcontains $_ }) }
        return [pscustomobject]@{ Id = $d.id; Deps = $deps }
    }
    if ($raw -match '"id"\s*:\s*"([^"]+)"') { return [pscustomobject]@{ Id = $matches[1]; Deps = @() } }
    [pscustomobject]@{ Id = $null; Deps = @() }
}

function Get-Excludes {
    $ids = New-Object Collections.Generic.HashSet[string]
    foreach ($l in [IO.File]::ReadAllLines((Join-Path $HERE 'sync-exclude.txt'))) {
        $l = $l.Split([char]'#', 2)[0].Trim()
        if ($l) { $null = $ids.Add($l) }
    }
    , $ids
}

function Get-WantedJars($dir, $excludes) {
    $keep = @(); $dropped = @(); $forced = @(); $deps = @{}
    foreach ($f in (Get-ChildItem -LiteralPath $dir -File | Where-Object { $_.Name.EndsWith('.jar') } | Sort-Object Name)) {
        $m = Get-ModMeta $f.FullName
        $mid = $m.Id
        $excluded = [bool]($mid -and $excludes.Contains($mid))
        $regs = @(); if ($excluded) { $regs = @(Get-SyncedRegs $f.FullName) }
        if ($excluded -and $regs.Count) {                      # excluded by taste, but the host will sync it
            $forced += [pscustomobject]@{ Id = $mid; Regs = $regs }
            $keep += $f.Name; $deps[$mid] = $m.Deps
        } elseif ($excluded) {
            $dropped += [pscustomobject]@{ Id = $mid; Jar = $f.Name }
        } else {
            $keep += $f.Name; if ($mid) { $deps[$mid] = $m.Deps }
        }
    }
    # a kept mod must not depend on something we excluded
    $exIds = @($dropped | ForEach-Object { $_.Id })
    $broken = @()
    foreach ($k in $deps.Keys) {
        $x = @($deps[$k] | Where-Object { $exIds -ccontains $_ } | Sort-Object)
        if ($x.Count) { $broken += "$k -> $($x -join ', ')" }
    }
    [pscustomobject]@{ Keep = $keep; Dropped = $dropped; Broken = $broken; Forced = $forced }
}

function Get-EpochSec($t) { [long][math]::Floor($t.ToUniversalTime().Ticks / 10000000) }

function Sync-Dir($src, $dst) {
    # Make $dst an exact copy of $src (like rsync -a --delete). Top-level $RIG_OWNED_CONFIG files are left alone.
    $null = New-Item -ItemType Directory -Force $dst
    $src = [IO.Path]::GetFullPath($src).TrimEnd('\'); $dst = [IO.Path]::GetFullPath($dst).TrimEnd('\')
    $old = @(Get-ChildItem -LiteralPath $dst -Recurse -Force -ErrorAction SilentlyContinue | Sort-Object { $_.FullName.Length } -Descending)
    foreach ($i in $old) {
        $rel = $i.FullName.Substring($dst.Length + 1)
        $s = Join-Path $src $rel
        if ($i.PSIsContainer) {
            if (-not (Test-Path -LiteralPath $s -PathType Container)) { Remove-Item -LiteralPath $i.FullName -Recurse -Force -ErrorAction SilentlyContinue }
        } elseif (-not ($rel -notmatch '\\' -and $RIG_OWNED_CONFIG -contains $i.Name) -and -not (Test-Path -LiteralPath $s)) {
            Remove-Item -LiteralPath $i.FullName -Force -ErrorAction SilentlyContinue
        }
    }
    foreach ($i in (Get-ChildItem -LiteralPath $src -Recurse -Force)) {
        $rel = $i.FullName.Substring($src.Length + 1)
        $d = Join-Path $dst $rel
        if ($i.PSIsContainer) { $null = New-Item -ItemType Directory -Force $d; continue }
        if ($rel -notmatch '\\' -and $RIG_OWNED_CONFIG -contains $i.Name) { continue }
        $di = Get-Item -LiteralPath $d -Force -ErrorAction SilentlyContinue
        if (-not $di -or $di.Length -ne $i.Length -or (Get-EpochSec $di.LastWriteTime) -ne (Get-EpochSec $i.LastWriteTime)) {
            $null = New-Item -ItemType Directory -Force (Split-Path $d)
            [IO.File]::Copy($i.FullName, $d, $true)             # keeps the source's modified time
        }
    }
}

function Get-BotOnlyJars($patch) {
    # Jars every rig gets that the host doesn't need.
    $out = @([pscustomobject]@{ Pre = 'moonlight-headless-patch-'; Src = $patch; Label = 'headless patch' })
    $hmc = @(Get-ChildItem -LiteralPath $HERE -File -Filter 'hmc-specifics-*-fabric-release.jar' | Sort-Object Name)
    if ($hmc.Count) { $out += [pscustomobject]@{ Pre = 'hmc-specifics-'; Src = $hmc[-1].FullName; Label = 'hmc-specifics console' } }
    $built = @(Get-ChildItem -Path $ZYMBOT_LIBS -File -Filter 'zymbot-*.jar' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime)
    if ($built.Count) { $out += [pscustomobject]@{ Pre = 'zymbot-'; Src = $built[-1].FullName; Label = 'zymbot' } }
    $out
}

function Test-Current($mods, $srcJar) {
    $d = Join-Path $mods (Split-Path $srcJar -Leaf)
    (Test-Path -LiteralPath $d) -and ((Get-Item -LiteralPath $d).LastWriteTime -ge (Get-Item -LiteralPath $srcJar).LastWriteTime)
}

function Cmd-Sync([string[]]$argv) {
    $check = $argv -contains '--check'
    $instances = Join-Path (Get-PrismDir) 'instances'
    $patch = $PATCH_CANDIDATES | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
    if (-not $patch) { Die "patch jar missing - build it (moonlight-headless-patch: gradlew build) or copy $PATCH_NAME next to this script" }
    $excludes = Get-Excludes
    $drift = $false

    foreach ($rig in @(Get-ChildItem -LiteralPath $HERE -Directory | Where-Object { $_.Name -match '^bot\d+$' } | Sort-Object Name | ForEach-Object { $_.Name })) {
        $rigDir = Join-Path $HERE $rig
        $u = Join-Path $rigDir '.unsynced'
        if (Test-Path -LiteralPath $u) { Say "[$rig] UNSYNCED - skipped ($(Read-Trimmed $u))"; continue }
        if (Get-Console $rig) { Say "[$rig] RUNNING - not touched (stop it first; swapping jars under a live game is unsafe)"; continue }

        $sf = Join-Path $rigDir '.source'
        $source = if (Test-Path -LiteralPath $sf) { Read-Trimmed $sf } else { $DEFAULT_SOURCE }
        $src = Join-Path $instances "$source\minecraft"
        if (-not (Test-Path -LiteralPath (Join-Path $src 'mods') -PathType Container)) {
            Say "[$rig] source instance not found: $source"; $drift = $true; continue
        }

        $w = Get-WantedJars (Join-Path $src 'mods') $excludes
        foreach ($f in $w.Forced) {
            Say "[$rig] keeping '$($f.Id)' despite sync-exclude.txt - it registers synced content ($($f.Regs -join ', ')); without it the bot is dropped on join"
        }
        if ($w.Broken.Count) { Say "[$rig] REFUSING: kept mods depend on excluded ones: $($w.Broken -join '; ')"; $drift = $true; continue }

        $mods = Join-Path $rigDir 'gamedir\mods'
        $null = New-Item -ItemType Directory -Force $mods        # absent on a fresh clone (gitignored)
        $extras = @(Get-BotOnlyJars $patch)
        $labels = ($extras | ForEach-Object { $_.Label }) -join ', '
        $isOurs = { param($j) foreach ($e in $extras) { if ($j.StartsWith($e.Pre)) { return $true } }; $false }
        $have = @(Get-ChildItem -LiteralPath $mods -File | Where-Object { $_.Name.EndsWith('.jar') -and -not (& $isOurs $_.Name) } | ForEach-Object { $_.Name })
        $want = @($w.Keep)
        $add = @($want | Where-Object { $have -notcontains $_ } | Sort-Object)
        $remove = @($have | Where-Object { $want -notcontains $_ } | Sort-Object)
        $stale = @($extras | Where-Object { -not (Test-Current $mods $_.Src) })

        if (-not $add.Count -and -not $remove.Count -and -not $stale.Count) {
            Say "[$rig] in sync with '$source' - $($want.Count) mods + $labels; $($w.Dropped.Count) excluded"
            continue
        }

        $drift = $true
        Say "[$rig] drift vs '${source}':"
        foreach ($j in $add) { Say "   + $j" }
        foreach ($j in $remove) { Say "   - $j" }
        foreach ($e in $stale) { Say "   + $(Split-Path $e.Src -Leaf) ($($e.Label))" }
        if ($check) { continue }

        foreach ($j in $remove) { Remove-Item -LiteralPath (Join-Path $mods $j) -Force }
        foreach ($j in $add) { Copy-Item -LiteralPath (Join-Path $src "mods\$j") -Destination $mods }
        foreach ($e in $stale) {
            Get-ChildItem -LiteralPath $mods -File | Where-Object { $_.Name.StartsWith($e.Pre) } | Remove-Item -Force
            Copy-Item -LiteralPath $e.Src -Destination $mods
        }
        Sync-Dir (Join-Path $src 'config') (Join-Path $rigDir 'gamedir\config')
        Say "[$rig] synced - $($want.Count) mods + $labels; excluded $($w.Dropped.Count): $(($w.Dropped | ForEach-Object { $_.Id }) -join ', ')"
        if (-not ($extras | Where-Object { $_.Pre -eq 'zymbot-' })) {
            Say "[$rig] note: zymbot isn't built yet (mod\: gradlew build) - the bot runs without it"
        }
    }
    if ($check -and $drift) { 1 } else { 0 }
}

# ---------------------------------------------------------------- one bot: stop, and the two console windows

function Cmd-StopBot($bot) {
    # stop-bot1.bat: just this bot - its relay, HeadlessMC launcher and game - never the others, never Prism.
    $c = Get-Console $bot
    $name = Get-BotName $bot
    $ids = @()
    if ($c) { $ids += [int]$c.relay_pid; if ($c.launcher_pid) { $ids += [int]$c.launcher_pid } }
    $g = Get-GamePid $name; if ($g[0]) { $ids += [int]$g[0] }
    if (-not $ids.Count) { Say "[$bot] isn't running"; return 0 }
    $until = (Get-Date).AddSeconds(30)
    do {
        foreach ($id in $ids) { try { Stop-Process -Id $id -Force -ErrorAction Stop } catch {} }
        Start-Sleep -Milliseconds 500
        $left = @($ids | Where-Object { Get-Process -Id $_ -ErrorAction SilentlyContinue })
    } while ($left.Count -and (Get-Date) -lt $until)
    Remove-Item -LiteralPath (Join-Path $HERE "$bot\console.json") -Force -ErrorAction SilentlyContinue
    if ($left.Count) { Say "[$bot] NOT stopped after 30 s - still running: $($left -join ', ')"; return 1 }
    Say "[$bot] stopped ($($ids.Count) process$(if ($ids.Count -ne 1) { 'es' })) - nothing of it left running"
    0
}

# Which console windows are open for a bot: <rig>\windows.json = { log: <pid of window 2>, bot: <pid of window 1> }.
function Get-Windows($bot) {
    try { [IO.File]::ReadAllText((Join-Path $HERE "$bot\windows.json")) | ConvertFrom-Json } catch { $null }
}
function Set-Window($bot, $which, [int]$id) {
    for ($i = 0; $i -lt 10; $i++) {                              # both panes start together: retry a busy file
        try {
            $w = Get-Windows $bot; $h = @{ log = 0; bot = 0 }
            if ($w) { $h.log = [int]$w.log; $h.bot = [int]$w.bot }
            $h[$which] = $id
            [IO.File]::WriteAllText((Join-Path $HERE "$bot\windows.json"), (ConvertTo-Json $h)); return
        } catch { Start-Sleep -Milliseconds (50 + (Get-Random -Maximum 150)) }
    }
}
function Test-Alive([int]$id) { [bool]($id -and (Get-Process -Id $id -ErrorAction SilentlyContinue)) }

function Get-Noise {
    # log-noise.txt: one regex per line (# comments), hidden from window 2 - edit it freely.
    $f = Join-Path $HERE 'log-noise.txt'
    if (-not (Test-Path -LiteralPath $f)) { return @() }
    @(Get-Content -LiteralPath $f -Encoding UTF8 | Where-Object { $_.Trim() -and -not $_.Trim().StartsWith('#') })
}

function Open-BotWindow($bot) {
    # Window 1 again: a pane under this one in Windows Terminal, else its own console window.
    $cmd = '-NoProfile -ExecutionPolicy Bypass -File "' + $SELF + '" console "' + $bot + '"'
    if ($env:WT_SESSION -and (Get-Wt)) {
        Start-Process -FilePath (Get-Wt) -ArgumentList ('-w 0 split-pane -H --size 0.4 powershell.exe ' + $cmd)
    } else {
        $p = Start-Process -FilePath 'powershell.exe' -PassThru -ArgumentList $cmd
        Set-Window $bot 'bot' $p.Id
    }
}

function Get-Wt {
    # Windows Terminal, unless ZBOT_CONSOLE=windows asks for the old separate windows.
    if ($env:ZBOT_CONSOLE -eq 'windows') { return $null }
    $c = Get-Command wt.exe -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    $null
}

function Cmd-Consoles([string[]]$argv) {
    # The console for each bot (owner, 2026-09-28): one Windows Terminal window "zymbot", a tab per bot,
    # each split - the log on top, decisions + a prompt below. No Windows Terminal (or ZBOT_CONSOLE=windows):
    # the old separate windows, this one becoming the log window of the first bot.
    # Returns 10 when Windows Terminal took over, so the .bat can close without "Press any key".
    $bots = @($argv | Where-Object { $_ -match '^bot\d+$' })
    if (-not $bots.Count) { $bots = @('bot1') }
    $wt = Get-Wt
    if (-not $wt) {
        foreach ($b in ($bots | Select-Object -Skip 1)) {
            Start-Process -FilePath 'powershell.exe' -ArgumentList ('-NoProfile -ExecutionPolicy Bypass -File "' + $SELF + '" console-log "' + $b + '"')
        }
        Cmd-ConsoleLog $bots[0]
        return 0
    }
    $parts = @()
    foreach ($b in $bots) {
        $name = Get-BotName $b
        $run = 'powershell.exe -NoProfile -ExecutionPolicy Bypass -File "' + $SELF + '" '
        $parts += ('new-tab --title "' + $name + '" ' + $run + 'console-log "' + $b + '" --pane')
        $parts += ('split-pane -H --size 0.4 --title "' + $name + ' - bot" ' + $run + 'console "' + $b + '"')
    }
    # minimised: it opened on top of the game, taking focus from Minecraft mid-play (owner, 2026-09-29). It waits
    # on the taskbar; click it when wanted.
    Start-Process -FilePath $wt -ArgumentList ('-w zymbot ' + ($parts -join ' ; ')) -WindowStyle Minimized
    Say "opened the Zymbot console in Windows Terminal, minimised on the taskbar: a tab per bot ($($bots -join ', ')), log on top, commands below"
    10
}

function Start-Guard($bot) {
    # A hidden watcher of its own (it survives a tab closing). It stops the bot once BOTH its panes (log and
    # commands) are gone. Closing one by accident leaves a way back: R in the log pane, F2 in the command pane
    # (owner, 2026-09-29; it used to stop the bot as soon as the log closed). One guard per bot.
    $g = Join-Path $HERE "$bot\guard.pid"
    try { if (Test-Alive ([int](Read-Trimmed $g))) { return } } catch {}
    $p = Start-Process -FilePath 'powershell.exe' -WindowStyle Hidden -PassThru -ArgumentList (
        '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + $SELF + '" _guard "' + $bot + '"')
    [IO.File]::WriteAllText($g, [string]$p.Id)
}

function Invoke-Guard($bot) {
    $gone = 0
    while (Get-Console $bot) {
        $w = Get-Windows $bot
        $open = $w -and ((Test-Alive ([int]$w.log)) -or (Test-Alive ([int]$w.bot)))
        # 5 s with neither pane: a pane being reopened (R / F2) is back well within that
        if ($open) { $gone = 0 } elseif (++$gone -ge 5) { Cmd-StopBot $bot | Out-Null; break }
        Start-Sleep -Seconds 1
    }
    Remove-Item -LiteralPath (Join-Path $HERE "$bot\guard.pid") -Force -ErrorAction SilentlyContinue
}

function Open-LogWindow($bot) {
    # The log pane again, from the command pane (F2): a pane in Windows Terminal, else its own window.
    $cmd = '-NoProfile -ExecutionPolicy Bypass -File "' + $SELF + '" console-log "' + $bot + '" --pane'
    if ($env:WT_SESSION -and (Get-Wt)) {
        # a split opens below the pane it splits: swap it up so the log stays on top (owner, 2026-09-29)
        Start-Process -FilePath (Get-Wt) -ArgumentList ('-w 0 split-pane -H --size 0.6 powershell.exe ' + $cmd + ' ; swap-pane up')
    } else {
        Start-Process -FilePath 'powershell.exe' -ArgumentList $cmd
    }
}

function Watch-Lines($bot, [scriptblock]$show, [scriptblock]$everyTick, [scriptblock]$wanted = $null, [int]$backlog = 0, [scriptblock]$afterBacklog = $null) {
    # Follow the bot's log from its current end; $show gets each new whole line; $everyTick runs every 30 ms
    # (keys) and returns $false to stop; the log is read every ~300 ms. At 300 ms per loop, typing showed
    # up only every few characters (owner, 2026-09-28). $backlog: first show the last N lines $wanted
    # accepts, so a reopened pane isn't empty.
    $c = Get-Console $bot
    if (-not $c) { Say "[$bot] isn't running - start it: standby-bot1-singleplayer.bat"; return }
    $log = $c.log; $pos = Get-LogSize $log; $carry = ''
    if ($backlog -gt 0 -and $wanted) {
        $old = Read-LogFrom $log 0
        if ($old) {
            $keep = @(($old.Substring(0, [Math]::Min($old.Length, $old.LastIndexOf("`n") + 1)) -split "\r?\n") |
                      Where-Object { $_ -and (& $wanted $_) } | Select-Object -Last $backlog)
            foreach ($l in $keep) { & $show $l }
            if ($keep.Count) { Write-Host "-- (the last $($keep.Count) lines before this pane opened; live from here) --" -ForegroundColor DarkGray }
        }
    }
    if ($afterBacklog) { & $afterBacklog }                      # e.g. the prompt, under the backlog
    $tick = 0
    while ($true) {
        if (-not (& $everyTick)) { return }
        if ((++$tick % 10) -ne 0) { Start-Sleep -Milliseconds 30; continue }
        $size = Get-LogSize $log
        if ($size -gt $pos) {
            $text = $carry + (Read-LogFrom $log $pos); $pos = $size
            $cut = $text.LastIndexOf("`n")
            if ($cut -ge 0) {
                foreach ($l in ($text.Substring(0, $cut) -split "\r?\n")) { if ($l) { & $show $l } }
                $carry = $text.Substring($cut + 1)
            } else { $carry = $text }
        }
        Start-Sleep -Milliseconds 30
    }
}

function Wait-BotConsole($bot, $name) {
    # play.bat starts the bot in the background and opens the tab at once: wait (90 s) for its console to
    # exist. Only the log pane waited, so the command pane found no bot, said so and closed (owner, 2026-09-28).
    $until = (Get-Date).AddSeconds(90)
    if (-not (Get-Console $bot)) { Say "[$name] waiting for $name to start..." }
    while (-not (Get-Console $bot)) {
        if ((Get-Date) -gt $until) { Say "[$name] didn't start within 90 s - check $bot\run-*.log"; Start-Sleep 5; return $false }
        Start-Sleep -Seconds 1
    }
    $true
}

function Cmd-ConsoleLog($bot, [switch]$pane) {
    # The log (window 2 / top pane): everything the bot logs, minus log-noise.txt, coloured: ERROR red, WARN
    # yellow, network cyan. With the command pane gone as well (or the whole tab), the guard stops the bot.
    $name = Get-BotName $bot
    if (-not (Wait-BotConsole $bot $name)) { return }
    $Host.UI.RawUI.WindowTitle = "$name - log (close both panes = stop $name)"
    Set-Window $bot 'log' $PID
    $w = Get-Windows $bot                                        # a stale one from last time isn't "closed";
    if ($w -and -not (Test-Alive ([int]$w.bot))) { Set-Window $bot 'bot' 0 }   # a pane that just started stays
    Start-Guard $bot
    if (-not $pane) { Open-BotWindow $bot }                     # in a tab, the split below is the bot pane
    Say "[$name] the full log. Below / window 1: decisions, chat, and a prompt for commands."
    Say "        Close both panes (or the tab) to stop $name. Closed one by accident? R here reopens the command pane, F2 there reopens this one."
    if ($env:WT_SESSION) { Say "        Windows Terminal: Ctrl+Shift+W closes a pane, Alt+Shift+Up/Down moves the divider." }
    $noise = Get-Noise
    $script:warned = $false
    $script:seenBot = $false
    $script:n = 0
    Watch-Lines $bot {
        param($l)
        foreach ($n in $noise) { if ($l -match $n) { return } }
        $color = if ($l -match '/ERROR\]|Exception|Game crashed') { 'Red' }
                 elseif ($l -match '/WARN\]') { 'Yellow' }
                 elseif ($l -cmatch 'Connecting to|[Dd]isconnect|joined the game|left the game|\bLAN\b|local bus|[Ss]ummon|Timed out|[Cc]onnection') { 'Cyan' }
                 else { 'Gray' }
        Write-Host $l -ForegroundColor $color
    } {
        while ([Console]::KeyAvailable) {                        # R: reopen the command pane / window 1
            $k = [Console]::ReadKey($true)
            if ($k.Key -eq 'R') {
                $w = Get-Windows $bot
                if ($w -and (Test-Alive ([int]$w.bot))) { Write-Host "== the command pane is already open" -ForegroundColor Magenta }
                else { Open-BotWindow $bot; Write-Host "== reopened the command pane ($name - bot)" -ForegroundColor Magenta }
            }
        }
        if ((++$script:n % 10) -ne 0) { return $true }         # keys every 30 ms; the rest every ~300 ms
        $w = Get-Windows $bot
        $botAlive = $w -and [int]$w.bot -and (Test-Alive ([int]$w.bot))
        if ($botAlive) { $script:seenBot = $true; $script:warned = $false }
        elseif ($script:seenBot -and -not $script:warned) {      # only once it had been open
            Write-Host "== the command pane ($name - bot) was closed. Press R here to reopen it (or double-click console.bat)" -ForegroundColor Magenta
            $script:warned = $true
        }
        if (($script:n % 100) -eq 0 -and -not (Get-Console $bot)) {   # ~3 s: Get-Console scans processes
            Write-Host "== $name stopped." -ForegroundColor Magenta; return $false }
        $true
    }
}

# Tab in the command pane (owner, 2026-09-30): the /zbot words, from ZymbotCommands.java - keep in step with
# it (the SKILL.md command table). Player-name slots offer the bots' names.
$ZBOT_WORDS = @{
    ''          = @('help', 'status', 'start', 'stop', 'role', 'goto', 'follow', 'come', 'watch', 'look', 'eat', 'debug',
                    'see', 'view', 'spy', 'grave', 'cancel', 'set', 'roster', 'regroup', 'danger', 'summon', 'lan', 'autostart')
    'role'      = @('bot', 'teammate', 'none')
    'debug'     = @('terrain', 'block', 'punch', 'foods', 'survey')
    'grave'     = @('loot')
    'set'       = @('leash', 'eat', 'critical', 'downed', 'regroup', 'probewait', 'plantime', 'lagtps')
    'danger'    = @('modpack', 'easy', 'normal', 'hard')
    'summon'    = @('auto')
    'summon auto' = @('on', 'off')
    'lan'       = @('auto')
    'lan auto'  = @('on', 'off')
    'autostart' = @('add', 'remove', 'list')
}
$ZBOT_NAMED = @('follow', 'come', 'watch', 'look', 'see', 'view', 'spy', 'grave loot')

function Get-Completions([string]$typed) {
    # Every full line that completes the last word of $typed; empty when it isn't a /zbot command.
    if ($typed -eq '/zbot') { $typed = '/zbot ' }                 # it gave "/zbotautostart" (owner, 2026-09-30)
    if ($typed -notmatch '^/zbot( |$)') { return @(if ('/zbot'.StartsWith($typed) -and $typed.StartsWith('/')) { '/zbot' }) }
    $words = @(($typed.Substring(5).TrimStart()) -split ' ')
    $last = $words[-1]
    $path = (@($words | Select-Object -SkipLast 1) -join ' ').Trim()
    $opts = @($ZBOT_WORDS[$path])
    if ($path -in $ZBOT_NAMED -or ($path -like 'grave loot *')) { $opts += @(Get-Rigs | ForEach-Object { Get-BotName $_ }) }
    $head = $typed.Substring(0, $typed.Length - $last.Length)
    @($opts | Where-Object { $_ -and $_.StartsWith($last, [StringComparison]::OrdinalIgnoreCase) } | Sort-Object -Unique | ForEach-Object { $head + $_ })   # no space after it (owner, 2026-09-30)
}

function Cmd-Console($bot) {
    # Decisions pane / window 1: the bot's decisions, chat and whispers, Zymbot's own lines - short and
    # coloured - and a prompt: a typed line goes to the bot like send.bat (/zbot status, msg hi, ...).
    # The line being typed stays pinned under the output: an incoming line clears it, prints, and puts it
    # back - it used to land in the middle of what you were typing (owner, 2026-09-28).
    $name = Get-BotName $bot
    $Host.UI.RawUI.WindowTitle = "$name - bot (type a command, Enter)"
    Set-Window $bot 'bot' $PID
    if (-not (Wait-BotConsole $bot $name)) { return }
    Start-Guard $bot
    Say "[$name] decisions, chat and whispers. Type a command and press Enter, e.g. /zbot status  (Tab completes; Up/Down: earlier commands; Esc clears it; F2 reopens the log pane)"
    if ($env:WT_SESSION) { Say "        Windows Terminal: Ctrl+Shift+W closes this pane, Alt+Shift+Up/Down moves the divider (dragging the divider dosn't work at all in Powershell)." }
    $script:typed = ''
    # Up/Down: earlier commands, like Minecraft's chat and any terminal (owner, 2026-09-30); kept in
    # <bot>\console-history.txt so a reopened pane still has them
    $histFile = Join-Path $HERE "$bot\console-history.txt"
    $script:hist = @(try { Get-Content -LiteralPath $histFile -ErrorAction Stop } catch { })
    $script:hi = $script:hist.Count
    $script:n = 0
    $script:live = $false                                       # the prompt shows once the backlog is out
    $script:logSeen = $false
    $script:logWarned = $false
    $prompt = '> '
    $clear = { [Console]::Write("`r" + (' ' * [Math]::Max(1, [Console]::BufferWidth - 1)) + "`r") }
    $redraw = { [Console]::Write($prompt + $script:typed) }
    $wanted = { param($l) $l -match '\[decision\]|\[CHAT\]|\[zymbot\]|IN THE WORLD' }
    Watch-Lines $bot {
        param($l)
        if (-not (& $wanted $l)) { return }
        $t = if ($l -match '^\[(\d\d:\d\d:\d\d)\]') { $Matches[1] } else { '' }
        $msg = (($l -replace '^\[[^\]]*\] \[[^\]]*\]: ', '') -replace '\[decision\] ', '') -replace '\x1b\[[0-9;]*m|\[m|\u00A7.', ''
        $color = if ($msg -match '^failed:|CRASH|knocked out|giving up|refused') { 'Red' }
                 elseif ($msg -match '^paused|retreat|waiting|fleeing|resumes') { 'Yellow' }
                 elseif ($msg -match '^done:|^regrouped|revived|IN THE WORLD|picked up|looted') { 'Green' }
                 elseif ($l -match '\[CHAT\]') { 'Cyan' }
                 else { 'White' }
        & $clear
        Write-Host "$t $msg" -ForegroundColor $color
        if ($script:live) { & $redraw }
    } {
        while ([Console]::KeyAvailable) {
            $k = [Console]::ReadKey($true)
            if ($k.Key -ne 'Tab') { $script:tab = $null }
            if ($k.Key -eq 'Tab') {                             # Tab: complete; again: the next match
                if (-not $script:tab) { $script:tab = @{ list = @(Get-Completions $script:typed); i = -1 } }
                if ($script:tab.list.Count) {
                    $script:tab.i = ($script:tab.i + 1) % $script:tab.list.Count
                    $script:typed = $script:tab.list[$script:tab.i]
                    & $clear; & $redraw
                }
            } elseif ($k.Key -eq 'Enter') {
                $line = $script:typed.Trim(); $script:typed = ''
                & $clear
                if ($line) {
                    if (-not $script:hist.Count -or $script:hist[-1] -ne $line) {
                        $script:hist = @($script:hist + $line | Select-Object -Last 100)
                        try { [IO.File]::WriteAllLines($histFile, [string[]]$script:hist) } catch {}
                    }
                    Write-Host "$prompt$line" -ForegroundColor DarkGray
                    try { Send-Line $bot $line } catch { Write-Host "  (not sent: $_)" -ForegroundColor Red }
                }
                $script:hi = $script:hist.Count
                & $redraw
            } elseif ($k.Key -eq 'UpArrow' -or $k.Key -eq 'DownArrow') {
                if (-not $script:hist.Count) { continue }
                $script:hi = [Math]::Max(0, [Math]::Min($script:hist.Count, $script:hi + $(if ($k.Key -eq 'UpArrow') { -1 } else { 1 })))
                $script:typed = if ($script:hi -lt $script:hist.Count) { $script:hist[$script:hi] } else { '' }   # below the newest: empty
                & $clear; & $redraw
            } elseif ($k.Key -eq 'Backspace') {
                if ($script:typed.Length) { $script:typed = $script:typed.Substring(0, $script:typed.Length - 1); [Console]::Write("`b `b") }
            } elseif ($k.Key -eq 'F2') {                        # F2: reopen the log pane
                $w = Get-Windows $bot
                & $clear
                if ($w -and (Test-Alive ([int]$w.log))) { Write-Host "== the log pane is already open" -ForegroundColor Magenta }
                else { Open-LogWindow $bot; Write-Host "== reopened the log pane" -ForegroundColor Magenta }
                & $redraw
            } elseif ($k.Key -eq 'Escape') {
                $script:typed = ''; $script:hi = $script:hist.Count; & $clear; & $redraw
            } elseif ($k.KeyChar -ge ' ') { $script:typed += $k.KeyChar; [Console]::Write($k.KeyChar) }
        }
        if ((++$script:n % 10) -ne 0) { return $true }         # keys every 30 ms; the rest every ~300 ms
        $w = Get-Windows $bot
        $logAlive = $w -and [int]$w.log -and (Test-Alive ([int]$w.log))
        if ($logAlive) { $script:logSeen = $true; $script:logWarned = $false }
        elseif ($script:logSeen -and -not $script:logWarned) {
            & $clear
            Write-Host "== the log pane was closed. Press F2 here to reopen it; close this pane too to stop $name" -ForegroundColor Magenta
            & $redraw
            $script:logWarned = $true
        }
        if (($script:n % 100) -eq 0 -and -not (Get-Console $bot)) {   # ~3 s: Get-Console scans processes
            & $clear; Write-Host "== $name stopped." -ForegroundColor Magenta; Start-Sleep 2; return $false }
        $true
    } $wanted 40 { $script:live = $true; & $redraw }
}

# ---------------------------------------------------------------- main

function Invoke-Main([string[]]$a) {
    if (-not $a -or $a.Count -eq 0 -or @('-h', '--help', 'help') -contains $a[0]) {
        Get-Content -LiteralPath $SELF -TotalCount 20 | ForEach-Object { Say ($_ -replace '^# ?', '') }
        return 0
    }
    $cmd = $a[0]; $rest = @($a | Select-Object -Skip 1)
    # Say what's starting before any slow step (process scans take a few seconds): sync-bots.bat sat
    # on a blank window with no hint it was working (owner, 2026-09-28).
    $intro = @{
        'setup'      = 'setting up: finding Java 21 + Prism, writing the rig configs...'
        'sync'       = 'syncing mods + config from the Prism instance into the headless (HeadlessMC) rigs...'
        'run'        = "starting $($rest[0]) and joining a world..."
        'standby'    = $(if ($env:ZBOT_THEN_JOIN) { "starting $($rest[0]) to its title screen (standby), then it waits for your Prism world to open to LAN (127.0.0.1:25565) and joins it..." }
                         else { "starting $($rest[0]) to its title screen (standby)..." })
        'connect'    = "connecting $($rest[0]) to $($rest[1])..."
        'disconnect' = "disconnecting $($rest[0])..."
        'gui'        = "reading $($rest[0])'s screen..."
        'send'       = "sending to $($rest[0])..."
        'play'       = "starting your game into '$($rest[0])', then the bots..."
        'prism'      = "starting your game into '$($rest[0])' (singleplayer, no bots)..."
        'stop'       = $(if ($rest -contains '--check') { 'checking for headless bots (stopping nothing)...' } else { 'stopping the headless bots (never your Prism game)...' })
    }
    if ($intro.ContainsKey($cmd) -and ($rest.Count -or $cmd -in 'setup', 'sync', 'stop')) { Say $intro[$cmd] }
    switch ($cmd) {
        'setup'      { return Cmd-Setup }
        'sync'       { return Cmd-Sync $rest }
        'run'        { if (-not $rest.Count) { Die 'usage: run-bot.bat <bot1|bot3> [heap] [host:port]' }
                       return Cmd-Run $rest[0] $rest[1] $rest[2] }
        'standby'    { if (-not $rest.Count) { Die 'usage: standby.bat <bot> [heap]' }
                       return Cmd-Run $rest[0] $rest[1] $null }
        'connect'    { if ($rest.Count -lt 2) { Die 'usage: connect-with-arg.bat <bot> <host:port> [--wait]  (connect-bot1.bat = bot1 to 127.0.0.1:25565, waiting)' }
                       return Cmd-Connect $rest[0] $rest[1] ($rest -contains '--wait') }
        'disconnect' { if (-not $rest.Count) { Die 'usage: disconnect-with-arg.bat <bot>  (disconnect-bot1.bat = bot1)' }; return Cmd-Send $rest[0] @('disconnect') }
        'gui'        { if (-not $rest.Count) { Die 'usage: gui.bat <bot>' }; return Cmd-Send $rest[0] @('gui') }
        'send'       { if ($rest.Count -lt 2) { Die 'usage: send.bat <bot> <console command...>' }
                       return Cmd-Send $rest[0] @($rest | Select-Object -Skip 1) }
        'team-key'   { return Cmd-TeamKey }
        '_relay'     { Invoke-Relay $rest[0] $rest[1] $rest[2] $rest[3]; return 0 }
        'play'       { if (-not $rest.Count) { Die 'usage: play-with-arg.bat "<world name>" [bot1 bot3 ...]  (play.bat = New World + bot1)' }
                       return Cmd-Play $rest[0] @($rest | Select-Object -Skip 1) }
        'prism'      { if (-not $rest.Count) { Die 'usage: start-singleplayer-server-no-arg.bat "<world name>"  (your game only, no bots; start-singleplayer-server.bat = New World)' }
                       return Cmd-Play $rest[0] @() -gameOnly }
        'stop'       { return Cmd-Stop -check:($rest -contains '--check') }
        'stop-bot'   { if (-not $rest.Count) { Die 'usage: stop-bot1.bat  (or: bots.ps1 stop-bot <bot>)' }; return Cmd-StopBot $rest[0] }
        'console'    { $b = if ($rest.Count) { $rest[0] } else {
                           # double-clicked console.bat has no name to pass: take the running bot (owner, 2026-09-28)
                           $run = @(Get-Rigs | Where-Object { Get-Console $_ })
                           if ($run.Count -eq 1) { $run[0] } else { 'bot1' } }
                       Cmd-Console $b; return 0 }
        'console-log'{ if (-not $rest.Count) { Die 'usage: bots.ps1 console-log <bot> [--pane]' }
                       Cmd-ConsoleLog $rest[0] -pane:($rest -contains '--pane'); return 0 }
        'consoles'   { return Cmd-Consoles $rest }
        '_standby-join' { $r = Cmd-Run $rest[0] $null $null; if ($r -eq 0) { $r = Cmd-Connect $rest[0] '127.0.0.1:25565' $true }; return $r }
        '_guard'     { Invoke-Guard $rest[0]; return 0 }
    }
    Die "unknown command '$cmd' - try: setup, sync, run, stop"
}

exit ([int](Invoke-Main $args))
