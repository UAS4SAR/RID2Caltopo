"""Read-only gap-cause and exposure audit of retained mapping artifacts.

Requires the same dependencies as compare_saved_surfaces.py. No source images
are discarded. Capture times are measured separately from flight-controller logs.
"""
import argparse
import datetime as dt
import json
from pathlib import Path

import numpy as np
import shapely
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.colors import ListedColormap
from compare_saved_surfaces import Result, mercator


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--result', type=Path, required=True)
    parser.add_argument('--poses', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    mission = json.loads((args.result/'mission.json').read_text())
    coordinates = mission['feature']['geometry']['coordinates'][0]
    origin = mercator(coordinates).mean(axis=0)
    scale = np.cos(np.deg2rad(np.asarray(coordinates)[:, 1].mean()))
    boundary = (mercator(coordinates)-origin)*scale
    polygon = shapely.Polygon(boundary)
    result = Result(args.result, origin, scale)
    stride = round(result.mesh_spacing/result.meta['cellMeters'])
    h = result.h[::stride, ::stride]
    # Exporter requires all four sampled heights and colored vertices, then limits
    # the corner height range to 6 m. Distinguish these three sequential filters.
    z = np.stack([h[:-1, :-1], h[:-1, 1:], h[1:, :-1], h[1:, 1:]])
    finite = np.isfinite(z).all(axis=0)
    colored = np.zeros(h.shape, dtype=bool)
    cols = np.rint((result.positions[:, 0]-result.meta['gridX'])/result.mesh_spacing).astype(int)
    rows = np.rint((-result.positions[:, 2]-result.meta['gridY'])/result.mesh_spacing).astype(int)
    colored[rows, cols] = True
    color_valid = colored[:-1, :-1]&colored[:-1, 1:]&colored[1:, :-1]&colored[1:, 1:]
    jump = (np.max(z, axis=0)-np.min(z, axis=0)) > 6
    causes = np.zeros(finite.shape, dtype=np.uint8)
    causes[~finite] = 1
    causes[finite&~color_valid] = 2
    causes[finite&color_valid&jump] = 3
    actual = np.zeros(finite.shape, dtype=bool)
    actual[:result.quads.shape[0], :result.quads.shape[1]] = result.quads
    assert np.array_equal(actual, causes == 0), 'Cause replay must reproduce every exported quad'
    yy, xx = np.indices(finite.shape)
    x = (result.meta['gridX']+xx*result.mesh_spacing)*result.factor+result.offset[0]
    y = (result.meta['gridY']+yy*result.mesh_spacing)*result.factor+result.offset[1]
    step = result.mesh_spacing*result.factor
    intersects = (x+step>=polygon.bounds[0])&(x<=polygon.bounds[2])&(y+step>=polygon.bounds[1])&(y<=polygon.bounds[3])
    areas = np.zeros(finite.shape)
    areas[intersects] = shapely.area(shapely.intersection(shapely.box(x[intersects], y[intersects], x[intersects]+step, y[intersects]+step), polygon))
    names = ['supportedMesh', 'missingHeightAtMeshCorner', 'missingColorAtKnownCorner', 'heightStepOver6m']
    metrics = {'targetAreaM2': polygon.area, 'meshSpacingMeters': result.mesh_spacing,
               'gapCauses': {name: {'areaM2': float(areas[causes == i].sum()), 'targetPercent': float(areas[causes == i].sum()/polygon.area*100)} for i, name in enumerate(names)},
               'interpretation': 'Missing mesh corner heights includes isolated holes expanded by the four-corner rule; >6 m discontinuities are deliberately unbridged, not proof of missing photos.'}
    if args.poses:
        poses = sorted(json.loads(args.poses.read_text()), key=lambda p: p['UTCAtExposure'])
        selected = set(result.layer['originalSha256'])
        def time(p): return dt.datetime.fromisoformat(p['UTCAtExposure'])
        def xy(p): return (mercator([[float(p['GpsLongitude']), float(p['GpsLatitude'])]])[0]-origin)*scale
        duplicates = []
        for a, b in zip(poses, poses[1:]):
            distance = float(np.linalg.norm(xy(a)-xy(b)))
            angle = abs((float(b['GimbalYawDegree'])-float(a['GimbalYawDegree'])+180)%360-180)
            if distance < .8 and abs(float(a['GimbalPitchDegree'])-float(b['GimbalPitchDegree'])) < 3 and angle < 3:
                duplicates.append({'a': a['digest'], 'b': b['digest'], 'distanceMeters': distance, 'elapsedSeconds': (time(b)-time(a)).total_seconds(), 'bothCompleted': a['digest'] in selected and b['digest'] in selected})
        def summary(items):
            return {'photos': len(items), 'firstExposureUTC': items[0]['UTCAtExposure'], 'lastExposureUTC': items[-1]['UTCAtExposure'], 'exposureSpanSeconds': (time(items[-1])-time(items[0])).total_seconds()}
        capture = {'allReceived': summary(poses), 'completed': summary([p for p in poses if p['digest'] in selected]), 'outsideCompletion': summary([p for p in poses if p['digest'] not in selected]), 'nearRepeatedPairs': duplicates, 'passes': {}}
        for pitch in [-90, -60]:
            items = [p for p in poses if p['digest'] in selected and abs(float(p['GimbalPitchDegree'])-pitch) < 3]
            intervals = [(time(b)-time(a)).total_seconds() for a, b in zip(items, items[1:])]
            speed = np.array([np.hypot(float(p['FlightXSpeed']), float(p['FlightYSpeed'])) for p in items])
            s = summary(items)
            s.update({'stationaryExposuresUnder0_2mps': int((speed < .2).sum()), 'medianIntervalSeconds': float(np.median(intervals)), 'intervalsOver10Seconds': [t for t in intervals if t > 10]})
            # On east/west legs FlightYSpeed is the recorded east velocity. Check
            # settled, moving exposures only; nadir Euler yaw has no look-direction meaning.
            if pitch == -60:
                offsets = [abs((float(p['GimbalYawDegree'])-(90 if float(p['FlightYSpeed']) > 0 else -90)+180)%360-180) for p, v in zip(items, speed) if v >= 3 and abs(float(p['FlightYSpeed'])) >= 3]
                s['movingLookOffsetFromTravelDegrees'] = {'samples': len(offsets), 'minimum': min(offsets), 'median': float(np.median(offsets)), 'maximum': max(offsets)}
            capture['passes'][str(pitch)] = s
        metrics['capture'] = capture
    (args.output/'audit.json').write_text(json.dumps(metrics, indent=2))
    fig, ax = plt.subplots(figsize=(9, 6), layout='constrained')
    cmap = ListedColormap(['#287f73', '#ce4743', '#a670c2', '#e3ac47'])
    ax.imshow(np.ma.array(causes, mask=areas <= 0), origin='lower', extent=[x.min(), x.max()+step, y.min(), y.max()+step], interpolation='nearest', cmap=cmap, vmin=0, vmax=3)
    ax.plot(*boundary.T, color='#23313b', linewidth=1)
    ax.set(xlim=(polygon.bounds[0]-2, polygon.bounds[2]+2), ylim=(polygon.bounds[1]-2, polygon.bounds[3]+2), xlabel='East (m)', ylabel='North (m)', title='Target mesh gaps · red: missing corner height · purple: color · gold: >6 m step')
    ax.set_aspect('equal')
    fig.savefig(args.output/'gap-causes.png', dpi=180)
    plt.close(fig)
    print(json.dumps(metrics, indent=2))


if __name__ == '__main__':
    main()
