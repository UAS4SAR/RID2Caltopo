"""Resumable, explicit upload of a finished layer; aircraft originals stay local."""
import hashlib
import re
import threading
from urllib.parse import urlsplit

import requests

from .nodeodm import Cancelled


class Publisher:
    def __init__(self, store, url, organization, token, functionality_release=""):
        parts=urlsplit(url)
        if parts.scheme not in ("http","https") or not parts.hostname or parts.username or parts.query or parts.fragment or parts.path not in ("","/"):
            raise ValueError("Tracker URL must be a base HTTP(S) URL.")
        if parts.scheme!="https" and parts.hostname not in ("localhost","127.0.0.1"):
            raise ValueError("Use HTTPS for organization sharing.")
        if not re.fullmatch(r"[a-z0-9_-]{1,80}",organization) or not token:
            raise ValueError("Configure an organization designator and device token.")
        self.store=store;self.url=url.rstrip("/")+"/"+organization+"/api/v1/mapping-layers"
        self.headers={"X-SAR-Token":token}
        if functionality_release:self.headers["X-R2C-Functionality-Release"]=str(functionality_release)
        self.lock=threading.RLock();self.running=None;self.cancelled=threading.Event()

    def request(self, method, path, **kwargs):
        with requests.request(method,self.url+path,headers=self.headers,timeout=(10,600),allow_redirects=False,**kwargs) as response:
            if not 200<=response.status_code<300:
                raise ValueError("Tracker sharing failed (HTTP %s). Check station organization credentials and tracker configuration."%response.status_code)
            return response.json()

    def start(self, mission_id):
        with self.lock:
            if self.running:raise ValueError("A layer is already being shared.")
            state=self.store.read(mission_id)
            if state["status"]!="ready":raise ValueError("Process and review the layer first.")
            if state.get("publishState")=="published":
                self.running=mission_id;self.cancelled=threading.Event()
                self.store.update(mission_id,publishState="uploading",publishPhase="Refreshing shared links",publishProgress=0,publishError=None)
                threading.Thread(target=self.refresh,args=(mission_id,),daemon=True).start()
                return
            self.running=mission_id;self.cancelled=threading.Event()
            self.store.update(mission_id,publishState="uploading",publishPhase="Preparing finished layer",publishProgress=0,publishError=None)
            threading.Thread(target=self.run,args=(mission_id,),daemon=True).start()

    def cancel(self, mission_id):
        with self.lock:
            if self.running!=mission_id:return False
            self.store.update(mission_id,publishPhase="Stopping layer transfer")
            self.cancelled.set()
            return True

    def run(self, mission_id):
        try:
            state=self.store.read(mission_id);bundle=self.store.directory(mission_id)/"layer.zip"
            sha=hashlib.sha256()
            with bundle.open("rb") as stream:
                for block in iter(lambda:stream.read(4*1024**2),b""):
                    if self.cancelled.is_set():raise Cancelled()
                    sha.update(block)
            metadata={"layerId":state["layer"]["layerId"],"sha256":sha.hexdigest(),"sizeBytes":bundle.stat().st_size}
            record=self.request("POST","/uploads",json=metadata)
            chunk_bytes=record["chunkBytes"]
            if chunk_bytes!=4*1024**2 or record["uploadId"]!=sha.hexdigest():raise ValueError("Tracker returned an unsupported transfer contract.")
            missing=record["missingChunks"];count=(metadata["sizeBytes"]+chunk_bytes-1)//chunk_bytes
            if not isinstance(missing,list) or any(not isinstance(i,int) or isinstance(i,bool) or not 0<=i<count for i in missing):raise ValueError("Tracker returned invalid chunk offsets.")
            with bundle.open("rb") as stream:
                for index in missing:
                    if self.cancelled.is_set():raise Cancelled()
                    stream.seek(index*chunk_bytes);block=stream.read(chunk_bytes)
                    self.store.update(mission_id,publishPhase="Sharing finished layer (original photos stay local)",publishProgress=round((index/count)*95))
                    with requests.put(self.url+"/uploads/"+record["uploadId"]+"/"+str(index),data=block,
                        headers=dict(self.headers,**{"X-Chunk-SHA256":hashlib.sha256(block).hexdigest()}),timeout=(10,60),allow_redirects=False) as response:
                        if not 200<=response.status_code<300:raise ValueError("Layer chunk transfer failed. Retry Share layer to resume.")
            if self.cancelled.is_set():raise Cancelled()
            self.store.update(mission_id,publishPhase="Tracker is verifying the finished layer",publishProgress=95)
            result=self.request("POST","/uploads/"+record["uploadId"]+"/commit")
            self.store.update(mission_id,publishState="published",publishProgress=100,publishPhase="Layer shared with organization",sharedLayer=result)
        except Cancelled:
            self.store.update(mission_id,publishState="cancelled",publishPhase="Sharing cancelled; local layer retained")
        except Exception as error:
            self.store.update(mission_id,publishState="failed",publishPhase="Sharing stopped; local layer retained",publishError=str(error) if isinstance(error,ValueError) else "Tracker connection failed. Retry Share layer to resume.")
        finally:
            with self.lock:self.running=None

    def refresh(self, mission_id):
        try:
            from urllib.parse import urlencode
            state=self.store.read(mission_id)
            layers=self.request("GET","?"+urlencode({"incidentId":state["mission"]["incidentId"]}))
            result=next((layer for layer in layers if layer["manifest"]["layerId"]==state["layer"]["layerId"]),None)
            if not result:raise ValueError("Shared layer is unavailable on this organization tracker.")
            if self.cancelled.is_set():raise Cancelled()
            self.store.update(mission_id,publishState="published",publishProgress=100,publishPhase="Shared links refreshed",sharedLayer=result)
        except Cancelled:
            self.store.update(mission_id,publishState="cancelled",publishPhase="Link refresh cancelled")
        except Exception as error:
            self.store.update(mission_id,publishState="failed",publishPhase="Link refresh failed",publishError=str(error) if isinstance(error,ValueError) else "Cannot refresh shared links. Retry when connected.")
        finally:
            with self.lock:self.running=None
