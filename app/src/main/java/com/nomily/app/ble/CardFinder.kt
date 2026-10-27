package com.nomily.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nomily.app.MainActivity
import com.nomily.app.R
import com.nomily.app.core.finder.CardFinderLogic
import com.nomily.app.core.finder.SeenRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * "Find my card" without firmware support: last connect / disconnect time, a coarse place for each
 * disconnect, and an alert when the link drops unexpectedly. The proximity radar lives in the UI
 * (it only runs while the section is on screen).
 *
 * Location is one coarse fix per disconnect, taken only when the permission is already granted;
 * without it the record keeps just the time.
 */
object CardFinder {
    private const val TAG = "CardFinder"
    private const val PREFS = "card_finder"
    private const val CHANNEL_ID = "card_finder_alerts"

    data class State(
        val address: String? = null,
        val name: String? = null,
        val lastConnectedAt: Long? = null,
        val lastDisconnectedAt: Long? = null,
        val history: List<SeenRecord> = emptyList(),
        val notifyOnDisconnect: Boolean = true,
        val notificationsBlocked: Boolean = false,
        val locationGranted: Boolean = false,
    ) {
        /** Newest of connect / disconnect. */
        val lastSeenAt: Long? get() = listOfNotNull(lastConnectedAt, lastDisconnectedAt).maxOrNull()
    }

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var lastAlertAt: Long? = null

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _state.value = State(
            address = prefs.getString("address", null),
            name = prefs.getString("name", null),
            lastConnectedAt = prefs.getLong("connected_at", 0).takeIf { it > 0 },
            lastDisconnectedAt = prefs.getLong("disconnected_at", 0).takeIf { it > 0 },
            history = decodeHistory(prefs.getString("history", null)),
            notifyOnDisconnect = prefs.getBoolean("notify", true),
        )
        refreshPermissions()
    }

    /** Re-read the notification and location grants (they change in system settings, outside the app). */
    fun refreshPermissions() {
        if (!::app.isInitialized) return
        _state.update {
            it.copy(
                notificationsBlocked = !NotificationManagerCompat.from(app).areNotificationsEnabled(),
                locationGranted = hasLocationPermission(),
            )
        }
    }

    fun register(address: String, name: String) {
        if (!::app.isInitialized) return
        val now = System.currentTimeMillis()
        prefs.edit().putString("address", address).putString("name", name).putLong("connected_at", now).apply()
        _state.update { it.copy(address = address, name = name, lastConnectedAt = now) }
        refreshPermissions()
    }

    fun setNotifyOnDisconnect(on: Boolean) {
        if (!::app.isInitialized) return
        prefs.edit().putBoolean("notify", on).apply()
        _state.update { it.copy(notifyOnDisconnect = on) }
    }

    /** The link ended. Only [unexpected] drops alert: a user disconnect or shutdown is not news. */
    fun onDisconnected(unexpected: Boolean, cardName: String?) {
        if (!::app.isInitialized) return
        recordDisconnect()
        if (unexpected) alert(cardName) { app.getString(R.string.find_drop_body, it) }
    }

    /** The phone's Bluetooth went off: the card didn't walk away, but the link is gone all the same. */
    fun onBluetoothOff(cardName: String?) {
        if (!::app.isInitialized) return
        recordDisconnect()
        alert(cardName) { app.getString(R.string.find_bluetooth_off_body, it) }
    }

    fun clearHistory() {
        _state.update { it.copy(history = emptyList()) }
        persistHistory()
    }

    fun sendTestAlert() {
        if (!::app.isInitialized) return
        post(app.getString(R.string.find_test_alert_body))
    }

    /** Opens the coordinates in whichever maps app handles `geo:`; silently nothing if none does. */
    fun openInMaps(context: Context, record: SeenRecord) {
        val lat = record.latitude ?: return
        val lon = record.longitude ?: return
        val label = android.net.Uri.encode(_state.value.name ?: context.getString(R.string.find_header))
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:$lat,$lon?q=$lat,$lon($label)"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "No maps app for geo: intent: ${it.message}") }
    }

    // ── internals ──

    private fun recordDisconnect() {
        val now = System.currentTimeMillis()
        prefs.edit().putLong("disconnected_at", now).apply()
        _state.update {
            it.copy(lastDisconnectedAt = now, history = CardFinderLogic.addSeen(it.history, SeenRecord(at = now)))
        }
        persistHistory()
        capturePlace(now)
    }

    private fun alert(cardName: String?, body: (String) -> String) {
        val s = _state.value
        if (!s.notifyOnDisconnect) return
        val now = System.currentTimeMillis()
        if (!CardFinderLogic.shouldAlert(lastAlertAt, now)) return
        lastAlertAt = now
        post(body(cardName ?: s.name ?: app.getString(R.string.find_this_card)))
    }

    /** Posts on the high-importance channel; without the notification grant the page says so instead. */
    @SuppressLint("MissingPermission") // areNotificationsEnabled() below covers POST_NOTIFICATIONS
    private fun post(body: String) {
        refreshPermissions()
        if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) {
            Log.w(TAG, "alert suppressed: notifications are off")
            return
        }
        val nm = app.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, app.getString(R.string.find_title), NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val open = PendingIntent.getActivity(
            app, 0, Intent(app, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(app.getString(R.string.find_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH) // pre-O: channels don't exist
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(app).notify(System.currentTimeMillis().toInt(), n) }
            .onFailure { Log.w(TAG, "notify failed: ${it.message}") }
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** One fix: `getCurrentLocation` on API 30+, otherwise the freshest last-known one. Never throws. */
    @SuppressLint("MissingPermission") // guarded by hasLocationPermission()
    private fun capturePlace(at: Long) {
        if (!hasLocationPermission()) return
        runCatching {
            val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            if (providers.isEmpty()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.getCurrentLocation(providers.first(), null, ContextCompat.getMainExecutor(app)) { loc ->
                    val fix = loc ?: lastKnown(lm, providers)
                    if (fix != null) scope.launch { resolve(at, fix) }
                }
            } else {
                lastKnown(lm, providers)?.let { fix -> scope.launch { resolve(at, fix) } }
            }
        }.onFailure { Log.w(TAG, "location failed: ${it.message}") }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(lm: LocationManager, providers: List<String>): Location? =
        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }

    /** Reverse-geocodes (blocking, runs on IO); the coordinate string stays as the fallback place name. */
    @Suppress("DEPRECATION") // shortcut: sync getFromLocation, fine on the IO scope; async overload needs API 33
    private fun resolve(at: Long, fix: Location) {
        val raw = String.format(Locale.US, "%.4f, %.4f", fix.latitude, fix.longitude)
        val label = runCatching {
            if (!Geocoder.isPresent()) null
            else Geocoder(app, Locale.getDefault()).getFromLocation(fix.latitude, fix.longitude, 1)?.firstOrNull()?.let { a ->
                listOfNotNull(a.featureName, a.locality).filter { it.isNotBlank() }.joinToString(" · ")
            }
        }.getOrNull()
        _state.update {
            it.copy(history = CardFinderLogic.patchSeen(it.history, at, label?.ifEmpty { null } ?: raw, fix.latitude, fix.longitude))
        }
        persistHistory()
    }

    private fun persistHistory() {
        val arr = JSONArray()
        _state.value.history.forEach {
            arr.put(JSONObject().put("at", it.at).put("place", it.place ?: JSONObject.NULL)
                .put("lat", it.latitude ?: JSONObject.NULL).put("lon", it.longitude ?: JSONObject.NULL))
        }
        prefs.edit().putString("history", arr.toString()).apply()
    }

    private fun decodeHistory(json: String?): List<SeenRecord> = runCatching {
        val arr = JSONArray(json ?: return emptyList())
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            SeenRecord(
                at = o.getLong("at"),
                place = if (o.isNull("place")) null else o.getString("place"),
                latitude = if (o.isNull("lat")) null else o.getDouble("lat"),
                longitude = if (o.isNull("lon")) null else o.getDouble("lon"),
            )
        }
    }.getOrDefault(emptyList())
}
