package com.gpstelemetry.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializzazione JSON manuale (org.json: incluso nella piattaforma Android,
 * fornito come dipendenza di test su JVM). Usata per la persistenza su file
 * e per l'esportazione integrale delle sessioni.
 */
object Json {

    /* ----------------------------- Track ------------------------------ */

    fun track(t: Track): JSONObject = JSONObject().apply {
        put("id", t.id)
        put("name", t.name)
        put("type", t.type.name)
        put("tolerance", t.tolerance)
        put("createdAt", t.createdAt)
        put("start", gate(t.start))
        put("finish", t.finish?.let { gate(it) } ?: JSONObject.NULL)
        put("splits", JSONArray().apply { t.splits.forEach { put(split(it)) } })
        put("path", JSONArray().apply { t.path.forEach { put(pathPoint(it)) } })
        put("published", t.published)
        put("remoteId", t.remoteId ?: JSONObject.NULL)
        put("remoteAuthor", t.remoteAuthor ?: JSONObject.NULL)
    }

    fun parseTrack(o: JSONObject): Track = Track(
        id = o.getString("id"),
        name = o.getString("name"),
        type = TrackType.valueOf(o.getString("type")),
        tolerance = o.getDouble("tolerance"),
        createdAt = o.getLong("createdAt"),
        start = parseGate(o.getJSONObject("start")),
        finish = if (o.isNull("finish")) null else parseGate(o.getJSONObject("finish")),
        splits = o.getJSONArray("splits").let { arr ->
            MutableList(arr.length()) { parseSplit(arr.getJSONObject(it)) }
        },
        path = o.getJSONArray("path").let { arr ->
            MutableList(arr.length()) { parsePathPoint(arr.getJSONObject(it)) }
        },
        published = o.optBoolean("published", false),
        remoteId = optStr(o, "remoteId"),
        remoteAuthor = optStr(o, "remoteAuthor"),
    )

    /** Stringa facoltativa (assente o null → null). */
    fun optStr(o: JSONObject, key: String): String? =
        if (!o.has(key) || o.isNull(key)) null else o.getString(key)

    /* ---------------------------- Session ----------------------------- */

