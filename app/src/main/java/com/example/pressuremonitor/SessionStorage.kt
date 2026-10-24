package com.example.pressuremonitor

/*
  Data models for a recorded pressure session, plus simple file-based
  JSON storage (no database library needed - uses org.json, which is
  built into Android).

  Each session is saved as one file: filesDir/sessions/<startTimeMs>.json
  The filename (a timestamp in milliseconds) doubles as the session's
  unique ID, and is what we display formatted as yyyy/MM/dd HH:mm:ss.
*/

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PressurePoint(
    val elapsedSeconds: Float, // seconds since recording started
    val pressure: Float        // hPa
)

data class PressureSession(
    val startTimeMs: Long,
    val points: List<PressurePoint>
)

object SessionStorage {

    private fun sessionsDir(context: Context): File {
        val dir = File(context.filesDir, "sessions")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun saveSession(context: Context, session: PressureSession) {
        val json = JSONObject()
        json.put("startTimeMs", session.startTimeMs)

        val pointsArray = JSONArray()
        session.points.forEach { point ->
            val pointObj = JSONObject()
            pointObj.put("t", point.elapsedSeconds)
            pointObj.put("p", point.pressure)
            pointsArray.put(pointObj)
        }
        json.put("points", pointsArray)

        val file = File(sessionsDir(context), "${session.startTimeMs}.json")
        file.writeText(json.toString())
    }

    /** Returns session IDs (start times in ms), newest first. */
    fun listSessions(context: Context): List<Long> {
        val dir = sessionsDir(context)
        return dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { it.nameWithoutExtension.toLongOrNull() }
            ?.sortedDescending()
            ?: emptyList()
    }

    fun loadSession(context: Context, startTimeMs: Long): PressureSession? {
        val file = File(sessionsDir(context), "$startTimeMs.json")
        if (!file.exists()) return null

        return try {
            val json = JSONObject(file.readText())
            val startTime = json.getLong("startTimeMs")
            val pointsArray = json.getJSONArray("points")

            val points = mutableListOf<PressurePoint>()
            for (i in 0 until pointsArray.length()) {
                val obj = pointsArray.getJSONObject(i)
                points.add(
                    PressurePoint(
                        elapsedSeconds = obj.getDouble("t").toFloat(),
                        pressure = obj.getDouble("p").toFloat()
                    )
                )
            }
            PressureSession(startTime, points)
        } catch (e: Exception) {
            null
        }
    }

    fun deleteSession(context: Context, startTimeMs: Long) {
        File(sessionsDir(context), "$startTimeMs.json").delete()
    }

    fun deleteSessions(context: Context, ids: Collection<Long>) {
        ids.forEach { deleteSession(context, it) }
    }
}