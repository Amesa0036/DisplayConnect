import com.example.displayconnect.network.GnssDiagnostic
import org.json.JSONObject
class GnssContractTest {
    @org.junit.Test fun diagnosticContractRejectsStaleOrInvalidData() {
    fun fixture(valid: Boolean = true) = JSONObject("""{"protocol":1,"source":"lc76g","supported":true,"running":true,"reason":"running","responded":true,"valid_fix":$valid,"latitude":41.3,"longitude":2.1,"altitude_msl_m":15,"hdop":0.8,"fix_age_ms":100,"satellites":9,"quality":1,"sentences":10,"gga_count":2,"checksum_errors":0,"malformed":0,"overlong":0,"i2c_errors":0,"bytes":800}""")
    val d = GnssDiagnostic.parse(fixture(), 1000)
    check(d.valid && d.message.contains("41.3") && d.display(1000).contains("41.3"))
    check(!d.display(5001).contains("41.3") && !d.display(0).contains("41.3"))
    fun rejected(j: JSONObject) { check(runCatching { GnssDiagnostic.parse(j, 1000) }.isFailure) }
    rejected(fixture().put("latitude",91));rejected(fixture().put("longitude",-181))
    rejected(fixture().put("fix_age_ms",3001));rejected(fixture().put("quality",6));rejected(fixture().put("satellites",0))
    rejected(fixture().put("running",false));rejected(fixture().put("protocol",2));rejected(fixture().put("source","phone"))
    rejected(fixture().put("reason","unknown"));rejected(fixture().put("bytes",-1))
    val no = GnssDiagnostic.parse(fixture(false).put("latitude",JSONObject.NULL).put("longitude",JSONObject.NULL),1000)
    check(!no.valid && !no.message.contains("41.3") && no.message.contains("Sin posición válida"))
    println("PASS actual Android GNSS diagnostic parser: bounds/provenance/freshness/rejection/no-fix rendering; stale coordinates removed")
}

}
