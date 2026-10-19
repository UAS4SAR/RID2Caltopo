"""Versioned mission contract and DJI mapping templates.

Template-only KMZs require Pilot 2 to generate/review execution waylines. They
are deliberately not presented as validated executable flight plans.
"""
import copy
import io
import json
import math
import uuid
import zipfile
from datetime import datetime, timezone
from xml.etree import ElementTree as ET

KML = "http://www.opengis.net/kml/2.2"
WPML = "http://www.dji.com/wpmz/1.0.6"
ET.register_namespace("", KML)
ET.register_namespace("wpml", WPML)
DEFAULT_PARAMETERS = {
    "heightMeters": 60, "speedMetersPerSecond": 4,
    "forwardOverlapPercent": 80, "sideOverlapPercent": 70,
    "directionDegrees": 0, "marginMeters": 10,
    "orthophotoResolutionCm": 5, "desiredGroundResolutionCmPerPixel": 2.0, "fastOrthophoto": True,
}
PARAMETER_RANGES = {
    "heightMeters": (5, 500), "speedMetersPerSecond": (1, 10),
    "forwardOverlapPercent": (60, 95), "sideOverlapPercent": (60, 95),
    "directionDegrees": (0, 359), "marginMeters": (0, 100),
    "orthophotoResolutionCm": (1, 100), "desiredGroundResolutionCmPerPixel": (0.2, 100),
}
THERMAL_PARAMETERS = dict(DEFAULT_PARAMETERS, speedMetersPerSecond=2,
    forwardOverlapPercent=85, sideOverlapPercent=80, orthophotoResolutionCm=15,
    desiredGroundResolutionCmPerPixel=6.1,
    fastOrthophoto=False)


def estimated_height_meters(gsd_cm_per_pixel, profile):
    """Approximate nadir, flat-ground flight height for native M4TD pixels."""
    if not _number(gsd_cm_per_pixel) or gsd_cm_per_pixel <= 0:
        raise ValueError("Ground resolution must be a positive number of centimeters per pixel.")
    if profile == "thermal":
        width, height, diagonal_fov = 640, 512, 45.0
    else:
        width, height, diagonal_fov = 4032, 3024, 82.0
    diagonal_pixels = math.hypot(width, height)
    horizontal_fov = 2 * math.atan(math.tan(math.radians(diagonal_fov / 2)) * width / diagonal_pixels)
    gsd_per_meter = (2 * math.tan(horizontal_fov / 2) / width) * 100
    return gsd_cm_per_pixel / gsd_per_meter


def now():
    return datetime.now(timezone.utc).isoformat()


def _number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def polygon(feature):
    geometry = feature.get("geometry", {})
    rings = geometry.get("coordinates")
    if geometry.get("type") != "Polygon" or not isinstance(rings, list) or len(rings) != 1:
        raise ValueError("Select a single polygon without holes. Split multipart or exclusion areas in CalTopo first.")
    if not isinstance(rings[0], list) or not 3 <= len(rings[0]) <= 2000:
        raise ValueError("The boundary must contain 3–2000 vertices.")
    points = []
    for point in rings[0]:
        if not isinstance(point, list) or len(point) < 2 or not all(_number(v) for v in point[:2]):
            raise ValueError("Boundary coordinates must be finite longitude/latitude values.")
        lon, lat = point[:2]
        if not -180 <= lon <= 180 or not -85 <= lat <= 85:
            raise ValueError("Boundary is outside supported longitude/latitude limits.")
        if not points or points[-1] != [lon, lat]:
            points.append([lon, lat])
    if points and points[-1] == points[0]:
        points.pop()
    if len(points) < 3 or len({tuple(p) for p in points}) != len(points):
        raise ValueError("Boundary needs at least three distinct, non-repeated vertices.")
    if max(p[0] for p in points) - min(p[0] for p in points) >= 180:
        raise ValueError("Antimeridian boundaries need separate missions.")
    def cross(a, b, c):
        return (b[0]-a[0])*(c[1]-a[1]) - (b[1]-a[1])*(c[0]-a[0])
    def on(a, b, c):
        return min(a[0], b[0]) <= c[0] <= max(a[0], b[0]) and min(a[1], b[1]) <= c[1] <= max(a[1], b[1])
    edges = list(zip(points, points[1:] + points[:1]))
    for i, (a, b) in enumerate(edges):
        for j in range(i+1, len(edges)):
            if j == i+1 or (i == 0 and j == len(edges)-1):
                continue
            c, d = edges[j]
            ab_c, ab_d, cd_a, cd_b = cross(a,b,c), cross(a,b,d), cross(c,d,a), cross(c,d,b)
            if (ab_c*ab_d < 0 and cd_a*cd_b < 0) or any((abs(v) < 1e-14 and on(x,y,z)) for v,x,y,z in [(ab_c,a,b,c),(ab_d,a,b,d),(cd_a,c,d,a),(cd_b,c,d,b)]):
                raise ValueError("Boundary crosses or touches itself. Repair it in CalTopo.")
    area = sum(a[0]*b[1]-b[0]*a[1] for a,b in edges)
    if abs(area) < 1e-12:
        raise ValueError("Boundary has no usable area.")
    if area < 0:
        points.reverse()
    return points + [points[0]]


