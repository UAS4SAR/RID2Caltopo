"""One-time station updater packaged as a zipapp; does not update NodeODM or photos."""
import argparse
from datetime import datetime, timezone
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import sys
import tempfile
import uuid
import zipfile
import json


def ensure_ignore(project):
    project=Path(project).resolve()
    path=project/".gitignore"
    if not path.resolve().is_relative_to(project):
        raise ValueError("Project .gitignore points outside the project.")
    existing=path.read_text() if path.exists() else ""
    lines=["/.venv-mapping/","/tools/mapping/__pycache__/","/tools/mapping/tests/__pycache__/","/tools/mapping/dist/",
           "**/.dji-cloud-credentials.json*"]
    missing=[line for line in lines if line not in existing.splitlines()]
    if missing:
        with path.open("a") as stream:
            if existing and not existing.endswith("\n"):stream.write("\n")
            stream.write("\n# Local mapping runtime and generated packages\n"+"\n".join(missing)+"\n")


def apply_update(project, archive, data_root=None, runner=subprocess.run):
    project=Path(project).expanduser().resolve()
    data_root=Path(data_root or Path.home()/"R2C-Mapping")
    python=project/".venv-mapping/bin/python"
    if not python.is_file() or not os.access(python,os.X_OK):
        raise ValueError("Run Setup Mapping Station once in this project before applying the update.")
    for path in data_root.glob("*/state.json"):
        state=json.loads(path.read_text())
        if state.get("status") in {"submitting","processing","collecting-results","cancelling"} or state.get("publishState")=="uploading":
            raise ValueError("Finish or cancel the active mapping task before updating. No files were changed.")
    with zipfile.ZipFile(archive) as source:
        entries=[]
        for entry in source.infolist():
            if not entry.filename.startswith("tools/mapping/") or entry.is_dir():continue
            path=PurePosixPath(entry.filename)
            if any(part.startswith(".dji-cloud-credentials.json") for part in path.parts):
                raise ValueError("Station update archives must not contain DJI credentials.")
            if ".." in path.parts or "\\" in entry.filename or ((entry.external_attr>>16)&0o170000)==0o120000:
                raise ValueError("Invalid update archive path.")
            target=project/entry.filename
            if not target.resolve().is_relative_to(project):raise ValueError("Update path leaves the project.")
            entries.append((entry,target))
        if not entries or sum(entry.file_size for entry,_ in entries)>4*1024**2:
            raise ValueError("Invalid station update archive.")
        # Validate all members before stopping or replacing any source file.
        if source.testzip() is not None:raise ValueError("Update archive failed its integrity check.")
        backups=data_root/"update-backups"
        backups.mkdir(mode=0o700,parents=True,exist_ok=True)
        stamp=datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")+"-"+uuid.uuid4().hex[:8]
        backup=backups/(stamp+".zip")
        with zipfile.ZipFile(backup,"w",zipfile.ZIP_DEFLATED) as saved:
            for entry,target in entries:
                if target.is_file():saved.write(target,entry.filename)
            if (project/".gitignore").is_file():saved.write(project/".gitignore",".gitignore")
        backup.chmod(0o600)
        print("Existing station source backed up to "+str(backup),flush=True)
        runner([str(python),"tools/mapping/local_runtime.py","stop"],cwd=project,check=True)
        for entry,target in entries:
            target.parent.mkdir(parents=True,exist_ok=True)
            descriptor, temporary=tempfile.mkstemp(prefix=target.name+".update-",dir=target.parent)
            try:
                with os.fdopen(descriptor,"wb") as output,source.open(entry) as incoming:shutil.copyfileobj(incoming,output)
                os.chmod(temporary,0o755 if entry.filename.endswith((".sh",".command")) else 0o644)
                os.replace(temporary,target)
            finally:Path(temporary).unlink(missing_ok=True)
        ensure_ignore(project)
    print("Station source updated in "+str(project)+". Original photos, mission state and pairing were retained.",flush=True)
    runner([str(python),"tools/mapping/local_runtime.py","start"],cwd=project,check=True)


def main():
    parser=argparse.ArgumentParser(description="Update an existing Mac R2C mapping station")
    parser.add_argument("project",type=Path)
    args=parser.parse_args()
    try:apply_update(args.project,sys.argv[0])
    except (ValueError,OSError,subprocess.CalledProcessError) as error:
        print("Station update needs attention: "+str(error),file=sys.stderr)
        sys.exit(1)


if __name__=="__main__":main()
