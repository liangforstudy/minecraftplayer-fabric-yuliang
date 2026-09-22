import re,subprocess,sys,json
INTC={'iconst_m1':-1,'iconst_0':0,'iconst_1':1,'iconst_2':2,'iconst_3':3,'iconst_4':4,'iconst_5':5,
      'fconst_0':0.0,'fconst_1':1.0,'fconst_2':2.0,'dconst_0':0.0,'dconst_1':1.0,'lconst_0':0,'lconst_1':1}
src=subprocess.run(['javap','-p','-c','-constants',sys.argv[1]],capture_output=True,text=True).stdout
body=src.split('static {};',1)[-1]
out={}; pend=[]
for ln in body.splitlines():
    m=re.match(r'\s*\d+: (\S+)\s*(.*)',ln)
    if not m: continue
    op,rest=m.group(1),m.group(2)
    com=rest.split('//',1)[1].strip() if '//' in rest else ''
    if op in INTC: pend.append(INTC[op])
    elif op in ('bipush','sipush'): pend.append(int(rest.split()[0]))
    elif op.startswith('ldc'):
        mm=re.match(r'(?:float|int|double|long) (-?[\d.]+(?:[eE]-?\d+)?)',com)
        if mm:
            v=mm.group(1); pend.append(float(v) if '.' in v else int(v))
        else:
            ms=re.match(r'String (.*)',com)
            if ms: pend.append(ms.group(1))
    elif op=='getstatic' and 'class_1267' in com:
        mm=re.search(r'class_1267\.(\w+)',com); pend.append('difficulty:'+mm.group(1) if mm else None)
    elif op=='putstatic':
        mm=re.search(r'Field (\w+):',com)
        if mm and pend: out[mm.group(1)]=pend[-1]
        pend=[]
    elif op.startswith('invoke'): pend=pend[:-1] if pend else []
print(json.dumps(out,indent=1))
