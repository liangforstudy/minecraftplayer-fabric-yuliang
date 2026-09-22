import re, json, subprocess, foodex
T='map/im/mappings/mappings.tiny'
obf_m={}; obf_f={}; cls_obf={}
for ln in open(T):
    p=ln.rstrip('\n').split('\t')
    if p[0]=='METHOD': obf_m[(p[1],p[2],p[3])]=p[4]
    elif p[0]=='FIELD': obf_f[(p[1],p[3])]=p[4]
    elif p[0]=='CLASS': cls_obf[p[2]]=p[1]
O1293=cls_obf['net/minecraft/class_1293']

def remap(src):
    def fix_method(m):
        owner,name,desc=m.group(1),m.group(2),m.group(3)
        inter=obf_m.get((owner,desc,name))
        if owner=='cpr$a' and inter:
            return f'Method net/minecraft/class_4174$class_4175.{inter}:{desc}'
        return m.group(0)
    src=re.sub(r'Method ([\w$/]+)\.([\w$<>]+):(\([^)]*\)\S*)', fix_method, src)
    src=src.replace('class cpr$a','class net/minecraft/class_4174$class_4175')
    src=src.replace(')Lcpr$a;',')Lnet/minecraft/class_4174$class_4175;')
    src=src.replace('descriptor: (I)Lcpr$a;','descriptor: (I)Lnet/minecraft/class_4174$class_4175;')
    src=re.sub(r':Lcpr;', ':Lnet/minecraft/class_4174;', src)
    def fix_eff(m):
        inter=obf_f.get(('bsb',m.group(1)))
        return f'Field net/minecraft/class_1294.{inter}:' if inter else m.group(0)
    src=re.sub(r'Field bsb\.(\w+):', fix_eff, src)
    src=src.replace(f'Method {O1293}."<init>"','Method net/minecraft/class_1293."<init>"')
    return src

def dump(p): return remap(subprocess.run(['javap','-p','-c','-constants','-v',p],capture_output=True,text=True).stdout)

CONSTS={}
c,_=foodex.analyze('map/mc/cps.class',{},src=dump('map/mc/cps.class'))
CONSTS.update(c)
_,b=foodex.analyze('map/mc/cut.class',CONSTS,src=dump('map/mc/cut.class'))
items={}
for iid,f in b: items.setdefault(iid,f)
json.dump(items,open('food_vanilla.json','w'),indent=1)
print('vanilla food items:',len(items))
for k in ['apple','bread','cooked_beef','carrot','potato','sweet_berries','cooked_cod','rotten_flesh','golden_apple','melon_slice','cooked_porkchop','cooked_chicken','cooked_rabbit','dried_kelp','beetroot','glow_berries','honey_bottle','pufferfish','poisonous_potato','chicken','beef','mutton','cod','salmon','tropical_fish','spider_eye','mushroom_stew','beetroot_soup','rabbit_stew','suspicious_stew','cookie','pumpkin_pie','baked_potato','cooked_mutton','cooked_salmon','cake','chorus_fruit']:
    if k in items: print(f'  {k:22s}',items[k])
