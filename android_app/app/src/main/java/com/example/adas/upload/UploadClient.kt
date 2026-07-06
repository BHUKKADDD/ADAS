package com.example.adas.upload

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/** One detection, trimmed to what the cloud ingestion API needs. */
@Serializable
data class DetectionSummary(val label: String, val confidence: Float)

/**
 * An anomaly packet uploaded to the cloud ingestion API. Metadata only for now
 * (no frame bytes) — enough to prove the selective-upload path end-to-end.
 */
@Serializable
data class AnomalyPacket(
    val timestampMs: Long,
    val deviceModel: String,
    val speedKmh: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyM: Float? = null,
    val detections: List<DetectionSummary> = emptyList()
)

/**
 * Model-agnostic upload client. Serializes an [AnomalyPacket] to JSON and POSTs it
 * over plain [HttpURLConnection] (no OkHttp/Ktor dependency), gated by a selective
 * connectivity policy. Kept decoupled from the detector/IDD model; the cloud side
 * itself is Phase 4 / commercial.
 */
class UploadClient(context: Context) {

    private val appContext = context.applicationContext
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /**
     * Selective-upload policy: only upload on an unmetered Wi-Fi connection (don't
     * burn the driver's mobile data). Flip the transport check to also allow
     * cellular later if desired.
     */
    fun canUpload(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** POST [packet] as JSON to [endpoint]. Returns true on a 2xx response. */
    suspend fun upload(packet: AnomalyPacket, endpoint: String): Boolean =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(packet).toByteArray(Charsets.UTF_8)
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5_000
                readTimeout = 5_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                Log.d(TAG, "Upload -> $endpoint : HTTP $code")
                code in 200..299
            } catch (e: Exception) {
                Log.w(TAG, "Upload failed: ${e.message}")
                false
            } finally {
                conn.disconnect()
            }
        }

    private companion object {
        const val TAG = "UploadClient"
    }
}
