"""Call the MCPFabric bridge: python rpc.py [-i <instance>] [--full] <method> '<json params>'. Never prints the token.

-i names a Prism instance folder (e.g. "26.2", "1.21.11 9b9t"); its config gives the port and token.
Without it, the Stage 0 instance is used, as before.
"""
import json, os, sys, urllib.request

PRISM = r'D:/PrismLauncher/instances'
args = sys.argv[1:]
instance = '1.21.11 Stage 0  temp testing'
full = False
while args[:1] in (['-i'], ['--full']):
    if args[0] == '-i':
        instance, args = args[1], args[2:]
    else:                                    # --full: the whole reply, for piping into a script
        full, args = True, args[1:]
# newer Prism instances keep the game in minecraft/, older ones in .minecraft/
for game in ('minecraft', '.minecraft'):
    path = os.path.join(PRISM, instance, game, 'config', 'mcpfabric.config.json')
    if os.path.exists(path):
        break
else:
    sys.exit(f"no mcpfabric.config.json in instance '{instance}'")
cfg = json.load(open(path, encoding='utf-8'))
method = args[0]
params = json.loads(args[1]) if len(args) > 1 else {}
req = urllib.request.Request(f"http://127.0.0.1:{cfg.get('port', 25599)}/rpc",
                             data=json.dumps({"method": method, "params": params}).encode(),
                             headers={"content-type": "application/json", "authorization": "Bearer " + cfg["token"]})
try:
    body = urllib.request.urlopen(req, timeout=60).read().decode()
except urllib.error.HTTPError as e:
    body = e.read().decode()
out = json.loads(body)
if method == "vision.screenshot" and out.get("ok"):
    import base64
    r = out["result"]
    data = r.get("base64") or r.get("data") or r.get("image")
    path = args[2] if len(args) > 2 else "shot.png"
    if data:
        open(path, "wb").write(base64.b64decode(data))
        print("saved", path)
    else:
        print({k: (v if len(str(v)) < 200 else "...") for k, v in r.items()})
else:
    text = json.dumps(out, ensure_ascii=False)
    print(text if full else text[:6000])
