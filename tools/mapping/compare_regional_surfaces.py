"""Read-only comparison of two regional exports, including partial mesh triangles.

Each input contains layer.json, surface/regions/<index>/, and optional XYZ PNGs.
"""
import argparse
import json
from pathlib import Path
import numpy as np
import shapely
import matplotlib.pyplot as plt
from matplotlib.colors import ListedColormap
from PIL import Image
from compare_saved_surfaces import Result, mercator, gap_stats


def compare(roots, output):
    layers=[json.loads((root/'layer.json').read_text()) for root in roots]
    boundary=layers[0]['assignmentBoundary']
    assert layers[1]['assignmentBoundary']==boundary
    assert set(layers[0]['originalSha256'])==set(layers[1]['originalSha256'])
    origin=mercator(boundary).mean(0)
    scale=np.cos(np.deg2rad(np.array(boundary)[:,1].mean()))
    ring=(mercator(boundary)-origin)*scale
    poly=shapely.Polygon(ring)
    west,south,east,north=poly.bounds
    step=.1
    x,y=np.meshgrid(np.arange(west,east,step)+step/2,np.arange(south,north,step)+step/2)
    inside=shapely.contains_xy(poly,x,y)
    metrics={'sampleSpacingM':step,'targetAreaM2':poly.area,'results':[]}
    fig,axes=plt.subplots(1,2,figsize=(14,6),layout='constrained')
    for j,(root,layer) in enumerate(zip(roots,layers)):
        rs=[]
        for d in sorted((root/'surface/regions').iterdir()):
            # Result consumes layer metadata without modifying the source export.
            r=Result(d,origin,scale,layer=layer);rs.append(r)
        samples=[r.sample(x,y) for r in rs]
        covered=np.logical_or.reduce([s[1] for s in samples])
        overlap=inside&np.logical_and.reduce([s[1] for s in samples])
        zs=np.stack([s[0] for s in samples]);valid=np.isfinite(zs).all(axis=0)
        deltas=np.full(x.shape,np.nan);deltas[valid]=np.ptp(zs[:,valid],axis=0)
        entry={'label':['before','after'][j],'layerId':layer['layerId'],
               'meshCoveragePct':100*(inside&covered).sum()/inside.sum(),
               'gaps':gap_stats(inside&~covered,step),
               'overlapMaxHeightDifferenceM':float(np.nanmax(deltas[overlap])),
               'vertices':sum(len(r.positions) for r in rs),
               'triangles':sum(len(r.indices) for r in rs)}
        z=int(layer['maxZoom']);unit=40075016.68557849/(256*2**z)
        px=np.floor((x/scale+origin[0]+40075016.68557849/2)/unit).astype(int)
        py=np.floor((40075016.68557849/2-y/scale-origin[1])/unit).astype(int)
        alpha=np.zeros(x.shape,dtype='u1')
        for tile in (root/str(z)).glob('*/*.png'):
            mask=inside&(px//256==int(tile.parent.name))&(py//256==int(tile.stem))
            if mask.any():alpha[mask]=np.asarray(Image.open(tile).convert('RGBA'))[py[mask]%256,px[mask]%256,3]
        entry['tileCoveragePct']=100*(inside&(alpha>0)).sum()/inside.sum()
        metrics['results'].append(entry)
        a=np.full(x.shape,np.nan);a[inside&covered]=0;a[inside&~covered]=1
        axes[j].pcolormesh(x,y,a,cmap=ListedColormap(['#b8d4c1','#df552f']),vmin=0,vmax=1,rasterized=True)
        axes[j].plot(*ring.T,color='#172730',lw=1);axes[j].set_aspect('equal')
        axes[j].set_title(f"{entry['label'].title()} · {entry['meshCoveragePct']:.1f}% mesh coverage")
        axes[j].set_xlabel('East (metres)');axes[j].set_ylabel('North (metres)')
    output.mkdir(parents=True,exist_ok=True)
    fig.suptitle('MapTest target · same photos and 0.4 m mesh · orange = missing mesh')
    fig.savefig(output/'coverage-comparison.png',dpi=160);plt.close(fig)
    (output/'metrics.json').write_text(json.dumps(metrics,indent=2))
    print(json.dumps(metrics,indent=2))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('before',type=Path);parser.add_argument('after',type=Path);parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();compare([args.before,args.after],args.output)
