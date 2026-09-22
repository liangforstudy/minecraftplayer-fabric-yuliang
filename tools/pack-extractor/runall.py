import os, json, glob, sys, re
import foodex
VSEED=json.load(open('vanilla_consts.json'))
MODID = {
 'FarmersDelight-1.21.1-3.3.6+refabricated':'farmersdelight',
 'Cobblemon-fabric-1.7.3+1.21.1':'cobblemon',
 'letsdo-bakery-fabric-2.1.6':'bakery',
 'letsdo-brewery-fabric-2.1.9':'brewery',
 'letsdo-candlelight-fabric-2.1.12':'candlelight',
 'letsdo-farm_and_charm-fabric-1.1.23':'farm_and_charm',
 'letsdo-herbalbrews-fabric-1.1.3':'herbalbrews',
 'letsdo-vinery-fabric-1.5.3':'vinery',
 'ubesdelight-fabric-1.21.1-0.4.14-3.0.0+refab':'ubesdelight',
 'supplementaries-1.21.1-3.9.7-fabric':'supplementaries',
 'zymlabs-rules-0.0.6':'zymlabs',
 'civfabric-0.4.0':'civfabric',
}
cands = [l.strip().lstrip('./') for l in open('/tmp/cands.txt')]
bymod = {}
for c in cands:
    mod = c.split('/')[0]
    bymod.setdefault(mod, []).append(os.path.join('allcls', c))

out = {}
for mod, files in sorted(bymod.items()):
    mid = MODID.get(mod)
    if not mid:  # architectury/appleskin/spiceoffabric = API shims, no items
        continue
    # include the whole mod's registry classes too, so lambdas resolve
    extra = [p for p in glob.glob(f'allcls/{mod}/**/*.class', recursive=True)
             if re.search(r'(Items|ObjectRegistry|Registry|Food|Foods)\w*\.class$', p)]
    paths = sorted(set(files) | set(extra))
    try:
        C, B = foodex.run(paths, seed=VSEED)
    except Exception as e:
        print('ERR', mod, e, file=sys.stderr); continue
    if B:
        out[mid] = {k: v for k, v in sorted(B.items())}
    print(f'{mid:18s} classes={len(paths):3d} consts={len(C):4d} items={len(B):4d}', file=sys.stderr)
json.dump(out, open('food_raw.json','w'), indent=1)
print('TOTAL items', sum(len(v) for v in out.values()), file=sys.stderr)
