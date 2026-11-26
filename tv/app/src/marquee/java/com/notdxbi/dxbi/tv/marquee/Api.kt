package com.notdxbi.dxbi.tv.marquee

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** An installed add-on: where it lives and what it says it can do */
class Addon(val transportUrl: String, val manifest: JSONObject) {
    val base: String = transportUrl.substringBefore('?').removeSuffix("/manifest.json").removeSuffix("/")
    // (an add-on's own query, such as a configuration, stays on every call)
    private val query: String = transportUrl.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
    val name: String = manifest.optString("name", manifest.optString("id"))
    val id: String = manifest.optString("id")

    fun url(resource: String, type: String, id: String, extra: String = ""): String {
        val enc = URLEncoder.encode(id, "UTF-8").replace("+", "%20").replace("%3A", ":")
        val tail = if (extra.isEmpty()) "" else "/$extra"
        return "$base/$resource/$type/$enc$tail.json$query"
    }

    /** Whether it answers [resource] for this type and id (Stremio's resources, types and idPrefixes) */
    fun supports(resource: String, type: String, id: String): Boolean {
        val resources = manifest.optJSONArray("resources") ?: return false
        for (i in 0 until resources.length()) {
            val r = resources.opt(i)
            val (name, types, prefixes) = when (r) {
                is String -> Triple(r, manifest.optJSONArray("types"), manifest.optJSONArray("idPrefixes"))
                is JSONObject -> Triple(r.optString("name"), r.optJSONArray("types") ?: manifest.optJSONArray("types"), r.optJSONArray("idPrefixes") ?: manifest.optJSONArray("idPrefixes"))
                else -> continue
            }
            if (name != resource) continue
            if (types != null && !types.strings().contains(type)) continue
            if (prefixes != null && prefixes.length() > 0 && prefixes.strings().none { id.startsWith(it) }) continue
            return true
        }
        return false
    }

    /** Catalogs that can be listed with nothing asked (no required genre, search or ids) */
    fun homeCatalogs(): List<Catalog> {
        val list = manifest.optJSONArray("catalogs") ?: return emptyList()
        return list.objects().mapNotNull { c ->
            val extras = c.optJSONArray("extra").objects()
            val required = extras.any { it.optBoolean("isRequired") } || (c.optJSONArray("extraRequired")?.length() ?: 0) > 0
            if (required) null else Catalog(this, c.optString("type"), c.optString("id"), c.optString("name", c.optString("id")))
        }
    }

    fun searchCatalogs(): List<Catalog> {
        val list = manifest.optJSONArray("catalogs") ?: return emptyList()
        return list.objects().mapNotNull { c ->
            val extras = c.optJSONArray("extra").objects()
            val search = extras.any { it.optString("name") == "search" } || c.optJSONArray("extraSupported")?.strings()?.contains("search") == true
            val otherRequired = extras.any { it.optBoolean("isRequired") && it.optString("name") != "search" }
            if (search && !otherRequired) Catalog(this, c.optString("type"), c.optString("id"), c.optString("name", c.optString("id"))) else null
        }
    }
}

class Catalog(val addon: Addon, val type: String, val id: String, val name: String)

/** A title in a row or a search: enough to show its poster and open it */
data class Item(
    val id: String,
    val type: String,
    val name: String,
    val poster: String,
    val background: String,
    val description: String,
    val year: String,
    val progress: Float = 0f,
    val videoId: String = "",
    val subtitle: String = "",
) {
    companion object {
        fun from(m: JSONObject) = Item(
            id = m.optString("id", m.optString("imdb_id")),
            type = m.optString("type"),
            name = m.optString("name"),
            poster = m.optString("poster"),
            background = m.optString("background"),
            description = m.optString("description"),
            year = m.optString("releaseInfo", m.optString("year")),
        )
    }
}

class Row(val title: String, val items: List<Item>, val wide: Boolean = false, val kind: String = "")

/** Stremio's account services and the add-ons' own APIs, called directly: no web page in between */
object Api {
    private const val API = "https://api.strem.io/api"
    private const val LINK = "https://link.stremio.com/api/v2"
    private val JSON = "application/json".toMediaType()

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private lateinit var prefs: android.content.SharedPreferences
    fun init(context: Context) { prefs = context.getSharedPreferences("marquee", Context.MODE_PRIVATE) }

