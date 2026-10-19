"""Portable, explicit Mac setup/start/stop. Always use the dedicated Docker context."""
import argparse
import hashlib
import ipaddress
import json
import os
import platform
import secrets
import shutil
import signal
import subprocess
import sys
import time
import webbrowser
from pathlib import Path
from urllib.parse import urlencode

import requests

REPOSITORY=Path(__file__).resolve().parents[2]
PROFILE="r2c-mapping"
CONTEXT="colima-r2c-mapping"
CONTAINER="r2c-nodeodm"
LABEL="org.ncssar.r2c.mapping"
ENGINE="http://127.0.0.1:3000"
PORT=8765
REQUIRED_OPTIONS={"fast-orthophoto","skip-3dmodel","orthophoto-resolution","rolling-shutter","tiles","cog","max-concurrency"}


def executable(name):
    formula_paths=[prefix+"/opt/"+name+"/bin/"+name for prefix in ("/opt/homebrew","/usr/local")]
    for candidate in (*formula_paths,shutil.which(name),"/opt/homebrew/bin/"+name,"/usr/local/bin/"+name):
        if candidate and Path(candidate).is_file() and os.access(candidate,os.X_OK):return candidate
    raise ValueError("Run Setup Mapping Station first; %s is missing."%name)


def command(arguments, check=True):
    environment=dict(os.environ)
    environment["PATH"]="/opt/homebrew/opt/docker/bin:/usr/local/opt/docker/bin:/opt/homebrew/bin:/usr/local/bin:"+environment.get("PATH","")
    result=subprocess.run(arguments,text=True,capture_output=True,env=environment)
    if check and result.returncode:
        raise ValueError((result.stderr or result.stdout or "Command failed").strip()[-3000:])
    return result


