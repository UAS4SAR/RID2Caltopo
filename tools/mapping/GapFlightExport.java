import java.nio.file.*;
import org.json.*;
import org.ncssar.rid2caltopo.mapping.*;

/** Prepare local review artifacts through the production planner. Never opens a connection. */
public class GapFlightExport {
  static JSONArray legs(DjiMappingRoute route,int pass) {
    var array=new JSONArray();
    for(var leg:route.getLegs()) {
      var a=route.coordinate(leg.getStart());var b=route.coordinate(leg.getEnd());
      array.put(new JSONObject().put("passIndex",pass<0?leg.getPassIndex():pass)
        .put("start",new JSONArray().put(a.getFirst()).put(a.getSecond()))
        .put("end",new JSONArray().put(b.getFirst()).put(b.getSecond()))
        .put("spacingMeters",route.passSpacing(route.getPasses().get(leg.getPassIndex()))));
    }
    return array;
  }
  public static void main(String[] args) throws Exception {
    var mission=new JSONObject(Files.readString(Path.of(args[1])));
    if(args[0].equals("anchors")) {
      var combined=new JSONArray();int photos=0;
      for(int pass=0;pass<2;pass++) {
        var m=new JSONObject(mission.toString());m.remove("captureProfile");m.remove("gapCapturePlan");
        m.getJSONObject("parameters").put("directionDegrees",pass*90).put("captureTrigger","distance-v1").put("cameraHeadingPolicy","aircraft-first-v1");
        var route=new DjiMappingRoute(m);photos+=route.getPhotoEstimate();
        for(Object leg:legs(route,pass))combined.put(leg);
      }
      Files.writeString(Path.of(args[2]),new JSONObject().put("legs",combined).put("estimatedPhotos",photos).toString(2));
    } else if(args[0].equals("canopy-anchors")) {
      // Start from a provenance-bearing canopy plan, then forecast only its anchor grid.
      var results=new JSONArray();double oldGsd=mission.getJSONObject("parameters").getDouble("desiredGroundResolutionCmPerPixel");
      double oldHeight=mission.getJSONObject("parameters").getDouble("heightMeters");
      for(double gsd:new double[]{2.0,2.2,2.4,2.6}) {
        var m=new JSONObject(mission.toString());m.getJSONObject("parameters").put("desiredGroundResolutionCmPerPixel",gsd).put("heightMeters",oldHeight*gsd/oldGsd);
        var route=new DjiMappingRoute(m);var anchors=new JSONArray();int photos=0;double metres=0;DjiMappingRoute.Point previous=null;
        for(var leg:route.getLegs())if(leg.getPassIndex()==0){
          var start=leg.getStart();var end=leg.getEnd();double length=Math.hypot(end.getX()-start.getX(),end.getY()-start.getY());
          photos+=(int)Math.ceil(length/route.passSpacing(route.getPasses().get(0)))+1;metres+=length;
          if(previous!=null)metres+=Math.hypot(start.getX()-previous.getX(),start.getY()-previous.getY());previous=end;
        }
        for(Object raw:legs(route,-1))if(((JSONObject)raw).getInt("passIndex")==0)anchors.put(raw);
        results.put(new JSONObject().put("gsd",gsd).put("direction",90).put("referenceHeightMeters",m.getJSONObject("gapCapturePlan").getDouble("planningSurfaceHeightMeters"))
          .put("legs",anchors).put("summary",new JSONObject().put("heightMeters",route.getHeightMeters()).put("estimatedPhotos",photos)
            .put("estimatedFlightSeconds",(int)Math.ceil(metres/route.getSpeed()+anchors.length()*4)).put("waypointCount",anchors.length()*2)));
      }
      Files.writeString(Path.of(args[2]),results.toString(2));
    } else if(args[0].equals("export")) {
      var out=Path.of(args[2]);Files.createDirectories(out);
      var route=new DjiMappingRoute(mission);
      Files.write(out.resolve("mission.kmz"),route.kmz());
      Files.writeString(out.resolve("route-summary.json"),route.summary().toString(2));
      Files.writeString(out.resolve("route-legs.json"),legs(route,-1).toString(2));
      var features=new JSONArray();
      features.put(new JSONObject().put("type","Feature").put("geometry",mission.getJSONObject("feature").getJSONObject("geometry"))
        .put("properties",new JSONObject().put("role","target").put("title",mission.getString("title"))));
      var path=new JSONArray();int index=0;
      for(Object raw:legs(route,-1)) {
        var leg=(JSONObject)raw;
        for(String endpoint:new String[]{"start","end"}) {
          var coordinate=leg.getJSONArray(endpoint);path.put(coordinate);
          features.put(new JSONObject().put("type","Feature").put("geometry",new JSONObject().put("type","Point").put("coordinates",coordinate))
            .put("properties",new JSONObject().put("waypoint",index++).put("pass",leg.getInt("passIndex")+1)
              .put("gimbalPitchDegrees",route.getPasses().get(leg.getInt("passIndex")).getPitchDegrees())
              .put("heightAboveTakeoffMeters",route.getHeightMeters())));
        }
      }
      features.put(new JSONObject().put("type","Feature").put("geometry",new JSONObject().put("type","LineString").put("coordinates",path))
        .put("properties",new JSONObject().put("role","aircraft path including connections")));
      Files.writeString(out.resolve("route-preview.geojson"),new JSONObject().put("type","FeatureCollection").put("features",features).toString(2));
      System.out.println(route.summary().toString(2));
    } else throw new IllegalArgumentException("Use anchors, canopy-anchors or export.");
  }
}
