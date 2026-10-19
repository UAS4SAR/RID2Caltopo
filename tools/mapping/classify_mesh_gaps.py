"""Read-only height/color/discontinuity classification of regional mesh gaps."""
import sys,json
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
from compare_saved_surfaces import Result,mercator
import numpy as np,shapely
import argparse
parser=argparse.ArgumentParser(description="Classify missing mesh area without altering the saved surface; counterfactual categories are not recommended fills.")
parser.add_argument('--input',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args();root=args.input;layer=json.loads((root/'layer.json').read_text());origin=mercator(layer['assignmentBoundary']).mean(0);scale=np.cos(np.deg2rad(np.array(layer['assignmentBoundary'])[:,1].mean()));poly=shapely.Polygon((mercator(layer['assignmentBoundary'])-origin)*scale);w,s,e,n=poly.bounds;step=.1;x,y=np.meshgrid(np.arange(w,e,step)+step/2,np.arange(s,n,step)+step/2);inside=shapely.contains_xy(poly,x,y)
actual=np.zeros(x.shape,bool);heightPossible=actual.copy();colorPossible=actual.copy();stepPossible=actual.copy();known=actual.copy()
for directory in sorted((root/'surface/regions').iterdir()):
 r=Result(directory,origin,scale,layer=layer);m=r.meta;zs,covered=r.sample(x,y);actual|=covered;known|=np.isfinite(zs)
 stride=round(r.mesh_spacing/m['cellMeters']);h=r.h[::stride,::stride];colored=np.zeros(h.shape,bool);cols=np.rint((r.positions[:,0]-m['gridX'])/r.mesh_spacing).astype(int);rows=np.rint((-r.positions[:,2]-m['gridY'])/r.mesh_spacing).astype(int);colored[rows,cols]=True
 u=((x-r.offset[0])/r.factor-m['gridX'])/r.mesh_spacing;v=((y-r.offset[1])/r.factor-m['gridY'])/r.mesh_spacing;qc=np.floor(u).astype(int);qr=np.floor(v).astype(int);u-=qc;v-=qr;valid=(qc>=0)&(qr>=0)&(qc<h.shape[1]-1)&(qr<h.shape[0]-1);qc=qc.clip(0,h.shape[1]-2);qr=qr.clip(0,h.shape[0]-2)
 heights=np.stack([h[qr,qc],h[qr,qc+1],h[qr+1,qc],h[qr+1,qc+1]]);colors=np.stack([colored[qr,qc],colored[qr,qc+1],colored[qr+1,qc],colored[qr+1,qc+1]])
 for ids,point in [([0,1,2],u+v<=1),([1,3,2],u+v>=1),([0,1,3],v<=u),([0,3,2],v>=u)]:
  hh=heights[ids];p=valid&point&np.isfinite(hh).all(0);heightPossible|=p;p=p&colors[ids].all(0);colorPossible|=p;stepPossible|=p&(np.max(hh,0)-np.min(hh,0)<=6)
gap=inside&~actual
report={'layerId':layer['layerId'],'samplingMeters':step,'knownHeightPct':float(100*(known&inside).sum()/inside.sum()),'meshCoveragePct':float(100*(actual&inside).sum()/inside.sum()),'missingMeshAreaM2':float(gap.sum()*step**2),'counterfactualGapCategoriesM2':{'noSupportedHeightTriangle':float((gap&~heightPossible).sum()*step**2),'heightsButNoColoredTriangle':float((gap&heightPossible&~colorPossible).sum()*step**2),'coloredButHeightJumpOver6m':float((gap&colorPossible&~stepPossible).sum()*step**2),'diagonalChoiceOrOther':float((gap&stepPossible).sum()*step**2)}}
print(json.dumps(report,indent=2));args.output.write_text(json.dumps(report,indent=2)+'\n')