def validate_mission(value):
    if not isinstance(value, dict) or value.get("type") != "r2c_mapping_mission" or value.get("version") != 1:
        raise ValueError("Select an R2C mapping mission JSON file (version 1).")
    capture = value.get("captureProfile")
    if capture is not None and (not isinstance(capture, dict) or capture.get("id") != "nadir"):
        raise ValueError("This separate processor only supports nadir missions; use the tablet controlled-capture route.")
    result = copy.deepcopy(value)
    try:
        result["missionId"] = str(uuid.UUID(str(value["missionId"])))
    except (KeyError, ValueError, TypeError):
        raise ValueError("Mission identity must be a UUID.")
    for field in ("incidentId", "assignmentId", "title"):
        if not isinstance(value.get(field), str) or not value[field].strip() or len(value[field]) > 240:
            raise ValueError("Mission needs a valid " + field + ".")
    feature = value.get("feature")
    if not isinstance(feature, dict) or str(feature.get("id")) != value["assignmentId"]:
        raise ValueError("Assignment identity does not match its boundary.")
    polygon(feature)
    # Retain the original geometry, including its order. Export normalization is separate.
    result["feature"] = {"type": "Feature", "id": value["assignmentId"],
                         "properties": {"class": "Assignment", "title": value["title"]},
                         "geometry": copy.deepcopy(feature["geometry"])}
    launch = value.get("launchLocation")
    if launch is not None:
        if not isinstance(launch, dict) or not all(_number(launch.get(field)) for field in
                ("latitude", "longitude", "horizontalAccuracyMeters")):
            raise ValueError("Launch location needs finite coordinates and accuracy.")
        if not -90 <= launch["latitude"] <= 90 or not -180 <= launch["longitude"] <= 180 or launch["horizontalAccuracyMeters"] < 0:
            raise ValueError("Launch location is outside supported coordinate limits.")
        captured = launch.get("capturedAt")
        if not isinstance(captured, str) or len(captured) > 64:
            raise ValueError("Launch location needs a capture timestamp.")
        try:
            if datetime.fromisoformat(captured.replace("Z", "+00:00")).tzinfo is None:
                raise ValueError()
        except ValueError:
            raise ValueError("Launch location timestamp needs an ISO-8601 timezone.")
    profile = value.get("profile", "daylight-rgb")
    if profile not in ("daylight-rgb", "thermal"):
        raise ValueError("Choose Daylight RGB or Thermal / low light.")
    result["profile"] = profile
    parameters = value.get("parameters", {})
    if not isinstance(parameters, dict) or set(parameters) - set(DEFAULT_PARAMETERS):
        raise ValueError("Unsupported mapping parameters.")
    result["parameters"] = dict(THERMAL_PARAMETERS if profile == "thermal" else DEFAULT_PARAMETERS, **parameters)
    if "desiredGroundResolutionCmPerPixel" in parameters and "heightMeters" not in parameters:
        result["parameters"]["heightMeters"] = estimated_height_meters(
            result["parameters"]["desiredGroundResolutionCmPerPixel"], profile)
    for field, (low, high) in PARAMETER_RANGES.items():
        v = result["parameters"][field]
        if not _number(v) or not low <= v <= high:
            raise ValueError("%s must be between %s and %s." % (field, low, high))
    for field in ("forwardOverlapPercent", "sideOverlapPercent", "directionDegrees", "marginMeters"):
        if result["parameters"][field] != int(result["parameters"][field]):
            raise ValueError(field + " must be a whole number.")
    if not isinstance(result["parameters"]["fastOrthophoto"], bool):
        raise ValueError("fastOrthophoto must be a boolean.")
    result["aircraft"] = "Matrice 4TD"
    result["camera"] = "thermal-ir" if profile == "thermal" else "wide-rgb"
    result["gimbalPitchDegrees"] = -90
    result["heightReference"] = "relativeToStartPoint"
    created = value.get("createdAt") or now()
    if not isinstance(created, str) or len(created) > 64:
        raise ValueError("Invalid mission timestamp.")
    try:
        if datetime.fromisoformat(created.replace("Z", "+00:00")).tzinfo is None:
            raise ValueError()
    except ValueError:
        raise ValueError("Mission timestamp needs an ISO-8601 timezone.")
    result["createdAt"] = created
    return result


