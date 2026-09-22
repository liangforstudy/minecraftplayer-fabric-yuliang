import re, subprocess, json, sys

# tiny: FIELD <obfClass> <obfName> <desc> <interName>  -- v1 format: FIELD owner desc name interName
tiny = {}
for line in open('im/mappings/mappings.tiny'):
    p = line.rstrip('\n').split('\t')
    if p[0] == 'FIELD':
        # FIELD <ownerObf> <desc> <nameObf> <nameInter>
        tiny[(p[1], p[3])] = p[4]

def clinit_ids(clsfile, owner_obf):
    out = subprocess.run(['javap','-p','-c','-constants',clsfile],capture_output=True,text=True).stdout
    body = out.split('static {};',1)[1]
    res = {}
    pending = None
    for ln in body.splitlines():
        m = re.search(r'// String (\S+)$', ln)
        if m and re.match(r'^[a-z_0-9./]+$', m.group(1)) and '.' not in m.group(1):
            pending = m.group(1)
        m2 = re.search(r'putstatic .*// Field (\S+?):', ln)
        if m2 and pending:
            fld = m2.group(1)
            if '.' in fld:  # other class
                pending = None; continue
            inter = tiny.get((owner_obf, fld))
            if inter:
                res[inter] = pending
            pending = None
    return res

effects = clinit_ids('mc/bsb.class','bsb')
items   = clinit_ids('mc/cut.class','cut')
json.dump({'effects':effects,'items':items}, open('vanilla_map.json','w'), indent=1)
print('effects:',len(effects),'items:',len(items))
print(list(effects.items())[:5])
print(list(items.items())[:5])
