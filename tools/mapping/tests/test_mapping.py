import copy
import hashlib
import io
import json
import tempfile
import threading
import time
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch
from xml.etree import ElementTree as ET

import numpy as np
import rasterio
from rasterio.transform import from_origin
from PIL import Image

from tools.mapping.model import KML, WPML, boundary_kml, estimated_height_meters, make_mission, template_kmz, validate_mission
from tools.mapping.store import Store, photo_metadata
from tools.mapping.nodeodm import Cancelled, NodeODM, TaskNotFound
from tools.mapping.thermal import visual_inputs
from tools.mapping.server import Processing, Station


def feature():
    return {"type":"Feature", "id":"assignment-1", "properties":{"class":"Assignment","title":"A & B"},
        "geometry":{"type":"Polygon","coordinates":[[[-121,39],[-120.999,39],[-120.999,39.001],[-121,39.001],[-121,39]]]}}


def jpeg(thermal=False):
    image = Image.new("RGB", (640,512) if thermal else (1600,1200), "gray")
    exif = Image.Exif(); exif[272] = "MATRICE4TD"; exif[306] = "2026:10:10 01:02:03"
    exif[0x8825] = {1:"N", 2:(39,0,0), 3:"W", 4:(121,0,0), 5:0, 6:100}
    stream = io.BytesIO(); image.save(stream,"JPEG",exif=exif)
    return stream.getvalue()


def engine_bundle(directory):
    tiff = directory / "fixture.tif"
    with rasterio.open(tiff,"w",driver="GTiff",width=32,height=32,count=3,dtype="uint8",crs="EPSG:4326",transform=from_origin(-121,39.001,.00003,.00003)) as raster:
        raster.write(np.ones((3,32,32),dtype="uint8")*128)
    tile = io.BytesIO(); Image.new("RGBA",(256,256),(200,80,20,255)).save(tile,"PNG")
    bundle = directory / "engine.zip"
    with zipfile.ZipFile(bundle,"w") as archive:
        archive.write(tiff,"odm_orthophoto/odm_orthophoto.tif")
        archive.writestr("orthophoto_tiles/15/5370/20244.png",tile.getvalue())
        archive.writestr("odm_report/report.json",'{"success":true}')
        archive.writestr("images/original.jpg",jpeg())
    return bundle


