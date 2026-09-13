#!/usr/bin/env python3
"""Prepare a peak-preserving, explicitly masked .aol package from classified LAS/LAZ.
Run ahead of deployment, never on an aircraft/tablet update path.
"""
import argparse, hashlib, json, math, time, zipfile, resource, sys
from pathlib import Path
import laspy
import numpy as np
from pyproj import Transformer
from scipy.spatial import cKDTree

R = 6371008.8

def write_package(path, metadata, surface, ground):
    payloads = {"surface.f32": np.asarray(surface, dtype="<f4").tobytes(),
                "ground.f32": np.asarray(ground, dtype="<f4").tobytes()}
    for name, data in payloads.items():
        metadata[name.split('.')[0] + "SHA256"] = hashlib.sha256(data).hexdigest()
    part = Path(str(path) + '.part')
    with zipfile.ZipFile(part, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for name, data in {"manifest.json": json.dumps(metadata, sort_keys=True).encode(), **payloads}.items():
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data)
    part.replace(path)


def prepare(a):
    start = time.monotonic()
    if a.geoid not in ("GEOID12B","GEOID18"):
        raise ValueError("Unsupported or unknown vertical reference")
    origin_lat, origin_lon = a.center
    if not abs(origin_lat)<70 or not abs(origin_lon)<=180 or not 0<a.extent<=1800:
        raise ValueError('Initial preparation supports local extents up to 1800 m below 70 degrees latitude')
    if a.spacing not in (1,2,4):
        raise ValueError('Spacing must be 1, 2 or 4 m; coarse masks preserve every one-metre child')
    factor=int(a.spacing)
    n=math.ceil((a.extent+2*60.96)/factor)*factor
    west=south=-n/2
    surface=np.full(n*n,-np.inf)
    ground=np.full(n*n,np.nan)
    ground_points=[]; ground_heights=[]
    crs=None; source_hashes={}; source_count=accepted_count=excluded_count=0
    class_counts=np.zeros(256,dtype=np.int64)
    for path in [a.input]+(a.additional_input or []):
        with path.open('rb') as source:
            source_hashes[path.name]=hashlib.file_digest(source,'sha256').hexdigest()
        with laspy.open(path) as reader:
            candidate=reader.header.parse_crs()
            if candidate is None or not candidate.is_compound or candidate.sub_crs_list[-1].to_epsg()!=5703:
                raise ValueError('Explicit NAVD88 metre CRS required; transform other references with provenance first')
            if crs is not None and candidate!=crs:
                raise ValueError('Source seam CRS mismatch; no implicit transformation or datum mixing')
            crs=candidate
            transformer=Transformer.from_crs(crs.sub_crs_list[0],4326,always_xy=True)
            for points in reader.chunk_iterator(250_000):
                source_count+=len(points)
                cls=np.asarray(points.classification);z=np.asarray(points.z)
                class_counts+=np.bincount(cls,minlength=256)
                rejected=(np.asarray(points.withheld)!=0)|np.isin(cls,[7,18])|~np.isfinite(z)
                excluded_count+=int(rejected.sum())
                lon,lat=transformer.transform(np.asarray(points.x),np.asarray(points.y))
                x=np.radians(lon-origin_lon)*R*math.cos(math.radians(origin_lat))
                y=np.radians(lat-origin_lat)*R
                cols=np.floor(x-west).astype(int);rows=np.floor(y-south).astype(int)
                valid=~rejected & (cols>=0)&(cols<n)&(rows>=0)&(rows<n)
                accepted_count+=int(valid.sum())
                np.maximum.at(surface,rows[valid]*n+cols[valid],z[valid])
                g=valid & (cls==2)
                if np.any(g):
                    ground_points.append(np.column_stack([x[g],y[g]]));ground_heights.append(z[g])
    surface[~np.isfinite(surface)]=np.nan
    if ground_points:
        # Ground only, bounded nearest support; never fills a surface coverage hole.
        xx,yy=np.meshgrid(west+np.arange(n)+.5,south+np.arange(n)+.5)
        dist,index=cKDTree(np.concatenate(ground_points)).query(np.column_stack([xx.ravel(),yy.ravel()]),distance_upper_bound=3)
        ok=np.isfinite(dist);ground[ok]=np.concatenate(ground_heights)[index[ok]]
    surface=surface.reshape(n,n); ground=ground.reshape(n,n)
    if factor>1:
        coarse=n//factor
        def reduce_grid(grid,maximum):
            blocks=grid.reshape(coarse,factor,coarse,factor)
            valid=np.isfinite(blocks).all(axis=(1,3))
            with np.errstate(invalid='ignore'):
                reduced=np.max(blocks,axis=(1,3)) if maximum else np.mean(blocks,axis=(1,3))
            reduced[~valid]=np.nan
            return reduced
        surface=reduce_grid(surface,True)
        ground=reduce_grid(ground,False)
        n=coarse
    metadata = dict(schema=1,layer='top-surface',id=a.dataset,version=a.version,
        processingVersion='r2c-lidar-max-1',sourceURL=a.source_url,surveyDate=a.survey_date,
        verticalReference='NAVD88 / '+a.geoid+' / metres',horizontalCRS='R2C_LOCAL_EQUIRECTANGULAR_WGS84',units='metres',
        quality='LAS accepted returns max per cell; withheld and classes 7/18 excluded. No surface gap fill. Coarse cells require all one-metre child cells valid; maxima never averaged. Ground nearest class 2 within 3 m. Unclassified peaks are not object identities. Cell footprint is horizontal localization uncertainty; aircraft/survey error is not bounded by pixel size.',
        originLatitude=origin_lat,originLongitude=origin_lon,west=west,south=south,spacing=a.spacing,width=n,height=n,
        sourceCRS=crs.to_wkt(),sourceSHA256=source_hashes,
        requestedExtentMeters=a.extent,paddingMeters=60.96)
    if a.wire_observations:
        metadata['wireObservations']=json.loads(a.wire_observations.read_text())
        for wire in metadata['wireObservations']:
            if wire.get('heightMeters') is not None:
                raise ValueError('Initial wire observations accept only explicitly unknown heights')
            for key in ('latitude','longitude','source','date','confidence'): assert key in wire
    write_package(a.output,metadata,surface,ground)
    report=dict(package=str(a.output),packageBytes=Path(a.output).stat().st_size,expandedRasterBytes=n*n*8,
        preparationSeconds=time.monotonic()-start,
        peakProcessMemoryBytes=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss*(1 if sys.platform=="darwin" else 1024),sourcePoints=source_count,acceptedPoints=accepted_count,
        excludedNoiseOrWithheld=excluded_count,
        missingSurfaceCells=int(np.isnan(surface).sum()),cells=n*n,spacing=a.spacing,
        maximumSurfaceMeters=float(np.nanmax(surface)),maximumHeightAbovePairedGroundMeters=float(np.nanmax(surface-ground)),
        cellsAboveGround10m=int(np.sum(surface-ground>10)),classificationCounts={str(k):int(v) for k,v in enumerate(class_counts) if v})
    Path(str(a.output)+'.report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('input',type=Path); p.add_argument('output',type=Path)
    p.add_argument('--center',nargs=2,type=float,required=True,metavar=('LAT','LON'))
    p.add_argument('--extent',type=float,default=250,help='Requested square width in metres, before 60.96 m padding')
    p.add_argument('--additional-input',type=Path,action='append',help='Adjacent tiles from the SAME survey and reference; geoid/date declaration applies to every source')
    p.add_argument('--spacing',type=float,default=1)
    p.add_argument('--dataset',required=True); p.add_argument('--version',required=True)
    p.add_argument('--survey-date',required=True); p.add_argument('--geoid',required=True); p.add_argument('--source-url',required=True)
    p.add_argument('--wire-observations',type=Path,help='JSON array of dated unknown-height point observations; never infer spans')
    prepare(p.parse_args())
