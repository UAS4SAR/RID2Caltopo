"""Compare exported regional 2D/3D results inside their common assignment.
Example: --result 'Android before=/tmp/old' --result 'Android after=/tmp/new'.
Read-only; uses identical geographic sampling for every exported result.
"""
import argparse,json
from pathlib import Path
import numpy as np
import shapely
from PIL import Image
from compare_saved_surfaces import Result,mercator,gap_stats
import matplotlib.pyplot as plt
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--result',action='append',required=True)
p.add_argument('--output',type=Path,required=True)
a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
items=[(s.split('=',1)[0],Path(s.split('=',1)[1])) for s in a.result]
layers=[json.loads((r/'layer.json').read_text()) for _,r in items]
assert all(l['assignmentBoundary']==layers[0]['assignmentBoundary'] for l in layers)
origin=mercator(layers[0]['assignmentBoundary']).mean(0);scale=np.cos(np.deg2rad(np.array(layers[0]['assignmentBoundary'])[:,1].mean()))
poly=shapely.Polygon((mercator(layers[0]['assignmentBoundary'])-origin)*scale);w,s,e,n=poly.bounds
step=.1;x,y=np.meshgrid(np.arange(w,e,step)+step/2,np.arange(s,n,step)+step/2);inside=shapely.contains_xy(poly,x,y)
metrics=[];heights=[];masks=[];fig,axes=plt.subplots(1,len(items),figsize=(5*len(items),7),squeeze=False)
for (label,root),layer,ax in zip(items,layers,axes[0]):
 regions=[Result(d,origin,scale,layer=layer) for d in sorted((root/'surface/regions').iterdir()) if d.is_dir()]
 assert len(regions)==layer['regionCount'] and all(abs(r.mesh_spacing-.4)<1e-9 for r in regions)
 samples=[r.sample(x,y) for r in regions];coverage=np.logical_or.reduce([v[1] for v in samples]);z=np.stack([v[0] for v in samples]);count=np.isfinite(z).sum(0)
 height=np.divide(np.nansum(z,axis=0),count,out=np.full(x.shape,np.nan),where=count>0);heights.append(height);masks.append(coverage)
 zoom=layer['maxZoom'];world=40075016.68557849;unit=world/(256*2**zoom);px=np.floor((x/scale+origin[0]+world/2)/unit).astype(int);py=np.floor((world/2-y/scale-origin[1])/unit).astype(int)
 rgba=np.zeros(x.shape+(4,),dtype='u1')
 for tile in (root/str(zoom)).glob('*/*.png'):
  m=inside&(px//256==int(tile.parent.name))&(py//256==int(tile.stem))
  if m.any():rgba[m]=np.asarray(Image.open(tile).convert('RGBA'))[py[m]%256,px[m]%256]
 gaps=inside&~coverage
 report={'label':label,'layerId':layer['layerId'],'engine':layer['engine'],'processedAt':layer['processedAt'],'processingSeconds':layer.get('processingSeconds'),'selectedPhotoCount':layer.get('selectedPhotoCount'),'photoCount':layer['photoCount'],'regions':len(regions),'meshSpacingM':.4,'triangles':sum(len(r.indices) for r in regions),'meshCoveragePct':float(100*(inside&coverage).sum()/inside.sum()),'knownHeightPct':float(100*(inside&np.isfinite(height)).sum()/inside.sum()),'tileCoveragePct':float(100*(inside&(rgba[:,:,3]>0)).sum()/inside.sum()),'meshGaps':gap_stats(gaps,step),'tileGaps':gap_stats(inside&(rgba[:,:,3]==0),step)}
 metrics.append(report)
 background=rgba[:,:,:3].copy();background[~inside]=245;background[gaps]=[220,25,80]
 ax.imshow(background,origin='lower',extent=[w,e,s,n]);ax.set_title(f"{label}\n{report['meshCoveragePct']:.2f}% mesh coverage");ax.set_aspect('equal');ax.set_xlabel('East (m)');ax.set_ylabel('North (m)')
comparisons=[]
for i in range(len(items)):
 for j in range(i+1,len(items)):
  common=inside&np.isfinite(heights[i])&np.isfinite(heights[j]);delta=np.abs(heights[i][common]-heights[j][common]);comparisons.append({'a':items[i][0],'b':items[j][0],'medianAbsoluteHeightDifferenceM':float(np.median(delta)),'p95AbsoluteHeightDifferenceM':float(np.percentile(delta,95)),'meshCoverageDisagreementM2':float((inside&(masks[i]!=masks[j])).sum()*step**2)})
fig.suptitle('MapTest target · 0.4 m mesh · pink = missing mesh, not necessarily missing ground observations');fig.tight_layout();fig.savefig(a.output/'gap-comparison.png',dpi=150);plt.close(fig)
report={'sampleSpacingM':step,'targetAreaM2':poly.area,'results':metrics,'pairwise':comparisons}
(a.output/'metrics.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