class MissionTests(unittest.TestCase):
    def test_separate_nadir_processor_rejects_controlled_oblique_profile(self):
        mission=make_mission(feature(),"MAP123")
        mission["captureProfile"]={"id":"nadir-plus-60"}
        with self.assertRaisesRegex(ValueError,"only supports nadir"):
            validate_mission(mission)

    def test_ground_resolution_derives_camera_specific_height(self):
        self.assertAlmostEqual(estimated_height_meters(6.1,"thermal"),60.0,delta=1.0)
        self.assertAlmostEqual(estimated_height_meters(2.0,"daylight-rgb"),58.0,delta=1.0)
        mission=make_mission(feature(),"MAP123")
        mission["profile"]="thermal"
        mission["parameters"]={"desiredGroundResolutionCmPerPixel":12.2}
        validated=validate_mission(mission)
        self.assertAlmostEqual(validated["parameters"]["heightMeters"],120.0,delta=2.0)
        self.assertAlmostEqual(validated["parameters"]["desiredGroundResolutionCmPerPixel"],12.2)

    def test_launch_location_is_validated_and_retained(self):
        mission=make_mission(feature(),"MAP123")
        mission["launchLocation"]={"latitude":39.0,"longitude":-121.0,"horizontalAccuracyMeters":8.0,"capturedAt":"2026-10-11T10:00:00Z"}
        self.assertEqual(validate_mission(mission)["launchLocation"],mission["launchLocation"])
        mission["launchLocation"]["latitude"]=91
        with self.assertRaises(ValueError): validate_mission(mission)

    def test_dji_mpf_jpeg_retains_all_original_frames_and_uses_primary_thermal(self):
        from tools.mapping.model import THERMAL_PARAMETERS
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); store=Store(root)
            value=make_mission(feature(),"MAP123")
            value.update(profile="thermal",parameters=dict(THERMAL_PARAMETERS))
            store.create(value)
            primary=Image.new("RGB",(1280,1024),"gray")
            auxiliary=Image.new("RGB",(960,768),"black")
            exif=Image.Exif();exif[272]="M4TD";exif[306]="2026:10:10 17:08:34"
            exif[0x8825]={1:"N",2:(39,0,0),3:"W",4:(121,0,0)}
            buffer=io.BytesIO()
            primary.save(buffer,"MPO",save_all=True,append_images=[auxiliary],exif=exif)
            original=buffer.getvalue();digest=hashlib.sha256(original).hexdigest()
            name="DJI_20261010170834_0500_T.JPG"
            store.begin_upload(value["missionId"],name,len(original),digest)
            result=store.chunk(value["missionId"],digest,0,io.BytesIO(original),len(original))
            self.assertTrue(result["complete"])
            path=store.directory(value["missionId"])/"incoming"/name
            self.assertEqual(path.read_bytes(),original)
            metadata=photo_metadata(path,thermal=True)
            self.assertEqual((metadata["width"],metadata["height"]),(1280,1024))
            self.assertEqual(metadata["latitude"],39)
            copies=visual_inputs([path],threading.Event())
            with Image.open(copies[0]) as image:
                self.assertEqual(image.format,"JPEG")
                self.assertEqual(image.size,(1280,1024))
                self.assertEqual(image.getexif().get_ifd(0x8825)[2],(39,0,0))
            self.assertEqual(path.read_bytes(),original)

    def test_engine_init_uses_multipart_and_requests_tiles_in_result(self):
        calls=[]
        class Adapter(NodeODM):
            def json(self,method,path,**kwargs):
                calls.append((path,kwargs))
                if path=="/info":return {"engine":"odm","engineVersion":"fixture","maxImages":10}
                if path=="/options":return [{"name":name} for name in ["fast-orthophoto","skip-3dmodel","orthophoto-resolution","rolling-shutter","tiles","cog","max-concurrency"]]
                if path=="/task/new/init":return {"uuid":"engine-1"}
                if "/upload/" in path:
                    upload=kwargs["data"]
                    while upload.read(1024):pass
                return {"success":True}
        with tempfile.TemporaryDirectory() as directory:
            file=Path(directory)/"1.JPG";file.write_bytes(jpeg())
            stages=[]
            Adapter("http://127.0.0.1:3000").submit(make_mission(feature(),"MAP123"),[file],threading.Event(),lambda *args:None,lambda *args:None,lambda:stages.append("committing"))
        init=next(kwargs for path,kwargs in calls if path=="/task/new/init")
        self.assertIn("files",init);self.assertNotIn("data",init)
        self.assertIn("orthophoto_tiles",json.loads(init["files"]["outputs"][1]))
        self.assertEqual(stages,["committing"])

    def test_boundary_template_and_thermal_lens(self):
        mission = make_mission(feature(),"MAP123")
        plain = ET.fromstring(boundary_kml(mission))
        self.assertEqual(plain.find(".//{%s}name" % KML).text,"A & B")
        with zipfile.ZipFile(io.BytesIO(template_kmz(mission))) as archive:
            self.assertEqual(archive.namelist(),["wpmz/template.kml"])
            root = ET.fromstring(archive.read("wpmz/template.kml"))
            self.assertEqual(root.find(".//{%s}templateType" % WPML).text,"mapping2d")
            self.assertEqual(root.find(".//{%s}orthoCameraOverlapH" % WPML).text,"80")
        mission["profile"]="thermal"; mission["parameters"]={}
        thermal=validate_mission(mission)
        self.assertEqual(thermal["parameters"]["forwardOverlapPercent"],85)
        self.assertEqual(ET.fromstring(boundary_kml(thermal,True)).find(".//{%s}imageFormat" % WPML).text,"ir")

    def test_reject_geometry_and_invalid_parameters(self):
        for geometry in [dict(type="MultiPolygon",coordinates=[]),dict(type="Polygon",coordinates=[feature()["geometry"]["coordinates"][0],[]]),dict(type="Polygon",coordinates=[[[0,0],[1,1],[0,1],[1,0],[0,0]]]),dict(type="Polygon",coordinates=[[[179,1],[-179,1],[-179,2],[179,1]]])]:
            value=feature();value["geometry"]=geometry
            with self.assertRaises(ValueError):make_mission(value,"MAP123")
        for values in [{"heightMeters":float("nan")},{"heightMeters":True},{"forwardOverlapPercent":80.5},{"sideOverlapPercent":1},{"unknown":1}]:
            mission=make_mission(feature(),"MAP123");mission["parameters"]=values
            with self.assertRaises(ValueError):validate_mission(mission)


