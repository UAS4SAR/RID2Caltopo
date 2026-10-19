"""Independent, dense-grid opportunity check of exported production route legs.

Tests two distance-trigger phases at two surface heights; deliberately does not
claim visibility through vegetation or predict usable feature matches.
"""
import argparse
import json
import math
from pathlib import Path
import numpy as np
import shapely
from scipy import ndimage
from compare_saved_surfaces import Result, mercator


def main():
    p=argparse.ArgumentParser();p.add_argument('--result',type=Path,required=True);p.add_argument('--plan',type=Path,required=True);p.add_argument('--poses',type=Path);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();mission=json.loads((a.result/'mission.json').read_text());ring=mission['feature']['geometry']['coordinates'][0]
    origin=mercator(ring).mean(axis=0);scale=np.cos(np.deg2rad(np.array(ring)[:,1].mean()));poly=shapely.Polygon((mercator(ring)-origin)*scale)
    result=Result(a.result,origin,scale);west,south,east,north=poly.bounds;step=.4
    xx,yy=np.meshgrid(np.arange(west,east,step)+step/2,np.arange(south,north,step)+step/2);inside=shapely.contains_xy(poly,xx,yy)
    h,mesh=result.sample(xx,yy);nearest=ndimage.distance_transform_edt(~np.isfinite(h),return_distances=False,return_indices=True)
    z=(h[tuple(nearest)]-result.meta['originDjiAltitude'])[inside];x=xx[inside];y=yy[inside];missing=~mesh[inside];missing_height=~np.isfinite(h[inside])
    manifest=json.loads((a.plan/'mission.json').read_text());summary=json.loads((a.plan/'route-summary.json').read_text());legs=json.loads((a.plan/'route-legs.json').read_text())
    width=summary['captureWidthPixels'];height=summary['captureHeightPixels'];altitude=summary['heightMeters'];reference=manifest['gapCapturePlan']['planningSurfaceHeightMeters'];focal=altitude/(summary['groundResolutionCmPerPixel']/100)
    rows=[];nadir=np.zeros(len(x),dtype=int);strips=np.zeros(len(x),dtype=int);opposed=[]
    def image_count(start,end,spacing,pitch):
        delta=end-start;length=np.linalg.norm(delta);direction=delta/length;right=np.array([direction[1],-direction[0]])
        angle=math.radians(90+pitch);st,ct=math.sin(angle),math.cos(angle);minimum=np.full(len(x),65535,dtype=np.uint16)
        for surface in (z,np.full_like(z,reference)):
            for phase in (1.,.5):
                count=np.zeros(len(x),dtype=np.uint16)
                for t in np.arange(spacing*phase,length-.001,spacing):
                    cx,cy=start+direction*t;dx=x-cx;dy=y-cy;dz=surface-altitude;along=dx*direction[0]+dy*direction[1]
                    depth=along*st-dz*ct;u=focal*(dx*right[0]+dy*right[1])/np.maximum(depth,1e-9)+width/2;v=focal*(-along*ct-dz*st)/np.maximum(depth,1e-9)+height/2
                    count+=(depth>5)&(u>80)&(u<width-80)&(v>80)&(v<height-80)
                minimum=np.minimum(minimum,count)
        return minimum
    photo_count=0;distance=0;previous=None
    for leg in legs:
        start,end=((mercator([leg[k]])[0]-origin)*scale for k in ('start','end'))
        length=float(np.linalg.norm(end-start));distance+=length
        if previous is not None:distance+=float(np.linalg.norm(start-previous))
        previous=end;photo_count+=math.ceil(length/leg['spacingMeters'])+1
        pitch=summary['capturePasses'][leg['passIndex']]['gimbalPitchDegrees'];count=image_count(start,end,leg['spacingMeters'],pitch)
        rows.append(dict(passIndex=leg['passIndex'],supportedGapAreaM2=float(((count>=3)&missing).sum()*step**2)))
        if pitch<=-87:nadir+=count;strips+=(count>0)
        else:opposed.append(count)
    assert len(opposed)%2==0
    support=np.zeros(len(x),dtype=bool)
    for first,second in zip(opposed[::2],opposed[1::2]):support|=(first>=3)&(second>=3)&(nadir>=3)
    report=dict(gridSpacingMeters=step,checkedTargetSamples=len(x),estimatedPhotos=photo_count,estimatedFlightSeconds=math.ceil(distance/summary['speedMetersPerSecond']+len(legs)*4),
        targetWithThreeNadirViewsPercent=float((nadir>=3).mean()*100),targetWithTwoNadirStripsPercent=float((strips>=2).mean()*100),
        missingMeshWithOpposedThreeViewOpportunityPercent=float((support&missing).sum()/missing.sum()*100),missingHeightWithOpposedThreeViewOpportunityPercent=float((support&missing_height).sum()/missing_height.sum()*100),
        remainingMeshOutsideOpportunityM2=float((missing&~support).sum()*step**2),remainingMissingHeightOutsideOpportunityM2=float((missing_height&~support).sum()*step**2),
        assumptions='Worst count across half/full distance-trigger phases and local nearest-known/upper-reference heights; 80 px image margin. No occlusion, texture, wind or image-quality model; opportunity is not a gap-free guarantee.',legs=rows)
    assert report['estimatedPhotos']==summary['estimatedPhotos'];assert abs(report['estimatedFlightSeconds']-summary['estimatedFlightSeconds'])<=1
    if a.poses:
        poses=json.loads(a.poses.read_text());counts=np.zeros(len(x),dtype=int);retained=np.zeros(len(x),dtype=int)
        excluded={v['sha256'] for v in result.layer['excludedPhotos']}
        for digest,pose in poses.items():
            cx,cy=(mercator([[float(pose['GpsLongitude']),float(pose['GpsLatitude'])]])[0]-origin)*scale
            cz=float(pose['AbsoluteAltitude'])-result.meta['originDjiAltitude'];yaw=math.radians(float(pose['FlightYawDegree']))
            depth=cz-z;f=float(mission['parameters']['heightMeters'])/(mission['parameters']['desiredGroundResolutionCmPerPixel']/100)
            u=f*((x-cx)*math.cos(yaw)-(y-cy)*math.sin(yaw))/np.maximum(depth,1e-9)+width/2
            v=f*(-(x-cx)*math.sin(yaw)-(y-cy)*math.cos(yaw))/np.maximum(depth,1e-9)+height/2
            visible=(depth>5)&(u>80)&(u<width-80)&(v>80)&(v<height-80)
            counts+=visible
            if digest not in excluded:retained+=visible
        report['capturedFlightNominalFootprints']=dict(missingMeshMedianViews=float(np.median(counts[missing])),supportedMeshMedianViews=float(np.median(counts[~missing])),missingMeshWithAtLeastThreeImagesPercent=float((counts[missing]>=3).mean()*100),
            missingMeshWithAtLeastThreeRetainedImagesPercent=float((retained[missing]>=3).mean()*100),interpretation='Approximate nadir footprints at nearest-known heights; high counts with gaps indicate that simply repeating the same views is insufficient. Does not measure actual feature quality.')
    a.output.write_text(json.dumps(report,indent=2));print(json.dumps({k:v for k,v in report.items() if k!='legs'},indent=2))


if __name__=='__main__':main()
