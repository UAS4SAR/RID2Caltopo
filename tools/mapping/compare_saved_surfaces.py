"""Read-only comparison of exported DSMs/GLBs on a common captured assignment.

Inputs: directories containing mission.json, layer.json, surface.json,
surface.f32 and surface.glb. Requires numpy, scipy, shapely and matplotlib.
"""
import argparse
import hashlib
import json
import struct
from pathlib import Path

import numpy as np
import shapely
from scipy import ndimage
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.colors import ListedColormap

R = 6378137.0


def mercator(coordinates):
    p = np.asarray(coordinates, dtype=float)
    return np.column_stack((R*np.deg2rad(p[:, 0]), R*np.log(np.tan(np.pi/4+np.deg2rad(p[:, 1])/2))))


def glb(path):
    data = path.read_bytes()
    magic, version, length = struct.unpack_from("<III", data)
    assert magic == 0x46546c67 and version == 2 and length == len(data)
    size = struct.unpack_from("<I", data, 12)[0]
    model = json.loads(data[20:20+size])
    binary = 28+size
    primitive = model["meshes"][0]["primitives"][0]

    def attribute(index, dtype, components):
        a = model["accessors"][index]
        v = model["bufferViews"][a["bufferView"]]
        assert not v.get("byteStride")
        return np.frombuffer(data, dtype=dtype, offset=binary+v.get("byteOffset", 0)+a.get("byteOffset", 0), count=a["count"]*components).reshape(-1, components).copy()

    attrs = primitive["attributes"]
    return model, attribute(attrs["POSITION"], "<f4", 3), attribute(attrs["NORMAL"], "<f4", 3), attribute(attrs["COLOR_0"], "u1", 4), attribute(primitive["indices"], "<u4", 1).reshape(-1, 3)


class Result:
    def __init__(self, root, origin, scale, layer=None):
        self.root = root
        self.label = root.name
        self.meta = json.loads((root/"surface.json").read_text())
        self.layer = layer if layer is not None else json.loads((root/"layer.json").read_text())
        self.h = np.fromfile(root/"surface.f32", dtype="<f4").reshape(self.meta["height"], self.meta["width"])
        self.model, self.positions, self.normals, self.colors, self.indices = glb(root/"surface.glb")
        m = self.meta
        self.factor = scale/m["mercatorToGroundScale"]
        self.offset = (np.array([m["originMercatorX"], m["originMercatorY"]])-origin)*scale
        self.mesh_spacing = self.layer["surfaceModel"]["meshSpacingMeters"]
        cols = np.rint((self.positions[:, 0]-m["gridX"])/self.mesh_spacing).astype(int)
        rows = np.rint((-self.positions[:, 2]-m["gridY"])/self.mesh_spacing).astype(int)
        assert np.max(np.abs(cols*self.mesh_spacing+m["gridX"]-self.positions[:, 0])) < .0001
        assert np.max(np.abs(rows*self.mesh_spacing+m["gridY"]+self.positions[:, 2])) < .0001
        self.quads = np.zeros((int(rows.max()), int(cols.max())), dtype=bool)
        qr = np.min(rows[self.indices], axis=1)
        qc = np.min(cols[self.indices], axis=1)
        # Either diagonal and partially supported quads are valid exports.
        assert np.all(np.max(rows[self.indices], axis=1)-qr == 1)
        assert np.all(np.max(cols[self.indices], axis=1)-qc == 1)
        corners=(rows[self.indices]-qr[:,None])*2+cols[self.indices]-qc[:,None]
        corner_mask=np.sum(1 << corners,axis=1)
        bits=np.zeros(len(corner_mask),dtype=np.uint8)
        for mask,bit in [(7,1),(14,2),(11,4),(13,8)]:bits[corner_mask==mask]=bit
        assert np.all(bits>0)
        self.triangles=np.zeros(self.quads.shape,dtype=np.uint8)
        np.bitwise_or.at(self.triangles,(qr,qc),bits)
        self.quads=self.triangles>0

    def sample(self, x, y):
        m = self.meta
        local_x = (x-self.offset[0])/self.factor
        local_y = (y-self.offset[1])/self.factor
        col = np.rint((local_x-m["gridX"])/m["cellMeters"]).astype(int)
        row = np.rint((local_y-m["gridY"])/m["cellMeters"]).astype(int)
        valid = (col >= 0)&(col < m["width"])&(row >= 0)&(row < m["height"])
        heights = np.full(x.shape, np.nan)
        heights[valid] = self.h[row[valid], col[valid]]+m["originDjiAltitude"]
        qc = np.floor((local_x-m["gridX"])/self.mesh_spacing).astype(int)
        qr = np.floor((local_y-m["gridY"])/self.mesh_spacing).astype(int)
        in_mesh = (qc >= 0)&(qc < self.quads.shape[1])&(qr >= 0)&(qr < self.quads.shape[0])
        coverage = np.zeros(x.shape, dtype=bool)
        u=(local_x-m["gridX"])/self.mesh_spacing-qc
        v=(local_y-m["gridY"])/self.mesh_spacing-qr
        flags=np.zeros(x.shape,dtype=np.uint8)
        flags[in_mesh]=self.triangles[qr[in_mesh],qc[in_mesh]]
        coverage=((flags&1>0)&(u+v<=1))|((flags&2>0)&(u+v>=1))|((flags&4>0)&(v<=u))|((flags&8>0)&(v>=u))
        return heights, coverage

    def exact_mesh_area(self, polygon):
        points=self.positions[self.indices][:,:,[0,2]].astype(float)
        points[:,:,0]=points[:,:,0]*self.factor+self.offset[0]
        points[:,:,1]=-points[:,:,1]*self.factor+self.offset[1]
        return float(shapely.area(shapely.intersection(shapely.polygons(points),polygon)).sum())

    def renderer_files(self, directory):
        p = self.positions.copy()
        p[:, 0] = p[:, 0]*self.factor+self.offset[0]
        p[:, 2] = p[:, 2]*self.factor-self.offset[1]
        p[:, 1] += self.meta["originDjiAltitude"]-519.45
        for name, data in [("positions", p), ("normals", self.normals), ("colors", self.colors), ("indices", self.indices)]:
            (directory/(self.label+"-"+name+".bin")).write_bytes(data.tobytes())