class StoreTests(unittest.TestCase):
    def setUp(self):
        self.temporary=tempfile.TemporaryDirectory();self.root=Path(self.temporary.name)
        self.store=Store(self.root/"missions");self.mission=make_mission(feature(),"MAP123")
        self.mid=self.mission["missionId"];self.store.create(self.mission)
    def tearDown(self):self.temporary.cleanup()

    def test_resume_checksum_and_duplicates(self):
        data=jpeg();digest=hashlib.sha256(data).hexdigest();self.store.begin_upload(self.mid,"DJI_1.JPG",len(data),digest)
        self.store.chunk(self.mid,digest,0,io.BytesIO(data[:100]),100)
        restarted=Store(self.root/"missions")
        self.assertEqual(restarted.begin_upload(self.mid,"DJI_1.JPG",len(data),digest)["offset"],100)
        with self.assertRaises(ValueError):restarted.chunk(self.mid,digest,0,io.BytesIO(data),len(data))
        restarted.chunk(self.mid,digest,100,io.BytesIO(data[100:]),len(data)-100)
        self.assertEqual(restarted.read(self.mid)["photos"][0]["latitude"],39)
        self.assertTrue(restarted.begin_upload(self.mid,"DJI_1.JPG",len(data),digest)["complete"])
        with self.assertRaises(ValueError):restarted.begin_upload(self.mid,"DJI_1.JPG",len(data)+1,digest)
        self.assertEqual((restarted.directory(self.mid)/"incoming/DJI_1.JPG").read_bytes(),data)

    def test_partial_chunk_rolls_back_and_bad_checksum_retries(self):
        data=jpeg();digest=hashlib.sha256(data).hexdigest();self.store.begin_upload(self.mid,"DJI_1.JPG",len(data),digest)
        with self.assertRaises(ValueError):self.store.chunk(self.mid,digest,0,io.BytesIO(data[:10]),100)
        self.assertEqual(self.store.begin_upload(self.mid,"DJI_1.JPG",len(data),digest)["offset"],0)
        with self.assertRaises(ValueError):self.store.chunk(self.mid,digest,0,io.BytesIO(b"x"*len(data)),len(data))
        self.assertEqual(self.store.begin_upload(self.mid,"DJI_1.JPG",len(data),digest)["offset"],0)

    def test_profile_locks_and_thermal_originals(self):
        state=self.store.profile(self.mid,"thermal");self.assertFalse(state["mission"]["parameters"]["fastOrthophoto"])
        data=jpeg(True);digest=hashlib.sha256(data).hexdigest();self.store.begin_upload(self.mid,"IR.JPG",len(data),digest)
        self.store.chunk(self.mid,digest,0,io.BytesIO(data),len(data))
        original=self.store.directory(self.mid)/"incoming/IR.JPG"
        rendered=visual_inputs([original],threading.Event())[0]
        self.assertEqual(original.read_bytes(),data)
        self.assertEqual(photo_metadata(rendered,True)["latitude"],39)
        self.assertEqual(photo_metadata(rendered,True)["cameraModel"],"M4TD IR visual")
        with self.assertRaises(ValueError):photo_metadata(original)
        with self.assertRaises(ValueError):self.store.profile(self.mid,"daylight-rgb")

    def test_result_georeference_tms_conversion_and_no_originals(self):
        manifest=self.store.install_results(self.mid,engine_bundle(self.root),{"engineVersion":"fixture"},threading.Event())
        self.assertEqual(manifest["boundsSource"],"orthophoto")
        self.assertAlmostEqual(manifest["bounds"][0],-121)
        self.assertEqual(manifest["tileScheme"],"xyz")
        self.assertTrue((self.store.directory(self.mid)/"results/tiles/15/5370/12523.png").exists())
        with zipfile.ZipFile(self.store.directory(self.mid)/"layer.zip") as archive:
            self.assertNotIn("images/original.jpg",archive.namelist())
            for file in manifest["files"]:self.assertEqual(hashlib.sha256(archive.read(file["path"])).hexdigest(),file["sha256"])

    def test_unsafe_archive_and_cancellation(self):
        bundle=self.root/"unsafe.zip"
        with zipfile.ZipFile(bundle,"w") as archive:archive.writestr("../orthophoto.tif",b"x")
        with self.assertRaises(ValueError):self.store.install_results(self.mid,bundle,{},threading.Event())
        cancelled=threading.Event();cancelled.set()
        with self.assertRaises(Cancelled):self.store.install_results(self.mid,engine_bundle(self.root),{},cancelled)
        self.assertFalse((self.store.directory(self.mid)/"layer.zip").exists())

    def test_processing_completion_and_cancel_keep_originals(self):
        bundle=engine_bundle(self.root)
        photos=[]
        for index in range(3):
            path=self.store.directory(self.mid)/"incoming"/(str(index)+".JPG")
            path.write_bytes(jpeg());photos.append({"name":path.name,"latitude":39,"capturedAtLocal":"2026:10:10 01:02:03"})
        self.store.update(self.mid,photos=photos)
        class Engine:
            endpoint="http://127.0.0.1:3000"
            stopped=False
            complete=True
            entered=threading.Event()
            def submit(engine,mission,files,cancelled,changed,created,committing):
                created("engine-1",{"engineVersion":"fixture"});committing();engine.entered.set();return "engine-1"
            def status(engine,task):return {"status":{"code":50 if engine.stopped else 40 if engine.complete else 20},"progress":10}
            def download(engine,task,target,cancelled):target.write_bytes(bundle.read_bytes())
            def cancel(engine,task):engine.stopped=True
        engine=Engine();processing=Processing(self.store,engine);processing.start(self.mid)
        deadline=time.monotonic()+5
        while self.store.read(self.mid)["status"] not in ("ready","failed") and time.monotonic()<deadline:time.sleep(.02)
        self.assertEqual(self.store.read(self.mid)["status"],"ready")
        while processing.running and time.monotonic()<deadline:time.sleep(.01)
        engine.complete=False;engine.entered.clear()
        self.store.update(self.mid,status="collecting")
        processing.start(self.mid);self.assertTrue(engine.entered.wait(2));processing.cancel(self.mid)
        deadline=time.monotonic()+5
        while self.store.read(self.mid)["status"] not in ("cancelled","failed") and time.monotonic()<deadline:time.sleep(.02)
        self.assertEqual(self.store.read(self.mid)["status"],"cancelled")
        self.assertTrue(engine.stopped)
        self.assertEqual(len(list((self.store.directory(self.mid)/"incoming").glob("*.JPG"))),3)

    def test_uncommitted_engine_task_requires_cancel(self):
        self.store.update(self.mid,status="interrupted",engineAddress="http://127.0.0.1:3000",engineTaskId="engine-1",photos=[{"latitude":39}]*3)
        with self.assertRaisesRegex(ValueError,"Cancel this task first"):
            Processing(self.store,NodeODM("http://127.0.0.1:3000")).start(self.mid)

    def test_cancel_unregistered_upload_does_not_start_an_engine_job(self):
        class Engine:
            endpoint="http://127.0.0.1:3000"
            def cancel(self,task):raise ValueError("Task not found")
            def status(self,task):raise TaskNotFound("Task not registered")
        self.store.update(self.mid,status="interrupted",engineAddress=Engine.endpoint,engineTaskId="engine-1",engineSubmissionStage="uploading")
        processing=Processing(self.store,Engine());processing.cancel(self.mid)
        deadline=time.monotonic()+2
        while self.store.read(self.mid)["status"] not in ("cancelled","failed") and time.monotonic()<deadline:time.sleep(.01)
        self.assertEqual(self.store.read(self.mid)["status"],"cancelled")


