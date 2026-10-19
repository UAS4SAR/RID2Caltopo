"""Durable local missions, bounded resumable uploads, and verified result packages."""
import hashlib
import json
import os
import re
import shutil
import threading
import uuid
import zipfile
from pathlib import Path, PurePosixPath

from PIL import Image
import rasterio
from rasterio.warp import transform_bounds

from .model import boundary_kml, json_bytes, now, template_kmz, validate_mission

ACTIVE = {"submitting", "processing", "collecting-results", "cancelling"}
PHOTO_LIMIT = 128 * 1024**2
CHUNK_LIMIT = 4 * 1024**2
MISSION_LIMIT = 50 * 1024**3


def atomic_json(path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_bytes(json_bytes(value))
    os.replace(temporary, path)

def atomic_bytes(path, value):
    temporary=path.with_suffix(path.suffix+".tmp")
    temporary.write_bytes(value)
    os.replace(temporary,path)


def photo_metadata(path, thermal=False):
    with Image.open(path) as image:
        # DJI originals can contain MPF auxiliary images inside their JPEG.
        # Pillow identifies these as MPO and opens the full-resolution primary
        # image first. Preserve the entire original; inspect only that primary.
        if image.format not in ("JPEG", "MPO"):
            raise ValueError("Only original JPEG photos are accepted.")
        image.seek(0)
        width, height = image.size
        if width*height > 60_000_000:
            raise ValueError("Photo exceeds the supported 60-megapixel limit.")
        supported = (width, height) in ((640,512),(1280,1024)) if thermal else 1000 <= width <= 12000 and 1000 <= height <= 12000
        if not supported:
            raise ValueError("Photo dimensions do not match the selected camera. Keep RGB and thermal photos in separate missions.")
        exif = image.getexif()
        capture = exif.get_ifd(0x8769).get(36867,exif.get(306,"")) if 0x8769 in exif else exif.get(306,"")
        gps = exif.get_ifd(0x8825) if 0x8825 in exif else {}
        def angle(values, ref):
            result = float(values[0]) + float(values[1])/60 + float(values[2])/3600
            return -result if ref in ("S", "W") else result
        latitude, longitude = None, None
        try:
            latitude = angle(gps[2], gps[1])
            longitude = angle(gps[4], gps[3])
            import math
            if not math.isfinite(latitude) or not math.isfinite(longitude) or not -90 <= latitude <= 90 or not -180 <= longitude <= 180:
                latitude, longitude = None, None
        except (KeyError, TypeError, ValueError, ZeroDivisionError):
            pass
        metadata = {"width":width, "height":height, "cameraModel":str(exif.get(272, "")),
                    "capturedAtLocal":str(capture), "latitude":latitude, "longitude":longitude}
        image.load()
    return metadata


class Store:
    def __init__(self, root):
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.locks = {}
        self.lock = threading.RLock()
        for directory in self.root.iterdir():
            if directory.is_dir() and (directory / "state.json").is_file():
                state = self.read(directory.name)
                if state["status"] in ACTIVE:
                    self.update(directory.name, status="interrupted", phase="Service restarted; resume or cancel the engine task", error=None)
                if state.get("publishState") == "uploading":
                    self.update(directory.name, publishState="interrupted", publishPhase="Service restarted; resume sharing")

    def directory(self, mission_id):
        try:
            normalized = str(uuid.UUID(mission_id))
        except (ValueError, TypeError):
            raise ValueError("Invalid mission identity.")
        return self.root / normalized

    def mission_lock(self, mission_id):
        with self.lock:
            return self.locks.setdefault(str(self.directory(mission_id)), threading.RLock())

    def read(self, mission_id):
        return json.loads((self.directory(mission_id) / "state.json").read_text())

    def update(self, mission_id, **values):
        with self.mission_lock(mission_id):
            state = self.read(mission_id)
            state.update(values, updatedAt=now())
            atomic_json(self.directory(mission_id) / "state.json", state)
            return state

    def create(self, value):
        mission = validate_mission(value)
        directory = self.directory(mission["missionId"])
        with self.mission_lock(mission["missionId"]):
            if directory.exists():
                existing = self.read(mission["missionId"])
                if existing["mission"] != mission:
                    raise ValueError("Mission identity already exists with different settings. Create a new mission.")
                return existing
            directory.mkdir()
            (directory / "incoming").mkdir()
            (directory / "uploads").mkdir()
            state = {"mission":mission, "status":"collecting", "phase":"Collecting original photos",
                "progressPercent":0, "etaSeconds":None, "photos":[], "uploads":{}, "engineTaskId":None,
                "error":None, "createdAt":now(), "updatedAt":now()}
            atomic_json(directory / "state.json", state)
            self.exports(mission)
            return state

    def exports(self, mission):
        directory = self.directory(mission["missionId"])
        atomic_json(directory / "mission.json", mission)
        atomic_bytes(directory / "boundary.kml",boundary_kml(mission))
        atomic_bytes(directory / "mapping-template.kmz",template_kmz(mission))

    def parameters(self, mission_id, parameters):
        with self.mission_lock(mission_id):
            state = self.read(mission_id)
            if state["status"] != "collecting":
                raise ValueError("Settings are fixed once processing starts. Create a new mission for a new flight.")
            value = dict(state["mission"], parameters=parameters)
            mission = validate_mission(value)
            self.exports(mission)
            return self.update(mission_id, mission=mission)

    def profile(self, mission_id, profile):
        from .model import DEFAULT_PARAMETERS, THERMAL_PARAMETERS
        with self.mission_lock(mission_id):
            state = self.read(mission_id)
            if state["status"] != "collecting" or state["photos"] or state["uploads"]:
                raise ValueError("Choose the camera before importing photos. Create a separate mission for the other camera.")
            defaults = THERMAL_PARAMETERS if profile == "thermal" else DEFAULT_PARAMETERS
            mission = validate_mission(dict(state["mission"], profile=profile, parameters=defaults))
            self.exports(mission)
            return self.update(mission_id, mission=mission)

    def clear_pending(self, mission_id):
        with self.mission_lock(mission_id):
            state=self.read(mission_id)
            if state["status"]!="collecting":raise ValueError("Stop processing before changing transfers.")
            for path in (self.directory(mission_id)/"uploads").glob("*.part"):path.unlink()
            return self.update(mission_id,uploads={})

    def begin_upload(self, mission_id, name, size, digest):
        if not isinstance(name, str) or len(name) > 160 or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_. -]*\.[jJ][pP][eE]?[gG]", name):
            raise ValueError("Use a JPEG filename without directory components.")
        if isinstance(size, bool) or not isinstance(size, int) or not 1 <= size <= PHOTO_LIMIT:
            raise ValueError("Photo exceeds the 128 MiB per-file limit.")
        if not isinstance(digest, str) or not re.fullmatch(r"[a-f0-9]{64}", digest):
            raise ValueError("Photo needs a SHA-256 checksum.")
        with self.mission_lock(mission_id):
            state = self.read(mission_id)
            if state["status"] != "collecting":
                raise ValueError("Photo collection is closed while this mission is processing.")
            for photo in state["photos"]:
                if photo["sha256"] == digest:
                    if photo["sizeBytes"] != size:
                        raise ValueError("Photo size conflicts with its checksum.")
                    return {"uploadId":digest, "offset":size, "complete":True}
                if photo["name"].casefold() == name.casefold():
                    raise ValueError("A different photo already uses this name. Rename the new photo before importing.")
            uploads = state["uploads"]
            for upload in uploads.values():
                if upload["name"].casefold() == name.casefold() and upload["sha256"] != digest:
                    raise ValueError("A different upload already uses this filename.")
            pending = sum(u["sizeBytes"] for u in uploads.values() if u["sha256"] != digest)
            if sum(p["sizeBytes"] for p in state["photos"]) + pending + size > MISSION_LIMIT:
                raise ValueError("Mission exceeds its 50 GiB photo budget.")
            if digest in uploads and (uploads[digest]["name"] != name or uploads[digest]["sizeBytes"] != size):
                raise ValueError("Upload metadata conflicts with the previous attempt.")
            uploads[digest] = {"name":name, "sizeBytes":size, "sha256":digest}
            part = self.directory(mission_id) / "uploads" / (digest + ".part")
            offset = part.stat().st_size if part.exists() else 0
            self.update(mission_id, uploads=uploads)
            return {"uploadId":digest, "offset":offset, "complete":False}

    def chunk(self, mission_id, upload_id, offset, stream, size):
        if not re.fullmatch(r"[a-f0-9]{64}", upload_id) or not 1 <= size <= CHUNK_LIMIT:
            raise ValueError("Invalid upload or chunk size.")
        with self.mission_lock(mission_id):
            state = self.read(mission_id)
            if state["status"] != "collecting":
                raise ValueError("Collection is closed.")
            metadata = state["uploads"].get(upload_id)
            if metadata is None:
                raise ValueError("Begin the upload before sending chunks.")
            directory = self.directory(mission_id)
            path = directory / "uploads" / (upload_id + ".part")
            existing = path.stat().st_size if path.exists() else 0
            if offset != existing or offset + size > metadata["sizeBytes"]:
                raise ValueError("Upload offset changed. Reopen the upload to resume.")
            # Roll back a partial chunk so the next request resumes at a known boundary.
            try:
                with path.open("ab") as output:
                    remaining = size
                    while remaining:
                        chunk = stream.read(min(remaining, 65536))
                        if not chunk:
                            raise ValueError("Photo transfer was interrupted.")
                        output.write(chunk)
                        remaining -= len(chunk)
            except Exception:
                with path.open("r+b") as output:
                    output.truncate(existing)
                raise
            offset += size
            if offset != metadata["sizeBytes"]:
                return {"offset":offset, "complete":False}
            checksum = hashlib.sha256()
            with path.open("rb") as photo:
                for chunk in iter(lambda:photo.read(1024*1024), b""):
                    checksum.update(chunk)
            try:
                if checksum.hexdigest() != upload_id:
                    raise ValueError("Photo checksum does not match. Retry from the original file.")
                details = photo_metadata(path, state["mission"]["profile"] == "thermal")
            except Exception:
                path.unlink(missing_ok=True)
                raise
            os.replace(path, directory / "incoming" / metadata["name"])
            state["photos"].append(dict(metadata, **details))
            del state["uploads"][upload_id]
            self.update(mission_id, photos=state["photos"], uploads=state["uploads"])
            return {"offset":offset, "complete":True}

    def install_results(self, mission_id, source_zip, engine, cancelled):
        directory = self.directory(mission_id)
        temporary = directory / "result-work"
        if temporary.exists():
            shutil.rmtree(temporary)
        temporary.mkdir()
        total = 0
        try:
            with zipfile.ZipFile(source_zip) as archive:
                if len(archive.infolist()) > 200000:
                    raise ValueError("Engine returned too many result files.")
                for member in archive.infolist():
                    if cancelled.is_set():
                        from .nodeodm import Cancelled
                        raise Cancelled()
                    path = PurePosixPath(member.filename)
                    if path.is_absolute() or ".." in path.parts or "\\" in member.filename or ((member.external_attr >> 16) & 0o170000) == 0o120000:
                        raise ValueError("Engine archive contains an unsafe path.")
                    if member.is_dir():
                        continue
                    if path.suffix.lower() not in (".tif", ".tiff", ".png", ".jpg", ".pdf", ".json"):
                        continue
                    # Keep only orthophotos, their tiles and the processing report.
                    if not any("orthophoto" in p.lower() or "report" in p.lower() for p in path.parts):
                        continue
                    total += member.file_size
                    if total > 20 * 1024**3:
                        raise ValueError("Expanded result exceeds 20 GiB.")
                    target = temporary.joinpath(*path.parts)
                    target.parent.mkdir(parents=True, exist_ok=True)
                    with archive.open(member) as incoming, target.open("wb") as outgoing:
                        shutil.copyfileobj(incoming, outgoing, 1024*1024)
            rasters = list(temporary.rglob("*.tif")) + list(temporary.rglob("*.tiff"))
            if not rasters:
                raise ValueError("Engine result has no orthophoto GeoTIFF.")
            # ODM versions use either tiles/ or orthophoto_tiles/. Discover the actual layout.
            tile_roots = []
            for image in temporary.rglob("*.png"):
                if len(image.parts) >= 4 and all(p.isdigit() for p in image.parts[-3:-1]) and image.stem.isdigit():
                    tile_roots.append(image.parent.parent.parent)
            if not tile_roots:
                raise ValueError("Engine result has no XYZ tiles. Enable ODM's tiles option.")
            tile_root = max(set(tile_roots), key=tile_roots.count)
            # ODM's gdal2tiles uses south-origin TMS. R2C and CalTopo use north-origin XYZ.
            xyz = temporary / "tiles"
            for tile in list(tile_root.glob("*/*/*.png")):
                if cancelled.is_set():
                    from .nodeodm import Cancelled
                    raise Cancelled()
                z, x, y = int(tile.parent.parent.name), int(tile.parent.name), int(tile.stem)
                if not 0 <= z <= 23 or not 0 <= x < 2**z or not 0 <= y < 2**z:
                    raise ValueError("Engine returned invalid tile coordinates.")
                target = xyz / str(z) / str(x) / (str(2**z-1-y)+".png")
                target.parent.mkdir(parents=True, exist_ok=True)
                os.replace(tile, target)
            shutil.rmtree(tile_root)
            tile_root = xyz
            zooms = sorted(int(p.name) for p in tile_root.iterdir() if p.is_dir() and p.name.isdigit())
            with rasterio.open(rasters[0]) as raster:
                if not raster.crs or raster.width < 1 or raster.height < 1:
                    raise ValueError("Orthophoto needs a valid coordinate reference system.")
                bounds = list(transform_bounds(raster.crs, "EPSG:4326", *raster.bounds, densify_pts=21))
                raster_info = {"crs":raster.crs.to_string(), "width":raster.width, "height":raster.height,
                    "pixelSize":list(raster.res)}
            state = self.read(mission_id)
            files = []
            for path in sorted(temporary.rglob("*")):
                if path.is_file():
                    if cancelled.is_set():
                        from .nodeodm import Cancelled
                        raise Cancelled()
                    digest = hashlib.sha256()
                    with path.open("rb") as stream:
                        for chunk in iter(lambda:stream.read(1024*1024), b""):
                            digest.update(chunk)
                    files.append({"path":path.relative_to(temporary).as_posix(), "sizeBytes":path.stat().st_size, "sha256":digest.hexdigest()})
            manifest = {"type":"r2c_mapping_layer", "version":1, "layerId":str(uuid.uuid4()),
                "missionId":mission_id, "incidentId":state["mission"]["incidentId"],
                "assignmentId":state["mission"]["assignmentId"], "title":state["mission"]["title"],
                "profile":state["mission"]["profile"], "measurementType":"visual-imagery; no calibrated temperature claim",
                "processedAt":now(), "captureTimesLocal":sorted({p["capturedAtLocal"] for p in state["photos"] if p["capturedAtLocal"]}),
                "captureTimeZone":"unknown", "requestedResolutionCm":state["mission"]["parameters"]["orthophotoResolutionCm"],
                "boundary":state["mission"]["feature"]["geometry"],
                "bounds":bounds, "boundsSource":"orthophoto", "raster":raster_info, "tileRoot":tile_root.relative_to(temporary).as_posix(),
                "tileScheme":"xyz", "minZoom":zooms[0], "maxZoom":zooms[-1],
                "orthophoto":rasters[0].relative_to(temporary).as_posix(), "engine":engine, "photoCount":len(state["photos"]),
                "quality":"Review alignment and transparent gaps before publishing", "files":files}
            atomic_json(temporary / "layer.json", manifest)
            result = directory / "results"
            if result.exists():
                shutil.rmtree(result)
            os.replace(temporary, result)
            bundle = directory / "layer.zip"
            with zipfile.ZipFile(bundle.with_suffix(".tmp"), "w", zipfile.ZIP_DEFLATED) as archive:
                for path in sorted(result.rglob("*")):
                    if path.is_file():
                        if cancelled.is_set():
                            from .nodeodm import Cancelled
                            raise Cancelled()
                        archive.write(path, path.relative_to(result).as_posix())
            os.replace(bundle.with_suffix(".tmp"), bundle)
            return manifest
        finally:
            if temporary.exists():
                shutil.rmtree(temporary)
