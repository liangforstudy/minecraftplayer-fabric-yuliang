import re, subprocess, json, sys, os
EFF = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)),'map/vanilla_map.json')))['effects']

INTC={'iconst_m1':-1,'iconst_0':0,'iconst_1':1,'iconst_2':2,'iconst_3':3,'iconst_4':4,'iconst_5':5}
FC={'fconst_0':0.0,'fconst_1':1.0,'fconst_2':2.0}
OP=re.compile(r'^\s*(\d+): (\S+)\s*(.*)$')

def javap(p):
    return subprocess.run(['javap','-p','-c','-constants','-v',p],capture_output=True,text=True).stdout

def bsm_table(src):
    """BootstrapMethods index -> target lambda method name."""
    t={}
    sec=src.split('BootstrapMethods:',1)
    if len(sec)<2: return t
    cur=None
    for ln in sec[1].splitlines():
        m=re.match(r'\s*(\d+): #\d+ REF_',ln)
        if m: cur=int(m.group(1)); continue
        m=re.search(r'REF_invoke\w+ [\w/$]+\.([\w$]+):',ln)
        if m and cur is not None and 'LambdaMetafactory' not in ln:
            t[cur]=m.group(1)
    return t

def const_val(op,rest,com):
    if op in INTC: return ('n',INTC[op])
    if op in FC: return ('n',FC[op])
    if op in ('bipush','sipush'): return ('n',int(rest))
    if op in ('ldc','ldc_w','ldc2_w'):
        m=re.match(r'(?:float|int|double|long) (-?[\d.]+(?:[eE]-?\d+)?)',com)
        if m:
            v=m.group(1)
            return ('n', float(v) if '.' in v or 'e' in v.lower() else int(v))
        m=re.match(r'String (.*)$',com)
        if m: return ('s',m.group(1))
    return None

ID_RE=re.compile(r'^[a-z][a-z0-9_]*(?:/[a-z0-9_]+)*$')

