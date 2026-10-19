import java.nio.file.*;
import org.json.*;
import org.ncssar.rid2caltopo.mapping.*;
/** Read-only forecasts using the compiled Android planner; never exports or transfers a route.
 * Usage: java -cp <debug Kotlin classes>:<org.json jar>:<Kotlin stdlib jar>
 *        tools/mapping/FlightForecast.java <saved mission.json> <output.json>
 */
public class FlightForecast {
  public static void main(String[] args) throws Exception {
    JSONObject original=new JSONObject(Files.readString(Path.of(args[0])));
    JSONObject results=new JSONObject();
    MappingCaptureProfile.Profile[] profiles={MappingCaptureProfile.INSTANCE.getNadir(),MappingCaptureProfile.INSTANCE.getNadir60Standard(),MappingCaptureProfile.INSTANCE.getNadir60()};
    for(var profile:profiles){
      var mission=new JSONObject(original.toString()).put("captureProfile",profile.json());
      mission.getJSONObject("parameters").put("captureTrigger","distance-v1");
      var route=new DjiMappingRoute(mission);results.put(profile.getId(),route.summary());
    }
    var cross=new JSONObject(original.toString());cross.remove("captureProfile");cross.getJSONObject("parameters").put("captureTrigger","distance-v1").put("directionDegrees",90);
    results.put("supplemental-cross-nadir",new DjiMappingRoute(cross).summary());
    Files.writeString(Path.of(args[1]),results.toString(2));
    System.out.println(results.toString(2));
  }
}
