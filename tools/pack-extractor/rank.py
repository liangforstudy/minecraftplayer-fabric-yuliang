import json,re
P='/Users/tan/Documents/Vibecodes/minecraftplayer-fabric-yuliang/survival-data'
foods=json.load(open(f'{P}/food_values.json'))
forage=json.load(open(f'{P}/wild_forage.json'))

def fv(i):
    f=foods.get(i)
    return (f.get('nutrition'),f.get('saturation')) if f else (None,None)

COOK={'minecraft:beef':'minecraft:cooked_beef','minecraft:porkchop':'minecraft:cooked_porkchop',
 'minecraft:chicken':'minecraft:cooked_chicken','minecraft:mutton':'minecraft:cooked_mutton',
 'minecraft:rabbit':'minecraft:cooked_rabbit','minecraft:cod':'minecraft:cooked_cod',
 'minecraft:salmon':'minecraft:cooked_salmon','minecraft:potato':'minecraft:baked_potato',
 'minecraft:kelp':'minecraft:dried_kelp'}

rows=[]
for e in forage:
    r=e['rarity_1_in_chunks']
    for block,drops in e['harvest'].items():
        for d in drops:
            m=re.match(r'^(\S+) \((.*)\)$',d)
            if not m: continue
            item,note=m.group(1),m.group(2)
            if item==block: continue           # the bush itself (shears)
            n,s=fv(item)
            edible = n is not None
            ck=COOK.get(item)
            cn,cs=fv(ck) if ck else (None,None)
            rows.append({'mod':e['mod'],'feature':e['feature'],'rarity_1_in_chunks':r,
                         'block':block,'yield':item,'chance':note,
                         'nutrition':n,'saturation':s,
                         'cooked_into':ck,'cooked_nutrition':cn,
                         'edible_raw':edible,'is_seed':bool(re.search(r'seed|sapling',item))})
rows.sort(key=lambda x:(-(x['nutrition'] or 0), x['rarity_1_in_chunks'] or 999))
json.dump(rows,open(f'{P}/forage_yield_table.json','w'),indent=1)

print('=== INSTANT FOOD from wild plants (no tool, no cooking) ===')
seen=set()
for x in rows:
    if x['edible_raw'] and '100%' in x['chance'] and not x['is_seed']:
        k=(x['yield'],x['feature'])
        if k in seen: continue
        seen.add(k)
        print(f"  {x['yield']:34s} n={x['nutrition']:<2} sat={x['saturation']:<5} "
              f"{x['chance']:16s} from {x['block'].split(':')[1]:22s} 1/{x['rarity_1_in_chunks']} chunks [{x['mod']}]")
print()
print('=== SEED / FARM STARTERS from wild plants ===')
for x in rows:
    if x['is_seed'] or (not x['edible_raw'] and not x['is_seed']):
        print(f"  {x['yield']:36s} {x['chance']:16s} 1/{x['rarity_1_in_chunks']} chunks [{x['mod']}]")
