"""Local mapping station: python -m tools.mapping.server --root ... --bind ..."""
import argparse
import hashlib
import json
import mimetypes
import os
import secrets
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

from .model import json_bytes
from .nodeodm import Cancelled, NodeODM, TaskNotFound
from .store import CHUNK_LIMIT, Store, atomic_json
from .publisher import Publisher


class Processing:
    def __init__(self, store, engine):
        self.store, self.engine = store, engine
        self.lock = threading.RLock()
        self.running = None
        self.cancelled = threading.Event()

    def start(self, mission_id):
        with self.lock, self.store.mission_lock(mission_id):
            if self.running:
                raise ValueError("This station is already processing a mission.")
            state = self.store.read(mission_id)
            if state["status"] not in ("collecting", "failed", "cancelled", "interrupted"):
                raise ValueError("Mission cannot start from its current state.")
            if not self.engine:
                raise ValueError("Configure a local NodeODM engine when starting the station.")
            if state["uploads"]:
                raise ValueError("Finish all pending photo transfers before processing.")
            if len(state["photos"]) < 3:
                raise ValueError("Import at least three overlapping RGB photos.")
            if any(p["latitude"] is None for p in state["photos"]):
                raise ValueError("Every selected photo needs GPS metadata for this georeferenced workflow.")
            if state["status"] == "interrupted" and state.get("engineAddress") != self.engine.endpoint:
                raise ValueError("Reconnect to the original local engine to resume or cancel this task.")
            if state["status"] == "interrupted" and state.get("engineTaskId") and not state.get("engineCommitted"):
                raise ValueError("Engine photo submission was interrupted. Cancel this task first, then start again.")
            self.running = mission_id
            self.cancelled = threading.Event()
            resume = state["status"] == "interrupted" and state.get("engineTaskId")
            self.store.update(mission_id, status="processing" if resume else "submitting",
                phase="Reconnecting to local engine" if resume else "Preparing photos", error=None, etaSeconds=None,
                engineAddress=self.engine.endpoint, engineTaskId=state.get("engineTaskId") if resume else None)
            threading.Thread(target=self.run, args=(mission_id, bool(resume)), daemon=True).start()

    def cancel(self, mission_id):
        with self.lock:
            state = self.store.read(mission_id)
            if self.running == mission_id:
                self.store.update(mission_id, status="cancelling", phase="Stopping local engine", etaSeconds=None)
                self.cancelled.set()
                return
            if self.running:
                raise ValueError("Another mission is using this station.")
            if state["status"] == "interrupted" and state.get("engineTaskId"):
                if not self.engine or state.get("engineAddress") != self.engine.endpoint:
                    raise ValueError("Reconnect to the original engine before cancelling.")
                self.running = mission_id
                self.cancelled = threading.Event()
                self.cancelled.set()
                self.store.update(mission_id, status="cancelling", phase="Stopping interrupted engine task")
                threading.Thread(target=self.run, args=(mission_id, True), daemon=True).start()
                return
            if state["status"] in ("collecting", "failed", "cancelled"):
                self.store.update(mission_id, status="cancelled", phase="Cancelled; original photos retained", etaSeconds=None)
                return
            raise ValueError("There is no running task to cancel.")

    def run(self, mission_id, resume):
        task_id = None
        download = self.store.directory(mission_id) / "engine-result.zip"
        try:
            state = self.store.read(mission_id)
            def changed(phase, percent):
                if self.cancelled.is_set():
                    raise Cancelled()
                self.store.update(mission_id, phase=phase, progressPercent=round(percent, 1))
            def created(identifier, details):
                nonlocal task_id
                task_id = identifier
                self.store.update(mission_id, engineTaskId=identifier, engine=details,engineSubmissionStage="uploading")
            def committing():
                self.store.update(mission_id,engineSubmissionStage="committing")
            if resume:
                task_id = state["engineTaskId"]
            else:
                files = [self.store.directory(mission_id) / "incoming" / p["name"] for p in state["photos"]]
                task_id = self.engine.submit(state["mission"], files, self.cancelled, changed, created,committing)
                self.store.update(mission_id, engineCommitted=True,engineSubmissionStage="committed")
            self.store.update(mission_id, status="processing", phase="Aligning and reconstructing imagery")
            started = time.monotonic()
            while True:
                if self.cancelled.is_set():
                    raise Cancelled()
                info = self.engine.status(task_id)
                code = info.get("status", {}).get("code")
                if code == 40:
                    break
                if code in (30, 50):
                    raise ValueError("Local engine failed or cancelled the reconstruction. Check the engine report.")
                progress = max(0, min(100, float(info.get("progress", 0))))
                elapsed = time.monotonic()-started
                # A rough estimate is shown only after measurable engine progress.
                eta = round(elapsed*(100-progress)/progress) if progress >= 5 and elapsed >= 30 and not resume else None
                self.store.update(mission_id, phase="Queued at local engine" if code == 10 else "Aligning and reconstructing imagery",
                    progressPercent=round(15 + progress*.7, 1), etaSeconds=eta)
                self.cancelled.wait(2)
            if self.cancelled.is_set():
                raise Cancelled()
            self.store.update(mission_id, status="collecting-results", phase="Collecting local orthophoto and tiles", progressPercent=85, etaSeconds=None)
            self.engine.download(task_id, download, self.cancelled)
            manifest = self.store.install_results(mission_id, download, self.store.read(mission_id).get("engine", {}), self.cancelled)
            if self.cancelled.is_set():
                raise Cancelled()
            self.store.update(mission_id, status="ready", phase="2D layer ready for review", progressPercent=100, etaSeconds=0, layer=manifest)
        except Cancelled:
            try:
                if task_id:
                    try:
                        self.engine.cancel(task_id)
                        deadline = time.monotonic() + 120
                        while self.engine.status(task_id).get("status", {}).get("code") not in (30, 40, 50):
                            if time.monotonic() > deadline:
                                raise ValueError("Engine has not confirmed cancellation. Reconnect and cancel again.")
                            time.sleep(2)
                    except ValueError:
                        # Init uploads are not registered NodeODM tasks. No commit
                        # was sent in this state; confirm absence before releasing it.
                        if self.store.read(mission_id).get("engineSubmissionStage")!="uploading":raise
                        try:self.engine.status(task_id)
                        except TaskNotFound:pass
                        else:raise ValueError("Engine cancellation is not confirmed.")
                self.store.update(mission_id, status="cancelled", phase="Cancelled; original photos retained", etaSeconds=None)
            except Exception:
                self.store.update(mission_id, status="interrupted", phase="Cancellation could not be confirmed", error="Reconnect to the local engine, then resume or cancel.", etaSeconds=None)
        except Exception as error:
            # Retain the task identity when its state is unknown; never start a duplicate task.
            final = "failed"
            if task_id:
                try:
                    if self.engine.status(task_id).get("status", {}).get("code") not in (30, 40, 50):
                        final = "interrupted"
                except Exception:
                    final = "interrupted"
            message = str(error) if isinstance(error, ValueError) else "Local engine connection or result transfer failed. Check the engine and resume."
            self.store.update(mission_id, status=final, phase="Processing stopped", error=message, etaSeconds=None)
        finally:
            download.unlink(missing_ok=True)
            with self.lock:
                self.running = None