def analyze(path, CONSTS, src=None):
    if src is None: src=javap(path)
    cls=os.path.basename(path).replace('.class','')
    BSM=bsm_table(src)
    consts={}; binds=[]; mfood={}; indy=[]
    src=src.split('BootstrapMethods:',1)[0]
    # drop the -v constant-pool header: body starts at the first line that is just '{'
    i=src.find('\n{\n')
    if i>=0: src=src[i+3:]
    blocks=re.split(r'\n(?=  \S.*[;{]\s*$)',src,flags=re.M)

    # helper methods that RETURN a half-built FoodComponent.Builder, e.g. stew(int)
    helpers={}
    BUILDER=('class_4174$class_4175','cpr$a')
    for blk in blocks:
        hdr=(blk.splitlines() or [''])[0]
        hm=re.match(r'\s*[\w.$/ ]*?([\w$]+)\(int\);\s*$',hdr)
        if not hm: continue
        if not any(b in hdr for b in BUILDER) and 'descriptor: (I)L' not in blk: continue
        if not re.search(r'descriptor: \(I\)L(?:net/minecraft/)?(?:class_4174\$class_4175|cpr\$a);',blk): continue
        h={}
        for ln in blk.splitlines():
            if 'method_19237:(F)' in ln: pass
            m=re.search(r'// float ([\d.]+)f',ln)
            if m: h['saturation']=round(float(m.group(1)),4)
            if 'method_19240:()' in ln: h['alwaysEdible']=True
            if 'method_19241:()' in ln: h['snack']=True
        helpers[hm.group(1)]=h

    for blk in blocks:
        mn=re.match(r'\s*\S.*?([\w$]+)\(',blk.splitlines()[0] if blk.splitlines() else '')
        mname=mn.group(1) if mn else None
        ops=[]
        for ln in blk.splitlines():
            m=OP.match(ln)
            if not m: continue
            idx,op,rest=int(m.group(1)),m.group(2),m.group(3)
            com=''
            if '//' in rest: rest,com=rest.split('//',1); com=com.strip()
            ops.append((op,rest.strip(),com))
        if not ops: continue
        cur=None; last_str=None; vals=[]; pend_eff=None; pend_inst=None
        for op,rest,com in ops:
            cv=const_val(op,rest,com)
            if cv:
                if cv[0]=='s':
                    if ID_RE.match(cv[1]) and len(cv[1])>1: last_str=cv[1]
                else: vals.append(cv[1])
                continue
            if op=='new' and 'class_4174$class_4175' in com:
                if cur is not None and cur.get('_built'):
                    f={k:v for k,v in cur.items() if not k.startswith('_')}
                    if cur.get('_id'): binds.append((cur['_id'],f))
                    elif mname: mfood.setdefault(mname,f)
                cur={'_id':last_str}; vals=[]; continue
            if op=='invokedynamic':
                m=re.search(r'InvokeDynamic #(\d+):',com)
                if m and last_str:
                    indy.append((last_str,BSM.get(int(m.group(1)))))
                    last_str=None
                vals=[]; continue
            if op=='getstatic':
                m=re.search(r'class_1294\.(field_\d+)',com)
                if m: pend_eff=EFF.get(m.group(1),m.group(1))
                m=re.search(r'Field (?:([\w/$]+)\.)?(\w+):L(?:net/minecraft/)?class_4174;',com)
                if m:
                    owner=(m.group(1) or '').split('/')[-1]; fld=m.group(2)
                    food=CONSTS.get(owner+'.'+fld) or CONSTS.get(fld)
                    if food:
                        if mname: mfood.setdefault(mname,food)
                        if last_str:
                            binds.append((last_str,food)); last_str=None
                        else:
                            cur=dict(food); cur['_built']=True; cur['_id']=None
                continue
            if op.startswith('invoke'):
                hm=re.search(r'Method ([\w/$."<>]+):\(I\)L(?:net/minecraft/)?(?:class_4174\$class_4175|cpr\$a);',com)
                if hm and hm.group(1).split('.')[-1] in helpers and cur is None:
                    ints=[v for v in vals if isinstance(v,int)]
                    cur=dict(helpers[hm.group(1).split('.')[-1]])
                    cur['_id']=last_str
                    if ints: cur['nutrition']=ints[-1]
                    vals=[]; continue
                fm=re.search(r'Method ([\w/$."<>]+):\(IF',com)
                if fm and cur is None and 'class_1293' not in com:
                    ints=[v for v in vals if isinstance(v,int)]
                    flts=[v for v in vals if isinstance(v,float)]
                    if ints and flts and 0<=ints[0]<=40 and 0.0<=flts[0]<=3.0:
                        f={'nutrition':ints[0],'saturation':round(flts[0],4)}
                        if pend_eff:
                            e={'effect':pend_eff}
                            if len(ints)>1: e['durationTicks']=ints[1]
                            f['effects']=[e]; pend_eff=None
                        f['_built']=True; f['_id']=last_str
                        cur=f; last_str=None
                    vals=[]; continue
                if 'class_1293."<init>"' in com:
                    ints=[v for v in vals if isinstance(v,int)]
                    pend_inst=(pend_eff, ints[0] if ints else None, ints[1] if len(ints)>1 else 0)
                    pend_eff=None; vals=[]; continue
                if cur is not None:
                    if 'method_19238:(I)' in com:
                        n=[v for v in vals if isinstance(v,int)]
                        if n: cur['nutrition']=n[-1]
                    elif 'method_19237:(F)' in com:
                        n=[v for v in vals if isinstance(v,(int,float))]
                        if n: cur['saturation']=round(float(n[-1]),4)
                    elif 'method_19240:()' in com: cur['alwaysEdible']=True
                    elif 'method_19241:()' in com: cur['snack']=True
                    elif 'method_19239:' in com and pend_inst:
                        e={'effect':pend_inst[0],'durationTicks':pend_inst[1],'amplifier':pend_inst[2]}
                        ch=[v for v in vals if isinstance(v,float)]
                        if ch and ch[-1]!=1.0: e['chance']=ch[-1]
                        cur.setdefault('effects',[]).append(e); pend_inst=None
                    elif 'method_19242:()' in com:
                        cur['_built']=True
                vals=[]; continue
            if op=='putstatic' and cur is not None and cur.get('_built'):
                m=re.search(r'Field (\w+):L(?:net/minecraft/)?class_4174;',com)
                f={k:v for k,v in cur.items() if not k.startswith('_')}
                if m:
                    consts[cls+'.'+m.group(1)]=f; consts[m.group(1)]=f
                    cur=None; continue
                if cur.get('_id'): binds.append((cur['_id'],f))
                else:
                    mi=re.search(r'Field (\w+):L(?:net/minecraft/)?class_1792;',com)
                    if mi: binds.append((mi.group(1).lower(),f))
                    elif mname: mfood.setdefault(mname,f)
                cur=None; continue
            if cur is not None and cur.get('_built') and op in ('areturn','astore','astore_1','astore_2','astore_3','putfield'):
                f={k:v for k,v in cur.items() if not k.startswith('_')}
                if cur.get('_id'): binds.append((cur['_id'],f))
                elif mname: mfood.setdefault(mname,f)
                cur=None
        if cur is not None and cur.get('_built'):
            f={k:v for k,v in cur.items() if not k.startswith('_')}
            if cur.get('_id'): binds.append((cur['_id'],f))
            elif mname: mfood.setdefault(mname,f)
    for iid,lam in indy:
        if lam and lam in mfood: binds.append((iid,mfood[lam]))
    return consts,binds

def run(paths, seed=None):
    CONSTS=dict(seed or {}); BIND={}
    for p in paths:  # pass 1: constants
        c,_=analyze(p,CONSTS); CONSTS.update(c)
    for p in paths:  # pass 2: bindings (consts now resolvable)
        _,b=analyze(p,CONSTS)
        for iid,f in b: BIND.setdefault(iid,f)
    return CONSTS,BIND

if __name__=='__main__':
    C,B=run(sys.argv[1:])
    print(json.dumps({'constants':{k:v for k,v in C.items() if '.' in k},'items':B},indent=1))
