# M5 MacBook Air setup

Target: Apple M5, 24 GB RAM, macOS 27.0.1. Setup selects a native ARM64 NodeODM
image and uses a dedicated `r2c-mapping` Colima VM: 4 CPUs, 12 GiB RAM and an
80 GiB sparse data disk. It does not change the active Docker context, mount host
folders into the VM, or install an automatic login service.

1. Use the MBA's **RID2Caltopo project checkout**, normally
   `~/Projects/RID2Caltopo`. The mapping station source and launchers belong in
   its `tools/mapping` directory. Ensure the new mapping files are present on
   the MBA; these working-tree changes have not yet been committed or pushed.
   The **R2C-Mapping-Field.zip** package is an alternative for a Mac without the
   updated checkout; extract it under `~/Projects`, not Documents. Do not run
   setup on a different Mac: the computer running it becomes the processor.
2. While online, open `tools/mapping/Setup Mapping Station.command` from that
   project folder, or run `bash tools/mapping/setup-mac.sh` from the project root.
   If Homebrew is missing, install it using its [official instructions](https://brew.sh),
   then run setup again. Administrator/Xcode Command Line Tools prompts must be
   handled on the MBA if macOS presents them.
3. Wait for **Mapping station is ready**. Setup installs Colima, Docker CLI and
   Python dependencies, pulls the pinned official NodeODM image, checks the engine
   API and required options, and opens the R2C station in the MBA's browser.
4. Put the MBA, tablet and controller on the same private LAN. The setup window
   shows the current station URL and pairing code; use these on the other devices.
   If macOS presents a local-network/firewall prompt, allow the requested station
   connection there.
5. Later, use **Start Mapping Station**. Start uses the installed image without
   pulling updates. After switching LANs, use Stop then Start to bind the new LAN
   address. Use **Stop Mapping Station** when finished.

The Python environment is created in the project as `.venv-mapping` and excluded
from Git. The original images, results, private pairing code and station log live under
`~/R2C-Mapping`. NodeODM working files live in its persistent VM volume. NodeODM
cleans completed/cancelled engine tasks after seven days and incomplete engine
uploads after two days; it does not remove the Mac's original photos or finished
local layers.

Setup reuses installed Homebrew tools instead of upgrading them. It finds Docker
in its formula directory, so an unrelated shell-completion symlink conflict does
not require forcing global links or changing another Docker installation.

NodeODM listens at `http://127.0.0.1:3000` on the MBA. Only the R2C station is
reachable from the incident LAN at port 8765. Its pairing code remains stable
across station restarts. First installation needs internet access and several
gigabytes of disk/download capacity; normal local processing does not use WAN.

The installer checks service readiness and required processing options. Actual
thermal alignment still needs the
[small-flight checklist](THERMAL-FLIGHT-CHECKLIST.md). If the required options
are missing, setup reports that instead of claiming the engine is ready.

## Updating an existing station

Run the provided `R2C-Mapping-Update.pyz` with the project's `.venv-mapping/bin/python`,
passing the project directory. The updater backs up the existing source under
`~/R2C-Mapping/update-backups`, refuses active processing/sharing jobs, stops only
the owned station/runtime, replaces the station files and starts it again. It
preserves originals, mission state and the pairing code. It does not pull a new
NodeODM image. The separate local processor is optional; it is not the target
same-device R2C architecture.

The Mac mini currently has the prerequisites and a stopped VM. NodeODM has not
been downloaded or started there. These notes do not claim a completed MBA
installation until the installer has run on that machine and its checks pass.
