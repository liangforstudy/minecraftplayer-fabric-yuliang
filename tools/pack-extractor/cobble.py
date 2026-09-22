import json, glob, os, re, collections
C='x/Cobblemon-fabric-1.7.3+1.21.1/data/cobblemon'
EXTRA=glob.glob('x/missingmons-cobblemon-3.6.1/data/*/species/**/*.json',recursive=True)

# ---- food item universe -------------------------------------------------
food=set()
van=json.load(open('food_vanilla.json'))
for k in van: food.add('minecraft:'+k)
raw=json.load(open('food_raw.json'))
NS={'farmersdelight':'farmersdelight','cobblemon':'cobblemon','bakery':'bakery','brewery':'brewery',
    'candlelight':'candlelight','farm_and_charm':'farm_and_charm','herbalbrews':'herbalbrews',
    'vinery':'vinery','ubesdelight':'ubesdelight','zymlabs':'zymlabs','civfabric':'civfabric',
    'supplementaries':'supplementaries'}
for mod,items in raw.items():
    for k in items: food.add(f'{NS.get(mod,mod)}:{k}')
cfg=json.load(open('food_config.json'))
for mod,items in cfg.items():
    for k in items: food.add(f'{NS.get(mod,mod)}:{k}')
# vanilla extras that are food-adjacent / cookable
food |= {'minecraft:egg','minecraft:milk_bucket','minecraft:wheat','minecraft:sugar_cane',
         'minecraft:brown_mushroom','minecraft:red_mushroom','minecraft:kelp','minecraft:sugar',
         'minecraft:honeycomb','minecraft:cocoa_beans','minecraft:sweet_berries','minecraft:melon_slice'}
COOKABLE={'minecraft:beef','minecraft:porkchop','minecraft:chicken','minecraft:mutton',
          'minecraft:rabbit','minecraft:cod','minecraft:salmon','minecraft:potato','minecraft:kelp'}

# ---- species drops ------------------------------------------------------
def rng(s):
    if s is None: return None
    m=re.match(r'^(\d+)-(\d+)$',str(s))
    return (int(m.group(1)),int(m.group(2))) if m else (int(s),int(s))

drops={}
for p in glob.glob(f'{C}/species/**/*.json',recursive=True)+EXTRA:
    d=json.load(open(p))
    nm=(d.get('name') or '').lower()
    if not nm: continue
    dr=d.get('drops') or {}
    ent=[]
    for e in dr.get('entries',[]) or []:
        it=e.get('item')
        if not it: continue
        if ':' not in it: it='minecraft:'+it
        ent.append({'item':it,'qty':e.get('quantityRange','1'),'pct':e.get('percentage',100.0)})
    if ent:
        bs=d.get('baseStats') or {}
        bh=d.get('behaviour') or {}
        mv=(bh.get('moving') or {})
        drops[nm]={'amount':dr.get('amount',1),'entries':ent,
                   'hp':bs.get('hp'),'atk':bs.get('attack'),'spd':bs.get('speed'),
                   'catchRate':d.get('catchRate'),
                   'canFly':bool((mv.get('fly') or {}).get('canFly')),
                   'avoidsWater':bool((mv.get('swim') or {}).get('avoidsWater')),
                   'canSwim':bool((mv.get('swim') or {}).get('canSwim')),
                   'labels':d.get('labels') or [],
                   'type':d.get('primaryType')}

# ---- spawns -------------------------------------------------------------
spawns=collections.defaultdict(list)
for p in glob.glob(f'{C}/spawn_pool_world/*.json'):
    d=json.load(open(p))
    if not d.get('enabled',True): continue
    if d.get('neededInstalledMods'): continue
    for s in d.get('spawns',[]) or []:
        mon=(s.get('pokemon') or '').split()[0].lower()
        cond=s.get('condition',{}) or {}
        biomes=cond.get('biomes') or []
        if any(not b.startswith('#cobblemon') and ':' in b for b in biomes) and not any(b.startswith('#') for b in biomes):
            continue  # other-mod biomes only
        spawns[mon].append({'bucket':s.get('bucket'),'level':s.get('level'),
                            'weight':s.get('weight'),'biomes':biomes,
                            'time':cond.get('timeRange'),'presets':s.get('presets') or [],
                            'ctx':s.get('spawnablePositionType')})

# ---- join: food-dropping, low-level, common ----------------------------
rows=[]
for mon,d in drops.items():
    fitems=[e for e in d['entries'] if e['item'] in food or e['item'] in COOKABLE]
    if not fitems: continue
    sp=spawns.get(mon,[])
    if not sp: continue
    lo=min((rng(s['level'])[0] for s in sp if rng(s['level'])), default=None)
    buckets=sorted({s['bucket'] for s in sp if s['bucket']})
    bset=set()
    for s in sp: bset.update(s['biomes'])
    times=sorted({s['time'] for s in sp if s['time']})
    rows.append({'pokemon':mon,'min_level':lo,'buckets':buckets,
                 'hp':d.get('hp'),'atk':d.get('atk'),'speed':d.get('spd'),
                 'type':d.get('type'),'catchRate':d.get('catchRate'),
                 'canFly':d.get('canFly'),'canSwim':d.get('canSwim'),
                 'labels':d.get('labels'),
                 'biomes':sorted(bset),'time':times,
                 'drop_amount':d['amount'],
                 'food_drops':[f"{e['item']} x{e['qty']}"+('' if e['pct']==100.0 else f" @{e['pct']}%") for e in fitems],
                 'all_drops':[f"{e['item']} x{e['qty']}"+('' if e['pct']==100.0 else f" @{e['pct']}%") for e in d['entries']]})
rows.sort(key=lambda r:(r['min_level'] or 99, r['pokemon']))
json.dump(rows,open('cobblemon_food_mobs.json','w'),indent=1)
print('species with food drops AND spawns:',len(rows))
print('total species with drops:',len(drops),'| species with spawns:',len(spawns))
GOOD={'legendary','mythical','ultra_beast','paradox'}
common=[r for r in rows if 'common' in r['buckets'] and (r['min_level'] or 99)<=10
        and not (set(r['labels'] or []) & GOOD)]
common.sort(key=lambda r:(r['hp'] or 99, r['pokemon']))
print('\n--- common, lvl<=10, food-dropping, sorted by HP (easiest first) ---',len(common))
print(f"{'pokemon':14s} {'L':>3s} {'HP':>3s} {'ATK':>3s} fly  drops")
for r in common:
    print(f"{r['pokemon']:14s} {r['min_level']:>3d} {r['hp']:>3d} {r['atk']:>3d} {'Y' if r['canFly'] else '-':^4s} {'; '.join(r['food_drops'])[:64]}")