def gap_stats(gaps, step):
    labels, count = ndimage.label(gaps)
    areas = np.bincount(labels.ravel())[1:]*step*step
    return {"connectedPatchesAtLeast1m2": int((areas >= 1).sum()), "largestPatchM2": float(areas.max(initial=0)), "missingAreaM2": float(gaps.sum()*step*step)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--labels", nargs="+", default=["nadir", "mixed", "all-received"])
    parser.add_argument("--titles", nargs="+")
    parser.add_argument("--comparison-title", default="Captured target · 0.4 m models")
    args = parser.parse_args()
    out = args.output
    out.mkdir(parents=True, exist_ok=True)
    labels = args.labels
    assert len(labels) in (2, 3) and len(set(labels)) == len(labels)
    default_titles = {"nadir": "Nadir only · noon", "mixed": "Nadir + −60° · completed", "all-received": "Nadir + −60° · all received"}
    titles = args.titles or [default_titles.get(label, label) for label in labels]
    assert len(titles) == len(labels)
    missions = [json.loads((args.input/n/"mission.json").read_text()) for n in labels]
    coordinates = missions[0]["feature"]["geometry"]["coordinates"][0]
    assert all(m["feature"]["geometry"]["coordinates"][0] == coordinates for m in missions)
    assert all(m["assignmentId"] == missions[0]["assignmentId"] for m in missions)
    origin = mercator(coordinates).mean(axis=0)
    scale = np.cos(np.deg2rad(np.asarray(coordinates)[:, 1].mean()))
    boundary = (mercator(coordinates)-origin)*scale
    polygon = shapely.Polygon(boundary)
    assert polygon.is_valid
    results = [Result(args.input/n, origin, scale) for n in labels]
    assert all(abs(r.mesh_spacing-.4) < 1e-9 for r in results)
    step = .2
    west, south, east, north = polygon.bounds
    x, y = np.meshgrid(np.arange(np.floor(west/step)*step, east+step, step)+step/2, np.arange(np.floor(south/step)*step, north+step, step)+step/2)
    inside = shapely.contains_xy(polygon, x, y)
    samples = [r.sample(x, y) for r in results]
    metrics = {"targetAreaM2": polygon.area, "assignmentId": missions[0]["assignmentId"], "commonGridMeters": step, "results": {}}
    for r, (z, mesh) in zip(results, samples):
        known = np.isfinite(z)&inside
        metrics["results"][r.label] = {"heightCoveragePct": float(known.sum()/inside.sum()*100), "meshCoveragePct": r.exact_mesh_area(polygon)/polygon.area*100,
            "heightGaps": gap_stats(inside&~known, step), "meshGaps": gap_stats(inside&~mesh, step), "captureStart": r.layer.get("captureTimeLocal"), "photoCount": r.layer["photoCount"],
            "sha256": {name: hashlib.sha256((r.root/name).read_bytes()).hexdigest() for name in ["surface.f32", "surface.glb"]}}
        r.renderer_files(out)
    for i in range(1, len(results)):
        a = np.isfinite(samples[0][0]); b = np.isfinite(samples[i][0]); ma = samples[0][1]; mb = samples[i][1]
        metrics[results[i].label+"VsNadir"] = {"heightGainedM2": float((inside&b&~a).sum()*step*step), "heightLostM2": float((inside&a&~b).sum()*step*step),
            "meshGainedM2": float((inside&mb&~ma).sum()*step*step), "meshLostM2": float((inside&ma&~mb).sum()*step*step)}
    np.savez_compressed(out/"common-grid.npz", x=x, y=y, inside=inside, boundary=boundary, heights=np.stack([s[0] for s in samples]), mesh=np.stack([s[1] for s in samples]))
    (out/"renderer.json").write_text(json.dumps({"heading": args.comparison_title, "boundary": boundary.tolist(), "results": [{"label": r.label, "title": title, "photoCount": r.layer["photoCount"], "vertices": len(r.positions), "indices": int(r.indices.size)} for r, title in zip(results, titles)], "bounds": list(polygon.bounds)}))
    (out/"metrics.json").write_text(json.dumps(metrics, indent=2))
    fig, axes = plt.subplots(2, len(results), figsize=(5*len(results), 9), layout="constrained", squeeze=False)
    extent = [x.min()-step/2, x.max()+step/2, y.min()-step/2, y.max()+step/2]
    cmap = ListedColormap(["#d34a45", "#2d837a"])
    for col, (r, (z, mesh)) in enumerate(zip(results, samples)):
        for row, covered in enumerate([np.isfinite(z), mesh]):
            image = np.ma.array(covered.astype(int), mask=~inside)
            axes[row, col].imshow(image, origin="lower", extent=extent, cmap=cmap, vmin=0, vmax=1, interpolation="nearest")
            axes[row, col].plot(*boundary.T, color="#333333", linewidth=1)
            pct = metrics["results"][r.label]["heightCoveragePct" if row == 0 else "meshCoveragePct"]
            axes[row, col].set_title(titles[col]+f"\n{'Heightfield' if row == 0 else 'Rendered mesh'}: {pct:.2f}% covered")
            axes[row, col].set_aspect("equal")
            axes[row, col].set_xlabel("East (m)")
            axes[row, col].set_ylabel("North (m)")
    fig.suptitle(args.comparison_title+" · red = gaps, green = support", fontsize=15)
    fig.savefig(out/"coverage.png", dpi=180)
    plt.close(fig)
    fig, axes = plt.subplots(1, 2, figsize=(12, 5), layout="constrained")
    change_cmap = ListedColormap(["#374851", "#d8e3e6", "#d97338", "#289980"])
    for i, (a, b) in enumerate([(np.isfinite(samples[0][0]), np.isfinite(samples[1][0])), (samples[0][1], samples[1][1])]):
        change = np.zeros(x.shape, dtype=int)
        change[a&b] = 1
        change[a&~b] = 2
        change[~a&b] = 3
        axes[i].imshow(np.ma.array(change, mask=~inside), origin="lower", extent=extent, cmap=change_cmap, vmin=0, vmax=3, interpolation="nearest")
        axes[i].plot(*boundary.T, color="#333333", linewidth=1)
        axes[i].set_title("Heightfield support" if i == 0 else "Rendered mesh support")
        axes[i].set_aspect("equal")
        axes[i].set_xlabel("East (m)")
        axes[i].set_ylabel("North (m)")
    fig.suptitle(args.comparison_title+" · green: gained · orange: lost · light: both · dark: neither", fontsize=12)
    fig.savefig(out/"support-change.png", dpi=180)
    plt.close(fig)
    print(json.dumps(metrics, indent=2))


if __name__ == "__main__":
    main()
