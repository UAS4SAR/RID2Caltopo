"""Compare retained exposure headings with the exact saved KMZ's gimbal actions."""
import argparse
import datetime as dt
import json
from pathlib import Path
import statistics
import xml.etree.ElementTree as ET
import zipfile
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt


def wrap(angle): return (angle+180)%360-180


def main():
    p=argparse.ArgumentParser()
    p.add_argument('--poses',type=Path,required=True)
    p.add_argument('--layer',type=Path,required=True)
    p.add_argument('--kmz',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
    selected=set(json.loads(a.layer.read_text())['originalSha256'])
    photos=sorted([p for p in json.loads(a.poses.read_text()) if p['digest'] in selected and abs(float(p['GimbalPitchDegree'])+60)<3],key=lambda p:p['UTCAtExposure'])
    groups=[]
    for photo in photos:
        if not groups or (dt.datetime.fromisoformat(photo['UTCAtExposure'])-dt.datetime.fromisoformat(groups[-1][-1]['UTCAtExposure'])).total_seconds()>10:groups.append([])
        groups[-1].append(photo)
    ns={'w':'http://www.dji.com/wpmz/1.0.6'}
    with zipfile.ZipFile(a.kmz) as z:root=ET.fromstring(z.read('wpmz/waylines.wpml'))
    commands=[]
    for action in root.findall('.//w:action',ns):
        pitch=action.find('.//w:gimbalPitchRotateAngle',ns)
        if pitch is not None and float(pitch.text)>-87:commands.append(float(action.find('.//w:gimbalYawRotateAngle',ns).text))
    assert len(commands)==len(groups)
    results=[]
    for i,(command,group) in enumerate(zip(commands,groups)):
        first=group[0];relative=[wrap(float(p['GimbalYawDegree'])-float(p['FlightYawDegree'])) for p in group]
        requested=wrap(command-float(first['FlightYawDegree']))
        results.append({'strip':i+1,'commandedGimbalYawNorthDegrees':command,'aircraftYawAtFirstExposureDegrees':float(first['FlightYawDegree']),
            'requiredRelativePanAtArrivalDegrees':requested,'exceeds65DegreeMechanicalRange':abs(requested)>65,
            'observedRelativePanMedianDegrees':statistics.median(relative),'photos':len(group),
            'correctedSequenceNominalRelativePanDegrees':0})
    report={'mechanicalPanRangeDegrees':[-65,65],'obliqueStrips':results,
        'conclusion':'The old sequence asks for unreachable relative gimbal pan at strip arrival, then changes aircraft yaw. Saturation/follow behavior fits the exposure record; firmware internals were not observed.'}
    (a.output/'heading-audit.json').write_text(json.dumps(report,indent=2))
    first=dt.datetime.fromisoformat(photos[0]['UTCAtExposure'])
    times=[(dt.datetime.fromisoformat(p['UTCAtExposure'])-first).total_seconds() for p in photos]
    relative=[wrap(float(p['GimbalYawDegree'])-float(p['FlightYawDegree'])) for p in photos]
    fig,axes=plt.subplots(2,1,figsize=(11,7),layout='constrained')
    axes[0].plot(times,[float(p['FlightYawDegree']) for p in photos],'.-',label='Aircraft yaw')
    axes[0].plot(times,[float(p['GimbalYawDegree']) for p in photos],'.-',label='Recorded camera yaw')
    axes[0].set(ylabel='North-referenced yaw (°)',title='Retained −60° exposures · camera follows an offset as aircraft turns')
    axes[0].legend()
    axes[1].plot(times,relative,'.-',color='#bd6848',label='Recorded camera pan relative to aircraft')
    axes[1].axhline(0,color='#246f60',linestyle='--',label='Corrected sequence nominal pan')
    axes[1].axhline(65,color='#777',linestyle=':');axes[1].axhline(-65,color='#777',linestyle=':')
    axes[1].set(xlabel='Seconds since first oblique exposure',ylabel='Relative pan (°)');axes[1].legend()
    fig.savefig(a.output/'heading-audit.png',dpi=170);plt.close(fig)
    print(json.dumps(report,indent=2))


if __name__=='__main__':main()
