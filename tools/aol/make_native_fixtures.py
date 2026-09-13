#!/usr/bin/env python3
"""Deterministic small LAS/LAZ fixtures for both mobile platforms' shared decoder."""
from pathlib import Path
import json
from datetime import date
import numpy as np
import laspy
from pyproj import CRS, Transformer

root=Path(__file__).resolve().parents[2]/'test-fixtures/aol'
lat,lon=39.0,-121.0
for name,code in [('albers',6350),('utm',6339)]:
    for side in ['west','east']:
        xs=np.arange(-600,0) if side=='west' else np.arange(0,600)
        xx,yy=np.meshgrid(xs+.25,np.arange(-150,150)+.25)
        x,y=xx.ravel(),yy.ravel()
        lng=lon+np.degrees(x/(6371008.8*np.cos(np.radians(lat))))
        lt=lat+np.degrees(y/6371008.8)
        tx,ty=Transformer.from_crs(4326,code,always_xy=True).transform(lng,lt)
        header=laspy.LasHeader(point_format=6,version='1.4')
        header.creation_date=date(2026,1,1)
        header.add_crs(CRS.from_user_input(f'EPSG:{code}+5703'))
        header.scales=[.001,.001,.001];header.offsets=[float(np.floor(tx.min())),float(np.floor(ty.min())),0]
        las=laspy.LasData(header)
        nearest=int(np.argmin(x*x+y*y))
        las.x=np.concatenate([np.tile(tx,2),np.full(3,tx[nearest])]);las.y=np.concatenate([np.tile(ty,2),np.full(3,ty[nearest])])
        tops=np.where(((np.abs(x)<1)|((x>430)&(x<440)))&(np.abs(y)<1),320.,310.)
        las.z=np.concatenate([np.full(len(x),300.),tops,[900.,950.,1000.]])
        las.classification=np.concatenate([np.full(len(x),2),np.full(len(x),1),[7,18,1]])
        las.withheld=np.concatenate([np.zeros(len(x)*2+2,dtype=np.uint8),[1]])
        las.write(root/f'native-{name}-{side}.laz')
    # Explicit unsupported reference is never silently interpreted as NAVD88 metres.
header=laspy.LasHeader(point_format=6,version='1.4')
header.creation_date=date(2026,1,1)
header.add_crs(CRS.from_epsg(6339))
las=laspy.LasData(header);las.x=[670000];las.y=[4320000];las.z=[300];las.classification=[2]
las.write(root/'native-unknown-reference.laz')