class HTTPTests(unittest.TestCase):
    def test_auth_origin_upload_and_ticket(self):
        import requests
        with tempfile.TemporaryDirectory() as directory:
            server=Station(("127.0.0.1",0),Store(directory),"pairing-code-fixture")
            thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
            base="http://127.0.0.1:%s"%server.server_port;headers={"Authorization":"Bearer pairing-code-fixture"}
            try:
                self.assertEqual(requests.get(base+"/").status_code,200)
                self.assertEqual(requests.get(base+"/api/missions").status_code,401)
                self.assertEqual(requests.post(base+"/api/missions",headers=dict(headers,Origin="http://example.com"),json=make_mission(feature(),"MAP123")).status_code,403)
                mission=make_mission(feature(),"MAP123")
                self.assertEqual(requests.post(base+"/api/missions",headers=headers,json=mission).status_code,201)
                url=base+"/api/missions/"+mission["missionId"]
                ticket=requests.post(url+"/download-ticket",headers=headers,json={"filename":"boundary.kml"}).json()
                self.assertIn(b"<Polygon>",requests.get(base+ticket["url"]).content)
                self.assertEqual(requests.get(base+"/download/invalid").status_code,401)
                data=jpeg();digest=hashlib.sha256(data).hexdigest()
                self.assertEqual(requests.post(url+"/photos",headers=headers,json={"name":"1.JPG","sizeBytes":len(data),"sha256":digest}).status_code,200)
                result=requests.put(url+"/uploads/"+digest,headers=dict(headers,**{"X-Upload-Offset":"0"}),data=data)
                self.assertTrue(result.json()["complete"])
            finally:server.shutdown();server.server_close();thread.join()


if __name__=="__main__":unittest.main()
