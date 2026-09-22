import re,json,foodex
exec(open('vanilla_food2.py').read().split('CONSTS={}')[0])
# obf field -> intermediary for class_4176 (obf 'cps')
obf2inter={}
for ln in open(T):
    p=ln.rstrip('\n').split('\t')
    if p[0]=='FIELD' and p[1]=='cps': obf2inter[p[3]]=p[4]
c,_=foodex.analyze('map/mc/cps.class',{},src=dump('map/mc/cps.class'))
out={}
for k,v in c.items():
    if '.' in k: continue
    it=obf2inter.get(k)
    if it:
        out['class_4176.'+it]=v
        out[it]=v
json.dump(out,open('vanilla_consts.json','w'),indent=1)
print(len(out)//2,'vanilla FoodComponents constants exported')
print(out.get('class_4176.field_18638'))
