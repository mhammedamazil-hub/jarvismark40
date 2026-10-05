package com.jarvis.mobile

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/** A GitHub release we can update to. */
data class ReleaseInfo(val tag: String, val apkUrl: String, val notes: String)

/** Where this app is published. Change these if you fork/rename the repo. */
object Github {
    const val OWNER = "mhammedamazil-hub"
    const val REPO = "jarvismark40"
    val LATEST_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
}

/**
 * Self-updater for GitHub-Releases distribution (no Play Store). Checks the latest release;
 * if its tag is newer than the installed versionName, it downloads the APK asset and launches
 * the system installer. Users tap "Update" instead of reinstalling by hand.
 *
 * Version rule: a release tag like `v1.3.0` is "newer" than installed `1.2.0`. Keep tags as
 * `vMAJOR.MINOR.PATCH` and bump them each release — the updater does the rest.
 */
class UpdateManager(private val ctx: Context) {
    private val client = OkHttpClient()

    /** Returns a [ReleaseInfo] if a strictly newer release exists on GitHub, else null. */
    suspend fun check(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(Github.LATEST_URL)
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val tag = json.optString("tag_name").removePrefix("v").trim()
                val notes = json.optString("body", "")
                val assets = json.optJSONArray("assets") ?: return@withContext null
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk", true)) {
                        apkUrl = a.optString("browser_download_url"); break
                    }
                }
                if (tag.isBlank() || apkUrl.isNullOrBlank()) return@withContext null
                if (verCode(tag) <= verCode(installedVersion())) return@withContext null
                ReleaseInfo(tag, apkUrl, notes)
            }
        } catch (e: Exception) { null }
    }

    private fun installedVersion(): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
    } catch (e: Exception) { "0" }

    /** "1.10.0" > "1.9.0" — compare segment by segment. */
    private fun verCode(v: String): Int =
        v.split(".").mapNotNull { it.trim().toIntOrNull() }.fold(0) { acc, n -> acc * 1000 + n }

    /** Download the APK to app storage, then hand it to the system installer when done. */
    fun downloadAndInstall(rel: ReleaseInfo, onError: (String) -> Unit = {}) {
        try {
            val file = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "JARVIS-${rel.tag}.apk")
            val request = DownloadManager.Request(Uri.parse(rel.apkUrl))
                .setTitle("JARVIS ${rel.tag}")
                .setDescription("Downloading update…")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(file))
            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val id = dm.enqueue(request)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    if (i?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                    try { ctx.unregisterReceiver(this) } catch (_: Exception) {}
                    installApk(file, onError)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                ctx.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED)
            else
                ctx.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        } catch (e: Exception) { onError(e.message ?: "Download failed") }
    }

    private fun installApk(file: File, onError: (String) -> Unit) {
        try {
            if (!file.exists()) { onError("Download didn't complete"); return }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (e: Exception) {
            onError("Install blocked — allow 'Install unknown apps' for JARVIS in Settings.")
        }
    }
}
