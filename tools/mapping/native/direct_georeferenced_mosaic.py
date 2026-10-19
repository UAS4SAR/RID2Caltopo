"""Prototype a nadir thermal visual mosaic from DJI camera pose metadata.

This is a CPU reference implementation for device ports, not calibrated
photogrammetry. It assumes a locally flat ground plane and preserves original
photos by reading them without modification.
"""
from pathlib import Path
import argparse, hashlib, json, math, re, time, uuid, zipfile
import cv2
import numpy as np
from PIL import Image
R=6378137.0
TAGS=re.compile(rb'drone-dji:(GpsLatitude|GpsLongitude|RelativeAltitude|GimbalYawDegree|GimbalPitchDegree|FlightYawDegree|LRFTargetLon|LRFTargetLat|LRFTargetDistance|GpsStatus)="([^"]*)"')
def metadata(path):
    raw=path.read_bytes()
    tags={k.decode():v.decode() for k,v in TAGS.findall(raw)}
    pitch=float(tags['GimbalPitchDegree'])
    if abs(pitch+90)>1: return None
    lat=float(tags.get('LRFTargetLat',tags['GpsLatitude']))
    lon=float(tags.get('LRFTargetLon',tags['GpsLongitude']))
    altitude=float(tags.get('LRFTargetDistance',tags['RelativeAltitude']))
    heading=(float(tags['FlightYawDegree'])+float(tags['GimbalYawDegree']))%360
    # DJI's 45-degree diagonal field of view and 5:4 sensor aspect ratio.
    t=math.tan(math.radians(45/2));tv=t/math.sqrt(1+(1280/1024)**2);th=tv*(1280/1024)
    mpp_x=2*altitude*th/1280;mpp_y=2*altitude*tv/1024
    latr=math.radians(lat);coslat=math.cos(latr)
    cx=R*math.radians(lon);cy=R*math.log(math.tan(math.pi/4+latr/2))
    angle=math.radians(heading);c=math.cos(angle);s=math.sin(angle)
    # Camera's horizontal axis points right of its heading; image rows point down.
    sx=mpp_x/coslat;sy=mpp_y/coslat
    M=np.array([[c*sx,-s*sy,cx-c*sx*639.5+s*sy*511.5],
                [-s*sx,-c*sy,cy+s*sx*639.5+c*sy*511.5],[0,0,1]],np.float64)
    return M,lat,lon,heading,altitude

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('images',type=Path);ap.add_argument('out',type=Path)
    ap.add_argument('--incident-id',required=True);ap.add_argument('--assignment-id',required=True);ap.add_argument('--title',required=True)
    ap.add_argument('--min-zoom',type=int,default=18);ap.add_argument('--max-zoom',type=int,default=21);args=ap.parse_args()
    if not 0 <= args.min_zoom <= args.max_zoom <= 23: raise SystemExit('Zoom must be an ordered range from 0 to 23.')
    args.out.mkdir(parents=True,exist_ok=True)
    t0=time.monotonic();photos=[]
    for p in sorted(args.images.glob('*_T.JPG')):
        meta=metadata(p)
        if meta is not None: photos.append((p,*meta))
    if not photos: raise SystemExit('No nadir thermal frames with required DJI metadata.')
    print(f'frames={len(photos)} opencv={cv2.__version__}',flush=True)
    all_tile_count=0;all_lon=[];all_lat=[]
    for path,M,*_ in photos:
        corners=M@np.array([[0,1279,1279,0],[0,0,1023,1023],[1,1,1,1]],np.float64)
        all_lon.extend(np.degrees(corners[0]/R).tolist())
        all_lat.extend(np.degrees(np.arctan(np.sinh(corners[1]/R))).tolist())
    bounds=[min(all_lon),min(all_lat),max(all_lon),max(all_lat)]
    yy,xx=np.mgrid[0:1024,0:1280]
    feather=np.minimum.reduce([xx+1,1279-xx,yy+1,1023-yy]).astype(np.float32)
    feather=np.clip(feather/128.0,0.03,1.0)
    for z in range(args.min_zoom,args.max_zoom+1):
        factor=256*(2**z)/(2*math.pi*R)
        by_tile={}
        for i,(path,M,*_) in enumerate(photos):
            P=np.array([[0,0,1],[1279,0,1],[1279,1023,1],[0,1023,1]],np.float64).T
            Q=M@P;px=(Q[0]+math.pi*R)*factor;py=(math.pi*R-Q[1])*factor
            tx0,tx1=math.floor(min(px)/256),math.floor(max(px)/256);ty0,ty1=math.floor(min(py)/256),math.floor(max(py)/256)
            for tx in range(tx0,tx1+1):
                for ty in range(ty0,ty1+1): by_tile.setdefault((tx,ty),[]).append(i)
        for (tx,ty),indices in by_tile.items():
            accum=np.zeros((256,256,3),np.float32);weights=np.zeros((256,256),np.float32)
            for i in indices:
                path,M,*_=photos[i]
                tileM=np.array([[factor,0,factor*math.pi*R-tx*256],[0,-factor,factor*math.pi*R-ty*256],[0,0,1]])@M
                with Image.open(path) as im: src=np.array(im.convert('RGB'))
                src=cv2.resize(src,(1280,1024),interpolation=cv2.INTER_LINEAR)
                warped=cv2.warpPerspective(src,tileM,(256,256),flags=cv2.INTER_LINEAR,borderMode=cv2.BORDER_CONSTANT)
                wm=cv2.warpPerspective(feather,tileM,(256,256),flags=cv2.INTER_LINEAR,borderMode=cv2.BORDER_CONSTANT)
                accum+=warped.astype(np.float32)*wm[:,:,None];weights+=wm
            valid=weights>0
            rgba=np.zeros((256,256,4),np.uint8)
            rgba[:,:,:3][valid]=np.clip(accum[valid]/weights[valid,None],0,255).astype(np.uint8)
            rgba[:,:,3][valid]=255
            target=args.out/str(z)/str(tx)/f'{ty}.png';target.parent.mkdir(parents=True,exist_ok=True)
            Image.fromarray(rgba).save(target,optimize=True)
        all_tile_count+=len(by_tile)
        print(f'z={z} tiles={len(by_tile)} elapsed={time.monotonic()-t0:.1f}s',flush=True)
    files=[]
    for path in sorted(args.out.rglob('*.png')):
        digest=hashlib.sha256(path.read_bytes()).hexdigest()
        files.append({'path':path.relative_to(args.out).as_posix(),'sizeBytes':path.stat().st_size,'sha256':digest})
    now=Image.open(photos[0][0]).getexif().get(306,'')
    layer_id=str(uuid.uuid4())
    layer={'type':'r2c_mapping_layer','version':1,'layerId':layer_id,'incidentId':args.incident_id,
        'assignmentId':args.assignment_id,'title':args.title,'profile':'thermal',
        'measurementType':'visual imagery; no calibrated temperature claim','processedAt':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),
        'captureTimeLocal':str(now),'captureTimeZone':'unknown','bounds':bounds,'boundsSource':'DJI RTK/LRF camera footprint',
        'tileRoot':'.','tileScheme':'xyz','minZoom':args.min_zoom,'maxZoom':args.max_zoom,
        'engine':{'name':'R2C direct-georeferenced mosaic prototype','version':'0.1','runtime':'OpenCV CPU'},
        'photoCount':len(photos),'quality':'Review alignment and image seams; assumes locally flat ground',
        'photogrammetric3D':False,'temperatureCalibrated':False,'files':files}
    (args.out/'layer.json').write_text(json.dumps(layer,indent=2)+'\n')
    archive=args.out/'layer.zip'
    with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_STORED) as zf:
        zf.write(args.out/'layer.json','layer.json')
        for item in files: zf.write(args.out/item['path'],item['path'])
    mutual_aid=args.out/'r2c-map-package.zip'
    tile_entries=[]
    mutual_aid_entries=[]
    for item in files:
        tile_path=Path(item['path'])
        z,x,y=map(int,tile_path.with_suffix('').parts[-3:])
        path=f'mapping-layers/{layer_id}/{z}/{x}/{y}.png'
        mutual_aid_entries.append((args.out/item['path'],path))
        tile_entries.append({'source':f'R2C-Mapping-{layer_id}','z':z,'x':x,'y':y,
            'expires_at_epoch_ms':0,'path':path})
    app_layer=dict(layer)
    app_layer['tileRoot']=f'mapping-layers/{layer_id}'
    app_layer['tileUrlTemplate']=f'http://127.0.0.1:8765/local-layer/{layer_id}/tiles/{{Z}}/{{X}}/{{Y}}.png'
    app_layer['files']=[{'path':path,'sizeBytes':(args.out/item['path']).stat().st_size,'sha256':item['sha256']}
        for item,(_,path) in zip(files,mutual_aid_entries)]
    package={'format':'rid2caltopo_mutual_aid_package','version':1,
        'generated':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),
        'package_name':f'{args.title} mapping layer','source_org':'','profile_enc':'',
        'aol_entries':[],'tile_entries':tile_entries,'dem_entries':[],'mapping_layers':[app_layer]}
    with zipfile.ZipFile(mutual_aid,'w',compression=zipfile.ZIP_STORED) as zf:
        for source,path in mutual_aid_entries: zf.write(source,path)
        zf.writestr('manifest.json',json.dumps(package,indent=2))
    report={'engine':'R2C direct-georeferenced thermal 2D prototype','engineVersion':'0.1','inputFrames':len(photos),'tileScheme':'xyz','minZoom':args.min_zoom,'maxZoom':args.max_zoom,'tiles':all_tile_count,'elapsedSeconds':round(time.monotonic()-t0,1),'bounds':bounds,'packageBytes':archive.stat().st_size,'r2cMapPackageBytes':mutual_aid.stat().st_size,'photogrammetric3D':False,'temperatureCalibrated':False,'placement':'DJI nadir camera metadata + thermal DFOV + onboard RTK/LRF centers','reviewRequired':True}
    (args.out/'prototype-report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))
if __name__=='__main__':main()
