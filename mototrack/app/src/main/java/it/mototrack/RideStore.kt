package it.mototrack

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class RideStore(context: Context) {
    private val directory = File(context.filesDir, "rides").apply { mkdirs() }
    private fun file(id: Long) = AtomicFile(File(directory, "$id.json"))
    fun delete(id: Long) {
        file(id).delete()
        check(listOf("$id.json", "$id.json.bak", "$id.json.new").none { File(directory, it).exists() }) {
            "Impossibile eliminare il giro"
        }
    }
    fun save(ride: Ride) {
        val json = JSONObject().put("id", ride.id).put("ended", ride.ended ?: JSONObject.NULL)
            .put("activeMillis", ride.activeMillis)
        val points = JSONArray()
        ride.points.forEach { p -> points.put(JSONObject()
            .put("time", p.time).put("lat", p.lat).put("lon", p.lon)
            .put("altitude", p.altitude ?: JSONObject.NULL).put("speed", p.speed ?: JSONObject.NULL)
            .put("lean", p.lean ?: JSONObject.NULL).put("acceleration", p.acceleration ?: JSONObject.NULL)
            .put("accelerationSource", p.accelerationSource).put("accuracy", p.accuracy.toDouble()).put("segment", p.segment)) }
        json.put("points", points)
        val inertial = JSONArray()
        ride.inertial.forEach { inertial.put(JSONObject().put("time", it.time).put("g", it.g).put("segment", it.segment)) }
        json.put("inertial", inertial)
        val atomic = file(ride.id)
        val stream = atomic.startWrite()
        try { stream.write(json.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    fun load(id: Long): Ride {
        val j = JSONObject(file(id).openRead().bufferedReader().use { it.readText() })
        val array = j.getJSONArray("points")
        val points = (0 until array.length()).map { index ->
            val p = array.getJSONObject(index)
            fun nullable(key: String) = if (p.isNull(key)) null else p.getDouble(key)
            Sample(p.getLong("time"), p.getDouble("lat"), p.getDouble("lon"), nullable("altitude"),
                nullable("speed"), nullable("lean"), nullable("acceleration"), p.getDouble("accuracy").toFloat(), p.getInt("segment"), p.optString("accelerationSource", "gps"))
        }
        val samples = j.optJSONArray("inertial") ?: JSONArray()
        val inertial = (0 until samples.length()).map {
            val p = samples.getJSONObject(it)
            InertialSample(p.getLong("time"), p.getDouble("g"), p.getInt("segment"))
        }
        return Ride(j.getLong("id"), if (j.isNull("ended")) null else j.getLong("ended"), j.getLong("activeMillis"), points, inertial)
    }
    fun list(): List<Ride> = directory.listFiles().orEmpty().filter { it.extension == "json" }
        .mapNotNull { f -> f.nameWithoutExtension.toLongOrNull()?.let { runCatching { load(it) }.getOrNull() } }
        .sortedByDescending { it.id }
}
