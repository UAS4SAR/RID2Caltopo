"""Prepare a local, gap-focused flight manifest; no aircraft connection or transfer.

Uses saved DSM/mesh support and production-planner nadir anchor legs. Nominal
footprint opportunities are planning proxies, not promises of reconstructed depth.
"""
import argparse
import copy
import datetime as dt
import hashlib
import json
import math
from pathlib import Path
import uuid

import numpy as np
from scipy import ndimage
import shapely
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.colors import ListedColormap
from compare_saved_surfaces import Result, mercator, R


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--result', type=Path, required=True)
    p.add_argument('--anchors', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--mission-id', required=True)
    p.add_argument('--source-experiment-id')
    args = p.parse_args()
    assert str(uuid.UUID(args.mission_id)) == args.mission_id
    args.output.mkdir(parents=True, exist_ok=True)
    source = json.loads((args.result/'mission.json').read_text())
    ring = source['feature']['geometry']['coordinates'][0]
    origin = mercator(ring).mean(axis=0)
    scale = np.cos(np.deg2rad(np.asarray(ring)[:, 1].mean()))
    boundary = (mercator(ring)-origin)*scale
    polygon = shapely.Polygon(boundary)
    result = Result(args.result, origin, scale)
    anchors = json.loads(args.anchors.read_text())
    def local(lonlat): return (mercator([lonlat])[0]-origin)*scale
    def geographic(point):
        x, y = origin+np.asarray(point)/scale
        return [float(np.rad2deg(x/R)), float(np.rad2deg(np.arctan(np.sinh(y/R))))]
    step = .4
    west, south, east, north = polygon.bounds
    xx, yy = np.meshgrid(np.arange(west, east, step)+step/2, np.arange(south, north, step)+step/2)
    inside = shapely.contains_xy(polygon, xx, yy)
    heights, mesh = result.sample(xx, yy)
    missing = inside&~mesh
    native_missing = inside&~np.isfinite(heights)
    nearest = ndimage.distance_transform_edt(~np.isfinite(heights), return_distances=False, return_indices=True)
    estimated = heights[tuple(nearest)]-result.meta['originDjiAltitude']
    points = np.column_stack((xx[missing], yy[missing], estimated[missing]))
    weight = np.where(native_missing[missing], 4., 1.)
    # Upper supported visible height rounded upward, relative to the source's
    # takeoff estimate. This is a planning reference, not obstacle clearance.
    reference = float(math.ceil(result.meta['quality']['maxSurfaceHeightMeters']))
    aircraft_height = source['parameters']['heightMeters']
    assert 0 <= reference <= min(40, aircraft_height-10)
    gsd = source['parameters']['desiredGroundResolutionCmPerPixel']/100
    width = source['cameraSpecifications']['widthPixels']; image_height = source['cameraSpecifications']['heightPixels']
    tilt = math.radians(30)
    narrow = 1/max(1, math.cos(tilt)+image_height*gsd/(2*aircraft_height)*math.sin(tilt))
    spacing = image_height*gsd*.15*narrow**2*(aircraft_height-reference)/aircraft_height
    focal = aircraft_height/gsd
    offset = (aircraft_height-reference)*math.tan(tilt)
    anchor_paths = [(local(a['start']), local(a['end']), a['passIndex']) for a in anchors['legs']]
    candidates = []
    # Sparse patch candidates are evaluated for three moving exposures from each
    # opposing direction, with an image-edge allowance and nearest known heights.
    for x in np.arange(math.ceil(west/10)*10, east, 10):
        for y in np.arange(math.ceil(south/10)*10, north, 10):
            if not polygon.contains(shapely.Point(x, y)): continue
            for length in (20., 30., 40.):
                start = np.array([x, y-length/2]); end = np.array([x, y+length/2])
                if start[1] < south-10 or end[1] > north+10: continue
                per_direction = []
                for sign in (1, -1):
                    a, b = (start, end) if sign == 1 else (end, start)
                    moving = np.arange(spacing, length-.001, spacing)
                    count = np.zeros(len(points), dtype=np.uint8)
                    right = np.array([sign, 0, 0.]); down = np.array([0., -sign*math.cos(tilt), -math.sin(tilt)]); forward = np.array([0., sign*math.sin(tilt), -math.cos(tilt)])
                    central_ray = np.array([0., sign*offset, reference-aircraft_height])
                    assert abs(central_ray@down)<1e-8 and central_ray@forward>0
                    for t in moving:
                        centre = np.array([x, a[1]+sign*t-sign*offset, aircraft_height])
                        v = points-centre
                        z = v@forward
                        u = focal*(v@right)/np.maximum(z, 1e-9)+width/2
                        q = focal*(v@down)/np.maximum(z, 1e-9)+image_height/2
                        count += (z>5)&(u>80)&(u<width-80)&(q>80)&(q<image_height-80)
                    per_direction.append(count>=3)
                opportunity = per_direction[0]&per_direction[1]
                if (weight*opportunity).sum() < 20: continue
                cost = 2*(math.ceil(length/spacing)+1)
                candidates.append(dict(start=start,end=end,opportunity=opportunity,photos=cost,length=length))
    selected = []
    seen = np.zeros(len(points), dtype=bool)
    current = anchor_paths[-1][1]
    photo_count = anchors['estimatedPhotos']
    while len(selected) < 4:
        best = None
        for c in candidates:
            if photo_count+c['photos'] > 190: continue
            newly = c['opportunity']&~seen
            gain = float((weight*newly).sum()*step*step)
            if gain < 20: continue
            a = c['start']-np.array([0., offset]); b = c['end']+np.array([0., offset])
            north_first = np.linalg.norm(current-a) <= np.linalg.norm(current-b)
            first = a if north_first else b
            # Pair travel includes both capture legs and the change of look offset.
            metres = 2*c['length']+2*offset+float(np.linalg.norm(current-first))
            score = gain/(metres/4+8)
            if best is None or score > best[0]: best = (score,c,north_first,gain)
        if best is None: break
        _, c, north_first, gain = best
        selected.append((c,north_first,gain));seen |= c['opportunity'];photo_count += c['photos']
        current = c['start']+np.array([0.,offset]) if north_first else c['end']-np.array([0.,offset])
        candidates = [other for other in candidates if other is not c]
    assert selected
    legs = []
    for index, (c,north_first,gain) in enumerate(selected):
        a, b = (c['start'],c['end']) if north_first else (c['end'],c['start'])
        for first,last in ((a,b),(b,a)):
            legs.append({'patch':index+1,'targetStart':geographic(first),'targetEnd':geographic(last)})
    manifest = copy.deepcopy(source)
    manifest.update(missionId=args.mission_id, title=source['title']+' · Gap-focused crossing + −60°', createdAt=dt.datetime.now(dt.timezone.utc).isoformat().replace('+00:00','Z'))
    manifest['photoIntake']['missionId'] = args.mission_id
    manifest['parameters'].update(captureTrigger='distance-v1',cameraHeadingPolicy='aircraft-first-v1')
    manifest['captureProfile'] = {'id':'gap-targeted-60','passes':[
        {'gimbalPitchDegrees':-90,'directionDegrees':0,'forwardOverlapPercent':80,'sideOverlapPercent':70},
        {'gimbalPitchDegrees':-90,'directionDegrees':90,'forwardOverlapPercent':80,'sideOverlapPercent':70},
        {'gimbalPitchDegrees':-60,'directionDegrees':0,'forwardOverlapPercent':85,'sideOverlapPercent':80}]}
    manifest['gapCapturePlan'] = {'version':1,'sourceLayerId':result.layer.get('experiment',{}).get('sourceLayerId',result.layer.get('layerId')),
        'sourceSurfaceSha256':hashlib.sha256((args.result/'surface.f32').read_bytes()).hexdigest(),
        'assignmentBoundary':ring,'planningSurfaceHeightMeters':reference,'captureLegs':legs,
        'purpose':'Crossed nadir anchors plus dense opposing north/south canopy views; prioritize remaining height gaps',
        'footprintModel':'Flat camera geometry with nearest known heights for missing cells; no occlusion model; opportunities are not predicted reconstruction coverage'}
    if 'launchLocation' in manifest:
        manifest['gapCapturePlan']['sourceLaunchReferenceOnly'] = manifest.pop('launchLocation')
    if args.source_experiment_id:
        assert str(uuid.UUID(args.source_experiment_id)) == args.source_experiment_id
        manifest['gapCapturePlan']['sourceExperimentId'] = args.source_experiment_id
    (args.output/'mission.json').write_text(json.dumps(manifest,indent=2))
    details = {'planningSurfaceHeightMeters':reference,'obliqueSpacingMeters':spacing,'obliqueCentreOffsetMeters':offset,
        'anchorPhotos':anchors['estimatedPhotos'],'estimatedPhotos':photo_count,'targetPatches':len(selected),
        'footprintOpportunity':{'missingMeshAreaM2':float(seen.sum()*step*step),'missingHeightAreaM2':float((seen&native_missing[missing]).sum()*step*step),
            'meaning':'Potential visibility in at least three moving exposures from each look direction; not observed new coverage'},
        'patches':[{'centreLocalEnuMeters':((c['start']+c['end'])/2).tolist(),'lengthMeters':c['length'],'estimatedPhotos':c['photos'],'weightedMarginalOpportunityM2':gain} for c,_,gain in selected]}
    (args.output/'planning-analysis.json').write_text(json.dumps(details,indent=2))
    fig, ax = plt.subplots(figsize=(11,7),layout='constrained')
    categories=np.zeros(inside.shape);categories[inside&~mesh]=1;categories[native_missing]=2
    ax.imshow(np.ma.array(categories,mask=~inside),origin='lower',extent=[xx.min()-step/2,xx.max()+step/2,yy.min()-step/2,yy.max()+step/2],cmap=ListedColormap(['#dbe7e4','#e5b04b','#d64743']),vmin=0,vmax=2,interpolation='nearest')
    ax.plot(*boundary.T,color='#1b333c',linewidth=1.3,label='Captured target')
    for a,b,pass_index in anchor_paths:
        ax.plot([a[0],b[0]],[a[1],b[1]],color='#3f7698' if pass_index==0 else '#6b95ad',alpha=.3,linewidth=.7)
    aircraft_paths = [(a,b) for a,b,_ in anchor_paths]
    for i,(c,first,_) in enumerate(selected):
        ax.plot([c['start'][0],c['end'][0]],[c['start'][1],c['end'][1]],color='#1e7a4f',linewidth=3)
        ax.text(c['start'][0]+1,(c['start'][1]+c['end'][1])/2,f'Patch {i+1}',fontsize=10)
        for sign in (1,-1):
            a,b=(c['start'],c['end']) if sign==1 else (c['end'],c['start'])
            a=a-np.array([0.,sign*offset]);b=b-np.array([0.,sign*offset])
            ax.annotate('',xy=b,xytext=a,arrowprops={'arrowstyle':'->','color':'#633fad','linewidth':1.8})
        a,b=(c['start'],c['end']) if first else (c['end'],c['start'])
        for begin,end in ((a,b),(b,a)):
            sign=1 if end[1]>begin[1] else -1
            aircraft_paths.append((begin-np.array([0.,sign*offset]),end-np.array([0.,sign*offset])))
    for (_,end),(start,_) in zip(aircraft_paths,aircraft_paths[1:]):
        ax.plot([end[0],start[0]],[end[1],start[1]],'--',color='#67777b',alpha=.45,linewidth=.7)
    ax.set(xlabel='East (m)',ylabel='North (m)',title='Prepared gap-focused flight · crossed nadir + opposing −60° patch views\nRed: missing height · gold: mesh gaps · blue: nadir · green: optical target · purple: oblique · dashed: transit')
    ax.set_aspect('equal');fig.savefig(args.output/'flight-pattern.png',dpi=170);plt.close(fig)
    print(json.dumps(details,indent=2))


if __name__=='__main__':main()
