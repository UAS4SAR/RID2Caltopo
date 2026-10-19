"""Streaming NodeODM adapter. The engine endpoint is configured by the Mac operator."""
from pathlib import Path
from urllib.parse import urlsplit

import requests
from requests_toolbelt.multipart.encoder import MultipartEncoder


class Cancelled(Exception):
    pass

class TaskNotFound(ValueError):
    pass


class CancellableUpload:
    def __init__(self, encoder, cancelled):
        self.encoder, self.cancelled = encoder, cancelled
        self.len = encoder.len

    def read(self, size=-1):
        if self.cancelled.is_set():
            raise Cancelled()
        return self.encoder.read(min(size, 1024 * 1024) if size >= 0 else 1024 * 1024)


class NodeODM:
    def __init__(self, endpoint, token=""):
        parts = urlsplit(endpoint)
        if parts.scheme not in ("http", "https") or not parts.hostname or parts.username or parts.password or parts.query or parts.fragment:
            raise ValueError("NodeODM endpoint must be an HTTP(S) server URL without credentials or query parameters.")
        self.endpoint, self.token = endpoint.rstrip("/"), token

    def request(self, method, path, **kwargs):
        response = requests.request(method, self.endpoint + path,
            params={"token": self.token} if self.token else {}, timeout=(10, 60), **kwargs)
        response.raise_for_status()
        return response

    def json(self, method, path, **kwargs):
        with self.request(method, path, **kwargs) as response:
            value = response.json()
        if isinstance(value, dict) and value.get("error"):
            if path.startswith("/task/") and path.endswith("/info") and "not found" in str(value["error"]).lower():
                raise TaskNotFound("NodeODM task is not registered.")
            raise ValueError("NodeODM: " + str(value["error"]))
        return value

    def submit(self, mission, files, cancelled, changed, task_created, task_committing=lambda:None):
        thermal = mission["profile"] == "thermal"
        if thermal:
            from .thermal import visual_inputs
            changed("Preparing visual thermal copies; originals retained", 0)
            files = visual_inputs(files, cancelled)
        info = self.json("GET", "/info")
        limit = info.get("maxImages")
        if limit and len(files) > limit:
            raise ValueError("This engine accepts at most %s images per task." % limit)
        available = {o["name"] for o in self.json("GET", "/options")}
        p = mission["parameters"]
        desired = {"fast-orthophoto": p["fastOrthophoto"], "skip-3dmodel": True,
            "orthophoto-resolution": p["orthophotoResolutionCm"], "rolling-shutter": not thermal,
            "tiles": True, "cog": True, "max-concurrency": 2}
        missing = set(desired) - available
        if missing:
            raise ValueError("Engine lacks required options: " + ", ".join(sorted(missing)))
        import json
        if cancelled.is_set():
            raise Cancelled()
        # NodeODM's init route uses multer().none(): it requires multipart form fields.
        response = self.json("POST", "/task/new/init", files={"name": (None,mission["title"]),
            "options": (None,json.dumps([{"name": k, "value": v} for k,v in desired.items()])),
            "outputs": (None,json.dumps(["odm_orthophoto/odm_orthophoto.tif", "orthophoto_tiles", "odm_report"])),
            "skipPostProcessing": (None,"true")})
        task_id = response["uuid"]
        task_created(task_id, {"engine":info.get("engine"), "engineVersion":info.get("engineVersion"), "options":desired,
            "inputMode":"thermal-visual-derivatives" if thermal else "original-rgb",
            "sourceTileScheme":"tms"})
        try:
            for index, path in enumerate(files):
                if cancelled.is_set():
                    raise Cancelled()
                changed("Sending photos to local engine", (index / len(files)) * 15)
                with Path(path).open("rb") as stream:
                    encoder = MultipartEncoder(fields={"images": (Path(path).name, stream, "image/jpeg")})
                    self.json("POST", "/task/new/upload/" + task_id,
                        data=CancellableUpload(encoder, cancelled), headers={"Content-Type":encoder.content_type})
            if cancelled.is_set():
                raise Cancelled()
            task_committing()
            self.json("POST", "/task/new/commit/" + task_id)
            return task_id
        except Exception:
            try:
                self.cancel(task_id)
            except Exception:
                pass
            raise

    def cancel(self, task_id):
        self.json("POST", "/task/cancel", json={"uuid":task_id})

    def status(self, task_id):
        return self.json("GET", "/task/" + task_id + "/info")

    def download(self, task_id, destination, cancelled, maximum_bytes=20 * 1024**3):
        size = 0
        try:
            with self.request("GET", "/task/" + task_id + "/download/all.zip", stream=True) as response, Path(destination).open("wb") as stream:
                for chunk in response.iter_content(1024 * 1024):
                    if cancelled.is_set():
                        raise Cancelled()
                    size += len(chunk)
                    if size > maximum_bytes:
                        raise ValueError("Engine result exceeds the configured download limit.")
                    stream.write(chunk)
        except Exception:
            Path(destination).unlink(missing_ok=True)
            raise
