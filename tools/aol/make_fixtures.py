from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).parent))
from prepare_surface import write_package
import numpy as np
root=Path(__file__).resolve().parents[2]/'test-fixtures/aol'
s=np.full((161,161),304.8,dtype='<f4');g=s.copy()
s[80,80]=312.42 # 1025 ft: aircraft at 1000 ft yields -25 ft
s[80,145]=500 # high feature wholly outside 60.96 m disk
m=dict(schema=1,layer='top-surface',id='synthetic-contract',version='1',processingVersion='fixture-1',sourceURL='synthetic:test',surveyDate='2020-01-01',verticalReference='NAVD88 / GEOID12B / metres',horizontalCRS='R2C_LOCAL_EQUIRECTANGULAR_WGS84',units='metres',quality='SYNTHETIC: not for field use',originLatitude=39.,originLongitude=-121.,west=-80.5,south=-80.5,spacing=1.,width=161,height=161)
write_package(root/'complete.aol',dict(m),s,g)
s[80,100]=np.nan
write_package(root/'hole.aol',dict(m),s,g)
# Deliberately invalid packages prove activation checks independently of ZIP parsing.
import json,zipfile
for name,key,value in [('bad-checksum','surfaceSHA256','0'*64),('bad-reference','verticalReference','unknown'),('bad-units','units','feet')]:
    with zipfile.ZipFile(root/'complete.aol') as z:
        entries={n:z.read(n) for n in z.namelist()}
    metadata=json.loads(entries['manifest.json']);metadata[key]=value
    entries['manifest.json']=json.dumps(metadata).encode()
    with zipfile.ZipFile(root/f'{name}.aol','w',zipfile.ZIP_DEFLATED) as z:
        for n,b in entries.items():z.writestr(n,b)