class Station(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 16

    def __init__(self, address, store, token, engine=None):
        self.store, self.token = store, token
        self.processing = Processing(store, engine)
        # Bound concurrent HTTP workers. Chunked uploads are intentionally sequential per browser.
        self.slots = threading.BoundedSemaphore(8)
        self.tickets = {}
        self.ticket_lock = threading.Lock()
        self.publisher = None
        super().__init__(address, Handler)

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def setup(self):
        super().setup()
        self.connection.settimeout(30)

    def log_message(self, format, *args):
        # Do not log credentials, query strings, photo filenames or incident names.
        pass

    def respond(self, body, status=200, content_type="application/json", filename=None):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Connection", "close")
        if filename:
            self.send_header("Content-Disposition", 'attachment; filename="%s"' % filename)
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def json(self, value, status=200):
        self.respond(json_bytes(value), status)

    def body(self, maximum=1024*1024):
        if self.headers.get("Transfer-Encoding"):
            raise ValueError("Send requests with Content-Length.")
        size = int(self.headers.get("Content-Length", "0"))
        if not 0 < size <= maximum:
            raise ValueError("Request body exceeds its size limit or is empty.")
        data = self.rfile.read(size)
        if len(data) != size:
            raise ValueError("Request was interrupted.")
        return json.loads(data)

    def authorized(self):
        supplied = self.headers.get("Authorization", "").removeprefix("Bearer ")
        if not secrets.compare_digest(supplied, self.server.token):
            self.json({"error":"Enter this station's pairing code."}, 401)
            return False
        # Reject cross-site browser writes. Native apps do not send Origin.
        origin = self.headers.get("Origin")
        if origin and urlsplit(origin).netloc != self.headers.get("Host"):
            self.json({"error":"Use the station's own page for browser operations."}, 403)
            return False
        return True

    def file(self, path, download=False):
        path = Path(path)
        with path.open("rb") as stream:
            # Export updates use atomic replacement. Bind headers to this open
            # inode so another operator saving parameters cannot truncate a download.
            self.send_response(200)
            self.send_header("Content-Type", mimetypes.guess_type(path.name)[0] or "application/octet-stream")
            self.send_header("Content-Length", str(os.fstat(stream.fileno()).st_size))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("Connection", "close")
            if download:
                self.send_header("Content-Disposition", 'attachment; filename="%s"' % path.name)
            self.end_headers()
            import shutil
            shutil.copyfileobj(stream, self.wfile, 1024*1024)
        self.close_connection = True

    def do_GET(self):
        self.handle_api("GET")

    def do_POST(self):
        self.handle_api("POST")

    def do_PUT(self):
        self.handle_api("PUT")

    def handle_api(self, method):
        try:
            path = urlsplit(self.path).path
            if method == "GET" and path in ("/", "/app.js", "/style.css"):
                self.file(Path(__file__).parent / "web" / ({"/":"index.html"}.get(path, path[1:])))
                return
            if method == "GET" and path.startswith("/download/"):
                key = path.removeprefix("/download/")
                with self.server.ticket_lock:
                    ticket = self.server.tickets.get(key)
                if not ticket or ticket[0] < time.monotonic():
                    self.json({"error":"Download link expired. Request a new download."}, 401)
                    return
                self.file(ticket[1], download=True)
                return
            if method == "GET" and path.startswith("/layer/"):
                parts=path.strip("/").split("/")
                if len(parts)<4:
                    raise FileNotFoundError()
                state=self.server.store.read(parts[1])
                if state["status"]!="ready" or not state.get("localLayerToken") or not secrets.compare_digest(parts[2],state["localLayerToken"]):
                    self.json({"error":"Layer link is invalid."},403);return
                if parts[3:]==["layer.json"]:
                    manifest=dict(state["layer"]);manifest.pop("files",None)
                    manifest["tileUrlTemplate"]="http://"+self.headers["Host"]+"/layer/"+parts[1]+"/"+parts[2]+"/tiles/{Z}/{X}/{Y}.png"
                    self.json(manifest);return
                if len(parts)==7 and parts[3]=="tiles" and all(v.isdigit() for v in parts[4:6]) and parts[6].endswith(".png") and parts[6][:-4].isdigit():
                    self.file(self.server.store.directory(parts[1])/"results"/state["layer"]["tileRoot"]/parts[4]/parts[5]/parts[6]);return
                raise FileNotFoundError()
            if not self.authorized():
                return
            if method=="GET" and path=="/api/station":
                self.json({"processingAvailable":self.server.processing.engine is not None,"sharingAvailable":self.server.publisher is not None,
                    "directPilotIntegrationAvailable":False,"sameDeviceReconstructionAvailable":False});return
            if path == "/api/missions":
                if method == "GET":
                    states = [self.server.store.read(d.name) for d in self.server.store.root.iterdir() if d.is_dir() and (d/"state.json").is_file()]
                    self.json(states)
                    return
                if method == "POST":
                    self.json(self.server.store.create(self.body()), 201)
                    return
            parts = path.strip("/").split("/")
            if len(parts) >= 3 and parts[:2] == ["api", "missions"]:
                mission_id = parts[2]
                directory = self.server.store.directory(mission_id)
                action = parts[3:]
                if method == "GET" and not action:
                    self.json(self.server.store.read(mission_id))
                    return
                if method == "PUT" and action == ["parameters"]:
                    self.json(self.server.store.parameters(mission_id, self.body()))
                    return
                if method == "PUT" and action == ["profile"]:
                    self.json(self.server.store.profile(mission_id, self.body()["profile"]))
                    return
                if method == "POST" and action == ["download-ticket"]:
                    name = self.body()["filename"]
                    if name not in ("mission.json", "boundary.kml", "mapping-template.kmz", "layer.zip"):
                        raise ValueError("Unknown download.")
                    if name == "layer.zip" and self.server.store.read(mission_id)["status"] != "ready":
                        raise ValueError("Layer is not ready.")
                    key = secrets.token_urlsafe(24)
                    with self.server.ticket_lock:
                        self.server.tickets = {k:v for k,v in self.server.tickets.items() if v[0] > time.monotonic()}
                        self.server.tickets[key] = (time.monotonic()+300, directory/name)
                    self.json({"url":"/download/"+key})
                    return
                if method == "POST" and action == ["layer-link"]:
                    state=self.server.store.read(mission_id)
                    if state["status"]!="ready":raise ValueError("Layer is not ready.")
                    token=state.get("localLayerToken") or secrets.token_urlsafe(24)
                    self.server.store.update(mission_id,localLayerToken=token)
                    self.json({"manifestUrl":"http://"+self.headers["Host"]+"/layer/"+mission_id+"/"+token+"/layer.json"});return
                if method == "POST" and action == ["publish"]:
                    if not self.server.publisher:raise ValueError("Organization sharing is not configured on this station.")
                    self.server.publisher.start(mission_id)
                    self.json(self.server.store.read(mission_id),202);return
                if method == "POST" and action == ["photos"]:
                    value = self.body()
                    self.json(self.server.store.begin_upload(mission_id, value["name"], value["sizeBytes"], value["sha256"]))
                    return
                if method=="POST" and action==["clear-pending"]:
                    self.json(self.server.store.clear_pending(mission_id));return
                if method == "PUT" and len(action) == 2 and action[0] == "uploads":
                    if self.headers.get("Transfer-Encoding"):
                        raise ValueError("Chunk requires Content-Length.")
                    size = int(self.headers.get("Content-Length", "0"))
                    if not 1 <= size <= CHUNK_LIMIT:
                        raise ValueError("Chunk exceeds 4 MiB.")
                    self.json(self.server.store.chunk(mission_id, action[1], int(self.headers.get("X-Upload-Offset", "-1")), self.rfile, size))
                    return
                if method == "POST" and action in (["process"], ["cancel"]):
                    if action == ["process"]:
                        self.server.processing.start(mission_id)
                    else:
                        if not self.server.publisher or not self.server.publisher.cancel(mission_id):
                            self.server.processing.cancel(mission_id)
                    self.json(self.server.store.read(mission_id), 202)
                    return
                if method == "GET" and len(action) == 2 and action[0] == "download" and action[1] in ("mission.json", "boundary.kml", "mapping-template.kmz", "layer.zip"):
                    if action[1] == "layer.zip" and self.server.store.read(mission_id)["status"] != "ready":
                        raise ValueError("Reviewable layer is not ready.")
                    self.file(directory / action[1], download=True)
                    return
                if method == "GET" and len(action) == 4 and action[0] == "tiles" and all(v.isdigit() for v in action[1:]):
                    state = self.server.store.read(mission_id)
                    if state["status"] != "ready":
                        raise ValueError("Layer is not ready.")
                    root = directory / "results" / state["layer"]["tileRoot"]
                    self.file(root / action[1] / action[2] / (action[3] + ".png"))
                    return
            self.json({"error":"Endpoint not found."}, 404)
        except FileNotFoundError:
            self.json({"error":"Mission or file not found."}, 404)
        except (ValueError, KeyError, TypeError) as error:
            self.json({"error":str(error)}, 400)
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            self.close_connection = True
        except Exception:
            self.json({"error":"Local operation failed. Check available disk space and station configuration."}, 500)


def main():
    parser = argparse.ArgumentParser(description="R2C local mapping station. Photos remain on the LAN.")
    parser.add_argument("--root", type=Path, required=True, help="Mission storage folder")
    parser.add_argument("--bind", default="127.0.0.1", help="Use a LAN address to allow access from another local device")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--nodeodm", default="", help="Local NodeODM endpoint, e.g. http://127.0.0.1:3000")
    args = parser.parse_args()
    token = os.environ.get("R2C_MAPPING_PAIRING_CODE") or secrets.token_urlsafe(12)
    if len(token) < 12:
        parser.error("Pairing code must contain at least 12 characters.")
    engine = NodeODM(args.nodeodm, os.environ.get("R2C_NODEODM_TOKEN", "")) if args.nodeodm else None
    server = Station((args.bind, args.port), Store(args.root), token, engine)
    tracker=os.environ.get("R2C_MAPPING_TRACKER_URL","")
    if tracker:
        server.publisher=Publisher(server.store,tracker,os.environ.get("R2C_MAPPING_ORGANIZATION",""),
            os.environ.get("R2C_MAPPING_TRACKER_TOKEN",""),os.environ.get("R2C_MAPPING_FUNCTIONALITY_RELEASE",""))
    # Parent launcher verifies the owned process without routing a self-check
    # through LAN proxies or macOS local-network client permissions.
    atomic_json(server.store.root / ".station-runtime.json", {"pid":os.getpid(),
        "address":args.bind,"port":server.server_port,"processingAvailable":engine is not None,
        "pairingHash":hashlib.sha256(token.encode()).hexdigest(),
        "bootNonce":os.environ.get("R2C_MAPPING_BOOT_NONCE","")})
    print("Local mapping station: http://%s:%s" % (args.bind, server.server_port), flush=True)
    print("Pairing code: " + token, flush=True)
    print("Pilot 2 route transfer is not implemented. This station is an optional local processing fallback.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