    fun session(s: Session): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("trackId", s.trackId)
        put("name", s.name)
        put("startedAt", s.startedAt)
        put("trackType", s.trackType.name)
        put("trackStart", gate(s.trackStart))
        put("trackFinish", s.trackFinish?.let { gate(it) } ?: JSONObject.NULL)
        put("trackSplits", JSONArray().apply { s.trackSplits.forEach { put(split(it)) } })
        put("status", s.status.name)
        put("samples", JSONArray().apply { s.samples.forEach { put(gps(it)) } })
        put("motion", JSONArray().apply { s.motion.forEach { put(motion(it)) } })
        put("hr", JSONArray().apply { s.hr.forEach { put(hr(it)) } })
        put("result", s.result?.let { result(it) } ?: JSONObject.NULL)
        put("vehicleType", s.vehicleType?.name ?: JSONObject.NULL)
        put("vehicle", s.vehicle ?: JSONObject.NULL)
        put("remoteLapId", s.remoteLapId ?: JSONObject.NULL)
    }

    /** Categoria e modello del veicolo, con conversione del vecchio testo unico. */
    private fun vehicleOf(o: JSONObject): Pair<VehicleType?, String?> =
        Vehicle.migrate(VehicleType.parse(optStr(o, "vehicleType")), optStr(o, "vehicle"))

    fun parseSession(o: JSONObject): Session = Session(
        id = o.getString("id"),
        trackId = o.getString("trackId"),
        name = o.getString("name"),
        startedAt = o.getLong("startedAt"),
        trackType = TrackType.valueOf(o.getString("trackType")),
        trackStart = parseGate(o.getJSONObject("trackStart")),
        trackFinish = if (o.isNull("trackFinish")) null else parseGate(o.getJSONObject("trackFinish")),
        trackSplits = o.getJSONArray("trackSplits").let { arr ->
            List(arr.length()) { parseSplit(arr.getJSONObject(it)) }
        },
        samples = o.getJSONArray("samples").let { arr ->
            MutableList(arr.length()) { parseGps(arr.getJSONObject(it)) }
        },
        motion = o.getJSONArray("motion").let { arr ->
            MutableList(arr.length()) { parseMotion(arr.getJSONObject(it)) }
        },
        hr = o.getJSONArray("hr").let { arr ->
            MutableList(arr.length()) { parseHr(arr.getJSONObject(it)) }
        },
        result = if (o.isNull("result")) null else parseResult(o.getJSONObject("result")),
        status = SessionStatus.valueOf(o.getString("status")),
        vehicle = vehicleOf(o).second,
        remoteLapId = optStr(o, "remoteLapId"),
        vehicleType = vehicleOf(o).first,
    )

    /* ------------------------ Riepilogo sessione ----------------------- */

    fun summary(s: SessionSummary): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("trackId", s.trackId)
        put("name", s.name)
        put("startedAt", s.startedAt)
        put("status", s.status.name)
        put("result", s.result?.let { result(it) } ?: JSONObject.NULL)
        put("vehicleType", s.vehicleType?.name ?: JSONObject.NULL)
        put("vehicle", s.vehicle ?: JSONObject.NULL)
        put("remoteLapId", s.remoteLapId ?: JSONObject.NULL)
    }

    fun parseSummary(o: JSONObject) = SessionSummary(
        id = o.getString("id"),
        trackId = o.getString("trackId"),
        name = o.getString("name"),
        startedAt = o.getLong("startedAt"),
        status = SessionStatus.valueOf(o.getString("status")),
        result = if (o.isNull("result")) null else parseResult(o.getJSONObject("result")),
        vehicle = vehicleOf(o).second,
        remoteLapId = optStr(o, "remoteLapId"),
        vehicleType = vehicleOf(o).first,
    )

    /** Risultato di un giro (pubblico, per l'invio alla classifica). */
    fun resultJson(r: SessionResult) = result(r)
    fun parseResultJson(o: JSONObject) = parseResult(o)

    /* --------------------------- Componenti --------------------------- */

    private fun gate(g: Gate) = JSONObject()
        .put("lat", g.lat)
        .put("lon", g.lon)
        .put("heading", g.headingDeg)
        .put("width", g.widthM)

    /** Tollerante verso il vecchio formato a punti (senza heading/width). */
    private fun parseGate(o: JSONObject) = Gate(
        lat = o.getDouble("lat"),
        lon = o.getDouble("lon"),
        headingDeg = o.optDouble("heading", 0.0),
        widthM = o.optDouble("width", 30.0),
    )

    private fun split(s: SplitGate) = gate(s.gate).put("name", s.name)

    private fun parseSplit(o: JSONObject) = SplitGate(o.optString("name", ""), parseGate(o))

    private fun pathPoint(p: PathPoint) = JSONObject().apply {
        put("lat", p.lat)
        put("lon", p.lon)
        p.ele?.let { put("ele", it) }
    }

    private fun parsePathPoint(o: JSONObject) = PathPoint(
        o.getDouble("lat"), o.getDouble("lon"),
        if (o.has("ele")) o.getDouble("ele") else null,
    )

    private fun gps(p: GpsSample) = JSONObject().apply {
        put("t", p.t)
        put("lat", p.lat)
        put("lon", p.lon)
        p.ele?.let { put("ele", it) }
        p.acc?.let { put("acc", it.toDouble()) }
        p.speed?.let { put("speed", it.toDouble()) }
        p.heading?.let { put("heading", it.toDouble()) }
    }

    private fun parseGps(o: JSONObject) = GpsSample(
        t = o.getLong("t"),
        lat = o.getDouble("lat"),
        lon = o.getDouble("lon"),
        ele = if (o.has("ele")) o.getDouble("ele") else null,
        acc = if (o.has("acc")) o.getDouble("acc").toFloat() else null,
        speed = if (o.has("speed")) o.getDouble("speed").toFloat() else null,
        heading = if (o.has("heading")) o.getDouble("heading").toFloat() else null,
    )

    private fun motion(m: MotionSample) = JSONObject().apply {
        put("t", m.t)
        put("ax", m.ax.toDouble())
        put("ay", m.ay.toDouble())
        put("az", m.az.toDouble())
        m.pitch?.let { put("pitch", it.toDouble()) }
        m.roll?.let { put("roll", it.toDouble()) }
    }

    private fun parseMotion(o: JSONObject) = MotionSample(
        t = o.getLong("t"),
        ax = o.getDouble("ax").toFloat(),
        ay = o.getDouble("ay").toFloat(),
        az = o.getDouble("az").toFloat(),
        pitch = if (o.has("pitch")) o.getDouble("pitch").toFloat() else null,
        roll = if (o.has("roll")) o.getDouble("roll").toFloat() else null,
    )

    private fun hr(h: HrSample) = JSONObject().put("t", h.t).put("bpm", h.bpm)
    private fun parseHr(o: JSONObject) = HrSample(o.getLong("t"), o.getInt("bpm"))

    private fun result(r: SessionResult) = JSONObject().apply {
        put("status", r.status.name)
        put("startTime", r.startTime ?: JSONObject.NULL)
        put("finishTime", r.finishTime ?: JSONObject.NULL)
        put("elapsedMs", r.elapsedMs ?: JSONObject.NULL)
        put("offTrackMs", r.offTrackMs)
        put("offTrackEpisodes", r.offTrackEpisodes)
        put("splits", JSONArray().apply {
            r.splits.forEach {
                put(JSONObject().put("name", it.name).put("t", it.t).put("elapsed", it.elapsed))
            }
        })
    }

    private fun parseResult(o: JSONObject) = SessionResult(
        status = SessionStatus.valueOf(o.getString("status")),
        startTime = if (o.isNull("startTime")) null else o.getLong("startTime"),
        finishTime = if (o.isNull("finishTime")) null else o.getLong("finishTime"),
        elapsedMs = if (o.isNull("elapsedMs")) null else o.getLong("elapsedMs"),
        splits = o.getJSONArray("splits").let { arr ->
            List(arr.length()) {
                val so = arr.getJSONObject(it)
                SplitTime(so.getString("name"), so.getLong("t"), so.getLong("elapsed"))
            }
        },
        offTrackMs = o.getLong("offTrackMs"),
        offTrackEpisodes = o.getInt("offTrackEpisodes"),
    )
}