def make_mission(feature, incident_id, title=None):
    return validate_mission({"type": "r2c_mapping_mission", "version": 1,
        "missionId": str(uuid.uuid4()), "incidentId": incident_id,
        "assignmentId": str(feature.get("id", "")),
        "title": title or feature.get("properties", {}).get("title", "Assignment"),
        "feature": feature, "parameters": DEFAULT_PARAMETERS})


def _element(parent, namespace, name, value=None):
    child = ET.SubElement(parent, "{%s}%s" % (namespace, name))
    if value is not None:
        child.text = str(value)
    return child


def boundary_kml(mission, template=False):
    mission = validate_mission(mission)
    p = mission["parameters"]
    root = ET.Element("{%s}kml" % KML)
    document = _element(root, KML, "Document")
    _element(document, KML, "name", mission["title"])
    _element(document, KML, "description", "R2C assignment boundary. Review all mission settings in Pilot 2 before flight.")
    parent = document
    if template:
        _element(document, WPML, "author", "RID2Caltopo")
        config = _element(document, WPML, "missionConfig")
        for key, val in {"flyToWaylineMode":"safely", "finishAction":"goHome", "exitOnRCLost":"executeLostAction", "executeRCLostAction":"goBack", "takeOffSecurityHeight":20, "globalTransitionalSpeed":p["speedMetersPerSecond"]}.items():
            _element(config, WPML, key, val)
        drone = _element(config, WPML, "droneInfo")
        _element(drone, WPML, "droneEnumValue", 100)
        _element(drone, WPML, "droneSubEnumValue", 1)
        payload = _element(config, WPML, "payloadInfo")
        _element(payload, WPML, "payloadEnumValue", 99)
        _element(payload, WPML, "payloadPositionIndex", 0)
        parent = _element(document, KML, "Folder")
        for key,val in {"templateType":"mapping2d", "templateId":0, "autoFlightSpeed":p["speedMetersPerSecond"]}.items():
            _element(parent, WPML, key, val)
        coord = _element(parent, WPML, "waylineCoordinateSysParam")
        for key,val in {"coordinateMode":"WGS84", "heightMode":"relativeToStartPoint", "globalShootHeight":p["heightMeters"], "surfaceFollowModeEnable":0}.items():
            _element(coord, WPML, key, val)
    placemark = _element(parent, KML, "Placemark")
    _element(placemark, KML, "name", mission["title"])
    if template:
        for key,val in {"shootType":"time", "direction":p["directionDegrees"], "margin":p["marginMeters"], "efficiencyFlightModeEnable":0, "smartObliqueEnable":0, "elevationOptimizeEnable":0, "caliFlightEnable":0, "height":p["heightMeters"]}.items():
            _element(placemark, WPML, key, val)
        overlap = _element(placemark, WPML, "overlap")
        _element(overlap, WPML, "orthoCameraOverlapH", p["forwardOverlapPercent"])
        _element(overlap, WPML, "orthoCameraOverlapW", p["sideOverlapPercent"])
    poly = _element(placemark, KML, "Polygon")
    _element(poly, KML, "altitudeMode", "clampToGround")
    ring = _element(_element(poly, KML, "outerBoundaryIs"), KML, "LinearRing")
    _element(ring, KML, "coordinates", " ".join("%.10f,%.10f,0" % tuple(point) for point in polygon(mission["feature"])))
    if template:
        payload = _element(parent, WPML, "payloadParam")
        _element(payload, WPML, "payloadPositionIndex", 0)
        _element(payload, WPML, "imageFormat", "ir" if mission["profile"] == "thermal" else "wide")
    return ET.tostring(root, encoding="utf-8", xml_declaration=True)


def template_kmz(mission):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("wpmz/template.kml", boundary_kml(mission, template=True))
    return buffer.getvalue()


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, allow_nan=False, indent=2) + "\n").encode("utf-8")