    var authKey: String?
        get() = prefs.getString("authKey", null)
        set(v) { prefs.edit().putString("authKey", v).apply() }

    suspend fun get(url: String, timeoutMs: Long = 0): JSONObject = withContext(Dispatchers.IO) {
        val client = if (timeoutMs > 0) http.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build() else http
        client.newCall(Request.Builder().url(url).header("User-Agent", "Marquee").build()).execute().use { r ->
            if (!r.isSuccessful) throw ApiError("${r.code} from ${Request.Builder().url(url).build().url.host}")
            JSONObject(r.body!!.string())
        }
    }

    private suspend fun post(method: String, body: JSONObject): Any = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$API/$method").post(body.toString().toRequestBody(JSON)).build()
        http.newCall(req).execute().use { r ->
            val json = JSONObject(r.body?.string() ?: "{}")
            json.optJSONObject("error")?.let { e ->
                if (e.optInt("code") == 1) authKey = null // the session is gone: sign in again
                throw ApiError(e.optString("message", "Stremio refused"))
            }
            json.opt("result") ?: throw ApiError("no answer from Stremio")
        }
    }

    // ---- Sign in with a phone (Stremio's link codes) ----
    class Link(val code: String, val link: String)

    suspend fun linkCreate(): Link {
        val r = get("$LINK/create?type=Create").optJSONObject("result") ?: throw ApiError("no code")
        return Link(r.optString("code"), r.optString("link"))
    }

    /** The account's key once the code is approved, else null */
    suspend fun linkRead(code: String): String? = try {
        get("$LINK/read?type=Read&code=${URLEncoder.encode(code, "UTF-8")}").optJSONObject("result")?.optString("authKey")?.ifEmpty { null }
    } catch (_: Exception) { null }

    // ---- The account ----
    suspend fun addons(): List<Addon> {
        val key = authKey ?: throw ApiError("signed out")
        val result = post("addonCollectionGet", JSONObject().put("type", "AddonCollectionGet").put("authKey", key).put("update", true)) as JSONObject
        return result.optJSONArray("addons").objects().mapNotNull { a ->
            val url = a.optString("transportUrl")
            val manifest = a.optJSONObject("manifest") ?: return@mapNotNull null
            if (url.startsWith("http")) Addon(url, manifest) else null
        }
    }

    suspend fun library(): List<JSONObject> {
        val key = authKey ?: throw ApiError("signed out")
        val result = post("datastoreGet", JSONObject().put("authKey", key).put("collection", "libraryItem").put("ids", JSONArray()).put("all", true))
        return (result as? JSONArray).objects()
    }

    suspend fun libraryPut(item: JSONObject) {
        val key = authKey ?: return
        post("datastorePut", JSONObject().put("authKey", key).put("collection", "libraryItem").put("changes", JSONArray().put(item)))
    }

    fun now(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    /** A library entry as Stremio keeps it: the existing one updated, or a new one */
    fun libraryItem(existing: JSONObject?, meta: Item): JSONObject {
        val now = now()
        val item = existing?.let { JSONObject(it.toString()) } ?: JSONObject()
            .put("_id", meta.id)
            .put("name", meta.name)
            .put("type", meta.type)
            .put("poster", meta.poster)
            .put("posterShape", "poster")
            .put("removed", false)
            .put("temp", true)
            .put("_ctime", now)
            .put("behaviorHints", JSONObject().put("defaultVideoId", JSONObject.NULL).put("featuredVideoId", JSONObject.NULL).put("hasScheduledVideos", false))
        if (item.optJSONObject("state") == null) item.put("state", JSONObject()
            .put("lastWatched", JSONObject.NULL).put("timeWatched", 0).put("timeOffset", 0).put("overallTimeWatched", 0)
            .put("timesWatched", 0).put("flaggedWatched", 0).put("duration", 0).put("video_id", JSONObject.NULL)
            .put("watched", JSONObject.NULL).put("noNotif", false))
        item.put("_mtime", now)
        return item
    }
}

class ApiError(message: String) : Exception(message)

fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it) as? String }
