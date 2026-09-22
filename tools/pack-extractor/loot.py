import json, glob, os, re
food=set()
van=json.load(open('food_vanilla.json'))
for k in van: food.add('minecraft:'+k)
NSMAP={'farmersdelight':'farmersdelight','cobblemon':'cobblemon','bakery':'bakery','brewery':'brewery',
 'candlelight':'candlelight','farm_and_charm':'farm_and_charm','herbalbrews':'herbalbrews',
 'vinery':'vinery','ubesdelight':'ubesdelight','zymlabs':'zymlabs','civfabric':'civfabric'}
for mod,items in json.load(open('food_raw.json')).items():
    for k in items: food.add(f'{NSMAP.get(mod,mod)}:{k}')
for mod,items in json.load(open('food_config.json')).items():
    for k in items: food.add(f'{NSMAP.get(mod,mod)}:{k}')
SEEDY=re.compile(r'seed|sapling|wheat|carrot|potato|beetroot|melon|pumpkin|mushroom|egg|sugar_cane|cocoa|apple|berr|bread|cake|cookie|stew|soup|meat|beef|pork|chicken|mutton|rabbit|fish|cod|salmon|kelp|honey|milk|rice|cabbage|onion|tomato|corn|oat|barley|lettuce|strawberr|grape|ham|bacon|pie|sandwich|croissant|baguette|dough|pasta|cheese|jam')

def items_in(lt):
    out=[]
    def walk(e, pool_rolls):
        t=e.get('type','')
        if t.endswith('item'):
            nm=e.get('name','')
            cnt=''
            for f in e.get('functions',[]) or []:
                if f.get('function','').endswith('set_count'):
                    c=f.get('count')
                    if isinstance(c,dict):
                        mn,mx=c.get('min'),c.get('max')
                        if mn is not None: cnt=f'x{mn:g}-{mx:g}'
                    elif isinstance(c,(int,float)): cnt=f'x{c:g}'
            out.append((nm,e.get('weight',1),cnt))
        for k in ('children','entries'):
            for ch in e.get(k,[]) or []: walk(ch,pool_rolls)
    for pool in lt.get('pools',[]) or []:
        for e in pool.get('entries',[]) or []: walk(e,pool.get('rolls'))
    return out

rows=[]
paths=glob.glob('vanilla/data/minecraft/loot_table/chests/**/*.json',recursive=True)
paths+=glob.glob('x/*/data/*/loot_table*/**/*.json',recursive=True)
for p in paths:
    if '/chest' not in p and '/gameplay' not in p: continue
    try: lt=json.load(open(p))
    except Exception: continue
    its=items_in(lt)
    f=[(n,w,c) for n,w,c in its if n in food or SEEDY.search(n or '')]
    if not f: continue
    parts=p.split('/')
    mod=parts[1] if p.startswith('x/') else 'minecraft'
    label=p.split('loot_table',1)[-1].split('loot_tables',1)[-1].lstrip('s/').replace('.json','')
    tot=sum(w for _,w,_ in its) or 1
    rows.append({'source':mod.split('-')[0],'table':label,
                 'food':[f'{n} {c} (w{w}, {100*w/tot:.0f}%)' for n,w,c in f]})
rows.sort(key=lambda r:(r['source'],r['table']))
json.dump(rows,open('loot_food.json','w'),indent=1)
print(len(rows),'loot tables with food/seeds\n')
for r in rows:
    print(f"[{r['source']}] {r['table']}")
    for x in r['food'][:14]: print('    ',x)
