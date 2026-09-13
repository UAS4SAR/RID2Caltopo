import contextlib
import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from types import SimpleNamespace
import laspy
import numpy as np
from pyproj import CRS, Transformer
from prepare_surface import prepare, R

class PreparationTests(unittest.TestCase):
    def test_seam_noise_masks_and_peak_preserving_coarsening(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp); n=142
            yy,xx=np.meshgrid(np.arange(n)-n/2+.5,np.arange(n)-n/2+.5,indexing='ij')
            lon=-121+np.degrees(xx.ravel()/(R*np.cos(np.radians(39))))
            lat=39+np.degrees(yy.ravel()/R)
            crs=CRS.from_user_input('EPSG:6350+5703')
            x,y=Transformer.from_crs(4326,6350,always_xy=True).transform(lon,lat)
            # Deliberate one-metre NoData hole survives every coarse validity mask.
            keep=np.ones(n*n,dtype=bool);keep[20*n+20]=False
            for name,mask in [('left',xx.ravel()<0),('right',xx.ravel()>=0)]:
                mask &= keep
                header=laspy.LasHeader(point_format=6,version='1.4');header.scales=[.0001,.0001,.0001];header.offsets=[x.min(),y.min(),0];header.add_crs(crs)
                points=laspy.LasData(header)
                extra=(name=='right')
                points.x=np.r_[x[mask],x[n*n//2+n//2:n*n//2+n//2+3]] if extra else x[mask]
                points.y=np.r_[y[mask],y[n*n//2+n//2:n*n//2+n//2+3]] if extra else y[mask]
                points.z=np.r_[np.full(mask.sum(),304.8),312.42,2000,3000] if extra else np.full(mask.sum(),304.8)
                points.classification=np.r_[np.full(mask.sum(),2),1,18,1] if extra else np.full(mask.sum(),2)
                if extra: points.withheld[-1]=1
                points.write(root/f'{name}.las')
            arrays=[]
            for spacing in (1,2):
                out=root/f'{spacing}.aol'
                a=SimpleNamespace(input=root/'left.las',additional_input=[root/'right.las'],output=out,center=[39,-121],extent=20,spacing=spacing,dataset='synthetic',version='1',survey_date='2020-01-01',geoid='GEOID12B',source_url='synthetic:test',wire_observations=None)
                with contextlib.redirect_stdout(io.StringIO()):prepare(a)
                with zipfile.ZipFile(out) as z:
                    m=json.loads(z.read('manifest.json'));self.assertEqual(len(m['sourceSHA256']),2)
                    arrays.append(np.frombuffer(z.read('surface.f32'),dtype='<f4').reshape(m['height'],m['width']))
            fine,coarse=arrays;blocks=fine.reshape(71,2,71,2)
            np.testing.assert_array_equal(np.isfinite(coarse),np.isfinite(blocks).all(axis=(1,3)))
            np.testing.assert_allclose(coarse,np.max(blocks,axis=(1,3)),equal_nan=True)
            self.assertAlmostEqual(float(np.nanmax(coarse)),312.42,places=3)
            self.assertEqual(np.isnan(fine).sum(),1)
            self.assertEqual(np.isnan(coarse).sum(),1)

if __name__=='__main__':unittest.main()
