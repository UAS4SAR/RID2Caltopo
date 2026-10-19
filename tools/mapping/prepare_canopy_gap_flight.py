"""Optimize one local canopy-aware flight from retained gaps and production anchor forecasts.

All coverage figures are geometric opportunities at estimated and upper-reference
heights, not predicted reconstruction success. No aircraft connection is opened.
"""
import argparse
import copy
import datetime as dt
import hashlib
import itertools
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
from compare_saved_surfaces import Result, mercator, R


def main():
    ap=argparse.ArgumentParser()
    ap.add_argument('--result',type=Path,required=True)
    ap.add_argument('--anchors',type=Path,required=True)
    ap.add_argument('--output',type=Path,required=True)
    ap.add_argument('--reference-height',type=float,default=20)
    ap.add_argument('--select-gsd',type=float,default=2.6)
    ap.add_argument('--max-patches',type=int,default=5)
    ap.add_argument('--mission-id',required=True)
    a=ap.parse_args();assert str(uuid.UUID(a.mission_id))==a.mission_id
    a.output.mkdir(parents=True,exist_ok=True)
    source=json.loads((a.result/'mission.json').read_text());ring=source['feature']['geometry']['coordinates'][0]
    origin=mercator(ring).mean(axis=0);scale=np.cos(np.deg2rad(np.array(ring)[:,1].mean()))
    boundary=(mercator(ring)-origin)*scale;poly=shapely.Polygon(boundary)
    result=Result(a.result,origin,scale)
    def local(p):return (mercator([p])[0]-origin)*scale
    def geographic(p):
        x,y=origin+np.asarray(p)/scale
        return [float(np.rad2deg(x/R)),float(np.rad2deg(np.arctan(np.sinh(y/R))))]
    step=1.;west,south,east,north=poly.bounds
    xx,yy=np.meshgrid(np.arange(west,east,step)+step/2,np.arange(south,north,step)+step/2)
    inside=shapely.contains_xy(poly,xx,yy);heights,mesh=result.sample(xx,yy)
    nearest=ndimage.distance_transform_edt(~np.isfinite(heights),return_distances=False,return_indices=True)
    z=heights[tuple(nearest)]-result.meta['originDjiAltitude']
    points=np.column_stack([xx[inside],yy[inside],z[inside]])
    missing=~mesh[inside];missing_height=~np.isfinite(heights[inside])
    weight=np.where(missing_height,4.,np.where(missing,1.,0.))
    width=source['cameraSpecifications']['widthPixels'];image_height=source['cameraSpecifications']['heightPixels']
    options=[];plans={}
    for anchor in json.loads(a.anchors.read_text()):
        if anchor['direction']!=90:continue
        gsd=anchor['gsd'];h=anchor['summary']['heightMeters'];focal=h/(gsd/100)
        tilt=math.radians(30);offset=(h-a.reference_height)*math.tan(tilt)
        narrow=1/max(1,math.cos(tilt)+image_height*(gsd/100)/(2*h)*math.sin(tilt))
        spacing=image_height*(gsd/100)*.15*narrow**2*(h-a.reference_height)/h
        def observe(start,end,pitch,space):
            delta=end-start;length=np.linalg.norm(delta);direction=delta/length
            theta=math.radians(90+pitch);st,ct=math.sin(theta),math.cos(theta)
            right=np.array([direction[1],-direction[0]])
            worst=np.full(len(points),255,dtype=np.uint16)
            # Require support at both the local nearest known height and the
            # conservative upper canopy reference, with an 80-pixel edge reserve.
            for surface in (points[:,2],np.full(len(points),a.reference_height)):
                count=np.zeros(len(points),dtype=np.uint16)
                for distance in np.arange(space,length-.001,space):
                    centre=start+direction*distance
                    dx=points[:,0]-centre[0];dy=points[:,1]-centre[1];dz=surface-h
                    along=dx*direction[0]+dy*direction[1]
                    depth=along*st-dz*ct
                    u=focal*(dx*right[0]+dy*right[1])/np.maximum(depth,1e-9)+width/2
                    v=focal*(-along*ct-dz*st)/np.maximum(depth,1e-9)+image_height/2
                    count+=(depth>5)&(u>80)&(u<width-80)&(v>80)&(v<image_height-80)
                worst=np.minimum(worst,count)
            return worst
        base_count=np.zeros(len(points),dtype=np.uint16);base_legs=np.zeros(len(points),dtype=np.uint16)
        anchor_paths=[]
        for leg in anchor['legs']:
            start,end=local(leg['start']),local(leg['end']);anchor_paths.append((start,end))
            count=observe(start,end,-90,leg['spacingMeters']);base_count+=count;base_legs+=(count>0)
        candidates=[]
        for x in np.arange(math.ceil(west/10)*10,east,10):
            for y in np.arange(math.ceil(south/10)*10,north,10):
                if not poly.contains(shapely.Point(x,y)):continue
                for length in (20.,30.,40.,60.):
                    start=np.array([x,y-length/2]);end=np.array([x,y+length/2])
                    if start[1]<south-10 or end[1]>north+10:continue
                    counts=[observe(start-[0,offset],end-[0,offset],-60,spacing),observe(end+[0,offset],start+[0,offset],-60,spacing)]
                    support=(counts[0]>=3)&(counts[1]>=3)&(base_count>=3)
                    if (support*weight).sum()<20:continue
                    candidates.append(dict(start=start,end=end,support=support,photos=2*(math.ceil(length/spacing)+1),length=length))
        selected=[];seen=np.zeros(len(points),dtype=bool);count=anchor['summary']['estimatedPhotos'];current=anchor_paths[-1][1]
        while len(selected)<a.max_patches:
            best=None
            for c in candidates:
                if count+c['photos']>190:continue
                gain=float((weight*(c['support']&~seen)).sum())
                if gain<20:continue
                north_first=np.linalg.norm(current-(c['start']-[0,offset]))<=np.linalg.norm(current-(c['end']+[0,offset]))
                first=c['start']-[0,offset] if north_first else c['end']+[0,offset]
                seconds=(2*c['length']+2*offset+float(np.linalg.norm(current-first)))/4+8
                score=gain/(seconds+c['photos']*2)
                if best is None or score>best[0]:best=(score,c,north_first,gain)
            if best is None:break
            _,c,north_first,gain=best
            selected.append((c,north_first,gain));seen|=c['support'];count+=c['photos']
            current=c['start']+[0,offset] if north_first else c['end']-[0,offset]
            candidates=[v for v in candidates if v is not c]
        if not selected:
            options.append(dict(gsd=gsd,anchorPhotos=count,status='No room for targeted views under 190-photo budget'));continue
        # Reorder entire opposing pairs to minimize transit; pair support and
        # photo count are unchanged. Keep anchors contiguous for connectivity.
        def paths(c,forward):
            return [(c['start']-[0,offset],c['end']-[0,offset]),(c['end']+[0,offset],c['start']+[0,offset])] if forward else [(c['end']+[0,offset],c['start']+[0,offset]),(c['start']-[0,offset],c['end']-[0,offset])]
        best=None
        for order in itertools.permutations(range(len(selected))):
            for directions in itertools.product((False,True),repeat=len(selected)):
                legs=anchor_paths+[p for i,d in zip(order,directions) for p in paths(selected[i][0],d)]
                distance=sum(float(np.linalg.norm(e-s)) for s,e in legs)+sum(float(np.linalg.norm(q[0]-p[1])) for p,q in zip(legs,legs[1:]))
                if best is None or distance<best[0]:best=(distance,order,directions,legs)
        distance,order,directions,legs=best
        details=dict(gsd=gsd,heightMeters=h,planningSurfaceHeightMeters=a.reference_height,anchorPhotos=anchor['summary']['estimatedPhotos'],estimatedPhotos=count,
            estimatedFlightSeconds=math.ceil(distance/4+len(legs)*4),legCount=len(legs),targetPatches=len(selected),obliqueSpacingMeters=spacing,obliqueCentreOffsetMeters=offset,
            baselineThreeViewTargetPercent=float((base_count>=3).mean()*100),baselineTwoStripTargetPercent=float((base_legs>=2).mean()*100),
            missingMeshOpportunityPercent=float((seen&missing).sum()/missing.sum()*100),missingHeightOpportunityPercent=float((seen&missing_height).sum()/missing_height.sum()*100),
            opportunityMeaning='At least three moving images from EACH opposing oblique direction plus three nadir images, at both nearest-known and 20 m reference heights. No occlusion/texture/wind model. Not predicted recovered coverage.',
            patches=[dict(centreLocalEnuMeters=((selected[i][0]['start']+selected[i][0]['end'])/2).tolist(),lengthMeters=selected[i][0]['length'],photos=selected[i][0]['photos']) for i in order])
        options.append(details);plans[gsd]=(details,selected,order,directions,legs,anchor)
    (a.output/'candidate-comparison.json').write_text(json.dumps(options,indent=2))
    details,selected,order,directions,legs,anchor=plans[a.select_gsd]
    manifest=copy.deepcopy(source);manifest.update(missionId=a.mission_id,title=source['title']+' · One-flight canopy gaps',createdAt=dt.datetime.now(dt.timezone.utc).isoformat().replace('+00:00','Z'))
    manifest['photoIntake']['missionId']=a.mission_id
    manifest['parameters'].update(heightMeters=details['heightMeters'],desiredGroundResolutionCmPerPixel=a.select_gsd,captureTrigger='distance-v1',cameraHeadingPolicy='aircraft-first-v1')
    manifest['captureProfile']={'id':'canopy-targeted-60','passes':[{'gimbalPitchDegrees':-90,'directionDegrees':90,'forwardOverlapPercent':85,'sideOverlapPercent':80},{'gimbalPitchDegrees':-60,'directionDegrees':0,'forwardOverlapPercent':85,'sideOverlapPercent':80}]}
    targets=[]
    for index,(i,d) in enumerate(zip(order,directions)):
        c=selected[i][0];first,last=(c['start'],c['end']) if d else (c['end'],c['start'])
        for start,end in ((first,last),(last,first)):
            targets.append(dict(patch=index+1,targetStart=geographic(start),targetEnd=geographic(end)))
    manifest['gapCapturePlan']=dict(version=2,sourceLayerId=result.layer['layerId'],sourceSurfaceSha256=hashlib.sha256((a.result/'surface.f32').read_bytes()).hexdigest(),assignmentBoundary=ring,
        planningSurfaceHeightMeters=a.reference_height,captureLegs=targets,purpose='Canopy-aware east/west nadir anchors plus targeted opposing north/south obliques in one flight',
        referenceHeightEvidence='20 m above retained takeoff datum: largest retained valid exposure laser surface 18.064 m, rounded up with margin. Not a complete obstacle survey; recheck datum if launch location changes.',
        footprintModel=details['opportunityMeaning'])
    if 'launchLocation' in manifest:manifest['gapCapturePlan']['sourceLaunchReferenceOnly']=manifest.pop('launchLocation')
    (a.output/'mission.json').write_text(json.dumps(manifest,indent=2));(a.output/'planning-analysis.json').write_text(json.dumps(details,indent=2))
    fig,ax=plt.subplots(figsize=(12,8),layout='constrained')
    ax.scatter(points[~missing,0],points[~missing,1],c='#dbe7e4',s=3,marker='s');ax.scatter(points[missing,0],points[missing,1],c='#d64743',s=4,marker='s',label='Current mesh gaps')
    ax.plot(*boundary.T,c='#23313b',lw=1.2,label='Assignment')
    anchor_count=len(anchor['legs'])
    for j,(start,end) in enumerate(legs):
        color='#2771a5' if j<anchor_count else '#7040aa'
        ax.annotate('',xy=end,xytext=start,arrowprops=dict(arrowstyle='->',color=color,lw=1.3,alpha=.75))
        ax.text(*((start+end)/2),str(j+1),fontsize=8,color=color)
    for (_,end),(start,_) in zip(legs,legs[1:]):ax.plot([end[0],start[0]],[end[1],start[1]],'--',c='#809090',lw=.6,alpha=.6)
    for i in order:
        c=selected[i][0];ax.plot([c['start'][0],c['end'][0]],[c['start'][1],c['end'][1]],c='#218453',lw=3)
    ax.set_aspect('equal');ax.set(xlabel='East (m)',ylabel='North (m)',title=f'One flight · {details["estimatedPhotos"]} visible photos · {details["estimatedFlightSeconds"]/60:.1f} min route estimate\nBlue: canopy-aware nadir · purple: −60° flight legs · green: aimed strips · dashed: transit')
    ax.legend(loc='upper right');fig.savefig(a.output/'flight-pattern.png',dpi=160);plt.close(fig)
    print(json.dumps(options,indent=2))


if __name__=='__main__':main()
