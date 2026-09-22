import json, os, glob, re
X='x'
def jload(p):
    try: return json.load(open(p))
    except Exception: return None

def placed_blocks(o, acc):
    """Collect only real placed blocks: dicts carrying a 'Name' key."""
    if isinstance(o,dict):
        n=o.get('Name')
        if isinstance(n,str): acc.add(n)
        for v in o.values(): placed_blocks(v,acc)
    elif isinstance(o,list):
        for i in o: placed_blocks(i,acc)

def loot_for(mod, blockid):
    ns,nm=blockid.split(':',1)
    for pat in (f'{X}/{mod}/data/{ns}/loot_table/blocks/{nm}.json',
                f'{X}/{mod}/data/{ns}/loot_tables/blocks/{nm}.json'):
        d=jload(pat)
        if d: return d
    return None

def drops_summary(lt):
    """Flatten a block loot table into [(item, chance_note)]."""
    out=[]
    if not lt: return out
    for pool in lt.get('pools',[]) or []:
        chance=None
        for c in pool.get('conditions',[]) or []:
            if c.get('condition','').endswith('random_chance'): chance=c.get('chance')
        def walk(e, cond_chance):
            t=e.get('type','')
            if t.endswith('item'):
                note=[]
                if cond_chance: note.append(f'{int(cond_chance*100)}%')
                for c in e.get('conditions',[]) or []:
                    cc=c.get('condition','')
                    if 'can_item_perform_ability' in str(c) or 'shears' in str(c): note.append('shears')
                    if cc.endswith('random_chance'): note.append(f"{int(c.get('chance',1)*100)}%")
                for f in e.get('functions',[]) or []:
                    if f.get('function','').endswith('apply_bonus'): note.append('fortune+')
                    if f.get('function','').endswith('set_count'):
                        cnt=f.get('count')
                        if isinstance(cnt,dict) and 'min' in cnt: note.append(f"x{cnt['min']}-{cnt['max']}")
                out.append((e['name'],' '.join(note) or '100%'))
            for k in ('children','entries'):
                for ch in e.get(k,[]) or []: walk(ch,cond_chance)
        for e in pool.get('entries',[]) or []: walk(e,chance)
    return out

res=[]
for pf in glob.glob(f'{X}/*/data/*/worldgen/placed_feature/*.json'):
    parts=pf.split('/'); mod=parts[1]; ns=parts[3]; name=os.path.basename(pf)[:-5]
    if not re.search(r'wild|berry|bush|grape|mint|herb|coffee|rooibos|yerba|hibiscus|lavender|mushroom_colony|hops|revival',name): continue
    d=jload(pf)
    if not d: continue
    rarity=None; biome_tag=None
    for pl in d.get('placement',[]) or []:
        t=pl.get('type','')
        if t.endswith('rarity_filter'): rarity=pl.get('chance')
        if t.endswith('biome_tag'): biome_tag=pl.get('tag')
    feat=d.get('feature'); acc=set()
    if isinstance(feat,str):
        fns,fn=feat.split(':',1)
        cf=jload(f'{X}/{mod}/data/{fns}/worldgen/configured_feature/{fn}.json')
        if cf: placed_blocks(cf,acc)
    else: placed_blocks(feat,acc)
    blocks=sorted(b for b in acc if b.split(':')[0]!='minecraft' or 'berry' in b or 'mushroom' in b)
    entry={'mod':mod.split('-')[0],'feature':f'{ns}:{name}','rarity_1_in_chunks':rarity,'biome_tag':biome_tag,'harvest':{}}
    for b in blocks:
        dr=drops_summary(loot_for(mod,b))
        if dr: entry['harvest'][b]=[f'{i} ({c})' for i,c in dr]
        else: entry['harvest'][b]=[]
    res.append(entry)
res.sort(key=lambda r:(r['mod'],r['feature']))
json.dump(res,open('forage_features.json','w'),indent=1)
print(len(res),'forage features')
for r in res:
    if r['harvest']:
        print(f"{r['mod']:16s} {r['feature']:45s} 1/{r['rarity_1_in_chunks']}")
        for b,d in r['harvest'].items():
            if d: print('      ',b,'->',', '.join(d))