def resources(memory_bytes, logical_cpus):
    gib=memory_bytes//1024**3
    if gib<8:raise ValueError("Use a Mac with at least 8 GB RAM; 16 GB or more is preferable.")
    return {"cpus":min(4,max(2,logical_cpus//2)),"memoryGiB":min(12,gib//2),"diskGiB":80,
        "architecture":"aarch64" if platform.machine()=="arm64" else "x86_64"}


def host_resources():
    if platform.system()!="Darwin":raise ValueError("This launcher is for macOS.")
    major=int(command(["/usr/bin/sw_vers","-productVersion"]).stdout.split(".")[0])
    if major<13:raise ValueError("This setup requires macOS 13 or later.")
    memory=int(command(["/usr/sbin/sysctl","-n","hw.memsize"]).stdout.strip())
    cpus=int(command(["/usr/sbin/sysctl","-n","hw.logicalcpu"]).stdout.strip())
    return resources(memory,cpus)


def docker(*arguments,check=True):
    return command([executable("docker"),"--context",CONTEXT,*arguments],check=check)


def local_get(url, **kwargs):
    # These readiness checks target this Mac, never a WAN proxy.
    with requests.Session() as session:
        session.trust_env=False
        return session.get(url, **kwargs)


def image():
    value=(Path(__file__).parent/"nodeodm-image.txt").read_text().strip()
    prefix="opendronemap/nodeodm@sha256:"
    if not value.startswith(prefix) or len(value.removeprefix(prefix))!=64 or any(c not in "0123456789abcdef" for c in value.removeprefix(prefix)):
        raise ValueError("Invalid pinned NodeODM image.")
    return value


def private_address(value):
    try:
        address=ipaddress.IPv4Address(value)
        return any(address in ipaddress.IPv4Network(network) for network in ("10.0.0.0/8","172.16.0.0/12","192.168.0.0/16"))
    except ipaddress.AddressValueError:return False


def lan_address():
    route=command(["/sbin/route","-n","get","default"],check=False).stdout
    preferred=[line.split(":",1)[1].strip() for line in route.splitlines() if line.strip().startswith("interface:")]
    for interface in dict.fromkeys(preferred+["en0","en1","en2"]):
        address=command(["/usr/sbin/ipconfig","getifaddr",interface],check=False).stdout.strip()
        if private_address(address):return address
    raise ValueError("Connect this Mac to the incident LAN, then start the station again.")


def engine_start(allow_download):
    plan=host_resources()
    colima=executable("colima")
    if command([colima,"status",PROFILE],check=False).returncode:
        print("Starting local processing runtime…",flush=True)
        command([colima,"start",PROFILE,"--arch",plan["architecture"],"--vm-type","vz","--runtime","docker",
            "--cpu",str(plan["cpus"]),"--memory",str(plan["memoryGiB"]),"--disk",str(plan["diskGiB"]),
            "--mount","none","--ssh-config=false","--activate=false","--binfmt=false"])
    pinned=image()
    if docker("image","inspect",pinned,check=False).returncode:
        if not allow_download:raise ValueError("The processing image is missing. Run Setup Mapping Station while online.")
        print("Downloading the pinned official NodeODM image. This is a one-time download…",flush=True)
        command([executable("docker"),"--context",CONTEXT,"pull",pinned])
    existing=docker("container","inspect",CONTAINER,check=False)
    if existing.returncode:
        print("Creating local NodeODM service…",flush=True)
        docker("run","--detach","--name",CONTAINER,"--pull","never","--restart","unless-stopped",
            "--label",LABEL+"=true","--publish","127.0.0.1:3000:3000","--cpus",str(plan["cpus"]),
            "--memory",str(max(3,plan["memoryGiB"]-2))+"g","--shm-size","512m",
            "--volume","r2c-nodeodm-data:/var/www/data",pinned,
            "--parallel_queue_processing","1","--cleanup_tasks_after","10080","--cleanup_uploads_after","2880")
    else:
        container=json.loads(existing.stdout)[0]
        if container["Config"].get("Labels",{}).get(LABEL)!="true" or container["Config"]["Image"]!=pinned:
            raise ValueError("An unexpected container uses the mapping service name. Review it before changing it.")
        docker("start",CONTAINER)
    print("Checking NodeODM readiness…",flush=True)
    deadline=time.monotonic()+180
    while True:
        try:
            response=local_get(ENGINE+"/info",timeout=5);response.raise_for_status();info=response.json()
            response=local_get(ENGINE+"/options",timeout=10);response.raise_for_status()
            available={option["name"] for option in response.json()}
            missing=REQUIRED_OPTIONS-available
            if missing:raise ValueError("Engine lacks required options: "+", ".join(sorted(missing)))
            if info.get("engine")!="odm":raise ValueError("Unexpected reconstruction engine.")
            break
        except (requests.RequestException,json.JSONDecodeError):
            if time.monotonic()>deadline:raise ValueError("NodeODM did not become ready. Check the container logs.")
            time.sleep(2)
    return dict(plan,image=pinned,engine=info.get("engine"),engineVersion=info.get("engineVersion"),nodeVersion=info.get("version"))


def station_root():
    root=Path.home()/"R2C-Mapping"
    root.mkdir(mode=0o700,exist_ok=True)
    return root


def pairing(root):
    path=root/"station-pairing-code"
    if not path.exists():
        descriptor=os.open(path,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
        with os.fdopen(descriptor,"w") as stream:stream.write(secrets.token_urlsafe(12)+"\n")
    value=path.read_text().strip()
    if len(value)<12:raise ValueError("Station pairing code is invalid.")
    return value


def station_pid(root):
    try:
        pid=int((root/"station.pid").read_text())
        actual=command(["/bin/ps","-p",str(pid),"-o","command="],check=False)
        if actual.returncode==0 and "-m tools.mapping.server" in actual.stdout and str(root) in actual.stdout:return pid
    except (FileNotFoundError,ValueError):pass
    return None


def station_ready(root, pid, address, code, nonce=None):
    try:
        value=json.loads((root/".station-runtime.json").read_text())
        return (value["pid"]==pid and value["address"]==address and value["port"]==PORT and
            value["processingAvailable"] is True and
            secrets.compare_digest(value["pairingHash"],hashlib.sha256(code.encode()).hexdigest()) and
            (nonce is None or secrets.compare_digest(value.get("bootNonce",""),nonce)))
    except (FileNotFoundError,ValueError,KeyError,TypeError):return False


def start_station(root,open_browser=True):
    address=lan_address();code=pairing(root);url="http://%s:%s/"%(address,PORT)
    existing=station_pid(root)
    if existing and not station_ready(root,existing,address,code):
        try:
            response=local_get(url+"api/station",headers={"Authorization":"Bearer "+code},timeout=5)
            response.raise_for_status()
            if not response.json().get("processingAvailable"):raise ValueError("The running station has no processing engine.")
        except requests.RequestException:raise ValueError("The LAN address changed. Use Stop Mapping Station, then Start Mapping Station.")
    elif not existing:
        nonce=secrets.token_urlsafe(18)
        environment=dict(os.environ,R2C_MAPPING_PAIRING_CODE=code,R2C_MAPPING_BOOT_NONCE=nonce,PYTHONUNBUFFERED="1")
        log=root/"station.log"
        descriptor=os.open(log,os.O_CREAT|os.O_WRONLY|os.O_APPEND,0o600)
        with os.fdopen(descriptor,"ab") as output:
            process=subprocess.Popen([sys.executable,"-m","tools.mapping.server","--root",str(root),"--bind",address,
                "--port",str(PORT),"--nodeodm",ENGINE],cwd=REPOSITORY,env=environment,stdout=output,stderr=output,start_new_session=True)
        (root/"station.pid").write_text(str(process.pid))
        deadline=time.monotonic()+60
        last_error="No response received"
        while True:
            if process.poll() is not None:raise ValueError("Station could not start. See ~/R2C-Mapping/station.log.")
            if station_ready(root,process.pid,address,code,nonce):break
            try:
                response=local_get(url+"api/station",headers={"Authorization":"Bearer "+code},timeout=2)
                response.raise_for_status()
                if response.json().get("processingAvailable"):break
                last_error="Station responded but reported no processing engine"
            except requests.RequestException as error:
                last_error=type(error).__name__+": "+str(error)
            if time.monotonic()>deadline:raise ValueError("Station check failed at "+url+" ("+last_error+"). See ~/R2C-Mapping/station.log.")
            time.sleep(.5)
    owned_pid=existing or process.pid
    subprocess.Popen(["/usr/bin/caffeinate","-i","-w",str(owned_pid)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,start_new_session=True)
    print("\nMapping station is ready.")
    print("Tablet/controller URL: "+url)
    print("Pairing code: "+code)
    print("Original photos and local results: "+str(root))
    print("Use Stop Mapping Station when finished; it keeps your mission files.")
    if open_browser:webbrowser.open(url+"#"+urlencode({"pair":code}))


def main():
    parser=argparse.ArgumentParser(description="Local Mac mapping station setup/start/stop")
    parser.add_argument("action",choices=("setup","start","stop","plan"))
    parser.add_argument("--no-open",action="store_true")
    args=parser.parse_args()
    try:
        if args.action=="plan":print(json.dumps(host_resources(),indent=2));return
        if args.action=="stop":
            root=Path.home()/"R2C-Mapping";pid=station_pid(root)
            if pid:
                os.kill(pid,signal.SIGINT)
                deadline=time.monotonic()+15
                while station_pid(root) and time.monotonic()<deadline:time.sleep(.5)
                if station_pid(root):raise ValueError("Station has not stopped yet. Wait before stopping the engine.")
            command([executable("colima"),"stop",PROFILE],check=False)
            print("Mapping station stopped. Original photos and local results were retained.")
            return
        root=station_root()
        if args.action=="start" and not (root/"runtime.json").is_file():raise ValueError("Run Setup Mapping Station once while online before using Start.")
        receipt=engine_start(allow_download=args.action=="setup")
        (root/"runtime.json").write_text(json.dumps(receipt,indent=2)+"\n")
        start_station(root,open_browser=not args.no_open)
    except (ValueError,OSError) as error:
        print("Setup needs attention: "+str(error),file=sys.stderr);sys.exit(1)


if __name__=="__main__":main()
