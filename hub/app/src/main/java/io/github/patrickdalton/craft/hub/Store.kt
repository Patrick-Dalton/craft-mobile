package io.github.patrickdalton.craft.hub

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The GitHub repository whose releases hold the APKs (one `<id>-latest` release per app). */
const val REPO = "Patrick-Dalton/craft-mobile"
private const val CATALOG_URL = "https://raw.githubusercontent.com/$REPO/main/catalog.json"
const val HUB_ID = "hub"

fun releaseFile(id: String, file: String) = "https://github.com/$REPO/releases/download/$id-latest/$file"

data class CatalogApp(
    val id: String,
    val name: String,
    val subtitle: String,
    val inspired: String,
    val packageName: String,
    val available: Boolean,
) {
    /** The upstream app's own icon, from the storytold repository it is built from. */
    val iconUrls: List<String>
        get() = listOf(256, 128, 64).map {
            "https://raw.githubusercontent.com/storytold/$id/main/assets/app-icon/hicolor/${it}x$it/apps/ai.storyteller.$id.png"
        }
}

/** What `<id>.json`, next to each APK, says about it. */
data class Release(val versionName: String, val versionCode: Long, val size: Long)

data class Installed(val versionName: String, val versionCode: Long)

object Store {
    /** The catalog from the repository, so new apps show up without a new hub; else the bundled copy. */
    fun catalog(context: Context): List<CatalogApp> {
        val json = try {
            fetchText(CATALOG_URL)
        } catch (e: IOException) {
            context.assets.open("catalog.json").bufferedReader().use { it.readText() }
        }
        val apps = JSONObject(json).getJSONArray("apps")
        return (0 until apps.length()).map { i ->
            val a = apps.getJSONObject(i)
            CatalogApp(
                id = a.getString("id"),
                name = a.getString("name"),
                subtitle = a.optString("subtitle"),
                inspired = a.optString("inspired"),
                packageName = a.getString("package"),
                available = a.optBoolean("available", false),
            )
        }
    }

    /** The latest build of [id], or null when none has been published. */
    fun release(id: String): Release? = try {
        val j = JSONObject(fetchText(releaseFile(id, "$id.json")))
        Release(j.getString("versionName"), j.getLong("versionCode"), j.optLong("size"))
    } catch (e: Exception) {
        null
    }

    fun installed(context: Context, packageName: String): Installed? = try {
        val info = context.packageManager.getPackageInfo(packageName, 0)
        Installed(info.versionName ?: "?", PackageInfoCompat.getLongVersionCode(info))
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    fun icon(app: CatalogApp): Bitmap? {
        for (url in app.iconUrls) {
            try {
                val bytes = open(url).use { it.inputStream.readBytes() }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it }
            } catch (e: IOException) {
                // Try the next size.
            }
        }
        return null
    }

    /** Downloads [id]'s APK into the cache, reporting progress from 0 to 1. */
    fun download(context: Context, id: String, onProgress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "apks").apply { mkdirs() }
        val part = File(dir, "$id.apk.part")
        val apk = File(dir, "$id.apk")
        open(releaseFile(id, "$id.apk")).use { conn ->
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        }
        if (!part.renameTo(apk)) throw IOException("não foi possível salvar o APK")
        return apk
    }

    private fun fetchText(url: String): String = open(url).use { it.inputStream.bufferedReader().readText() }

    private fun open(url: String): Conn {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "CraftHub")
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw IOException("HTTP $code")
        }
        return Conn(conn)
    }

    private class Conn(private val conn: HttpURLConnection) : AutoCloseable {
        val inputStream get() = conn.inputStream
        val contentLengthLong get() = conn.contentLengthLong
        override fun close() = conn.disconnect()
    }
}
