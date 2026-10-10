package com.hhst.youtubelite.ui.about

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import com.hhst.youtubelite.R
import com.hhst.youtubelite.diagnostics.AppLog
import com.hhst.youtubelite.diagnostics.DiagnosticOutcome
import com.hhst.youtubelite.extractor.Extractor
import com.hhst.youtubelite.extractor.MemCache
import com.hhst.youtubelite.ui.theme.AppTheme
import com.tencent.mmkv.MMKV
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.java.KoinJavaComponent.get

class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { AppTheme {
            val scope = rememberCoroutineScope()
            var busy by remember { mutableStateOf(false) }
            var status by remember { mutableStateOf("") }
            val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
            fun action(name: String, work: suspend () -> String) {
                if (busy) return
                busy = true; status = getString(R.string.about_working)
                scope.launch {
                    val operation = AppLog.operation(AppLog.Category.APP, name)
                    status = runCatching { work().also { operation.finish(DiagnosticOutcome.SUCCESS) } }.getOrElse {
                        operation.finish(if (it is kotlinx.coroutines.CancellationException) DiagnosticOutcome.CANCELLED else DiagnosticOutcome.FAILURE, "operation_failed", it)
                        getString(R.string.about_operation_failed)
                    }
                    busy = false
                }
            }
            AboutScreen(applicationInfo.loadLabel(packageManager).toString(), version, { finish() }, busy, status,
                onSource = { openUrl(SOURCE) },
                onUpdate = { action("check_update") {
                    val latest = withContext(Dispatchers.IO) {
                        val request = Request.Builder().url("https://api.github.com/repos/HydeYYHH/litube/releases/latest")
                            .header("Accept", "application/vnd.github+json").build()
                        get<OkHttpClient>(OkHttpClient::class.java).newCall(request).execute().use { response ->
                            check(response.isSuccessful) { "HTTP ${response.code}" }
                            val body = response.body?.string() ?: error("Empty release response")
                            val json = JsonParser.parseString(body).asJsonObject
                            check(!json.get("prerelease").asBoolean && !json.get("draft").asBoolean)
                            json.get("tag_name").asString
                        }
                    }
                    when {
                        StableVersion.isDevelopment(version) -> getString(R.string.about_development_version, latest)
                        StableVersion.isNewer(version, latest) -> getString(R.string.about_update_available, latest)
                        else -> getString(R.string.about_up_to_date, latest)
                    }
                } },
                onRelease = { openUrl("$SOURCE/releases/latest") },
                onClear = { action("clear_cache") {
                    // clearCache removes HTTP cache only; cookies and WebStorage keep the account.
                    WebView(this@AboutActivity).let { it.clearCache(true); it.destroy() }
                    withContext(Dispatchers.IO) {
                        get<MemCache>(MemCache::class.java).clear()
                        val kv = get<MMKV>(MMKV::class.java)
                        kv.allKeys()?.filter { it.startsWith("extractor:metadata:") || it.startsWith("extractor:stream:") || it.startsWith("extractor:segment:") }?.forEach(kv::removeValueForKey)
                        listOf("gallery", "thumbnails").forEach { File(cacheDir, it).deleteRecursively() }
                    }
                    getString(R.string.about_cache_cleared)
                } },
                onExport = { action("export_share") {
                    val archive = withContext(Dispatchers.IO) {
                        AppLog.export(this@AboutActivity)
                    }
                    val uri = FileProvider.getUriForFile(this@AboutActivity, "$packageName.download.fileprovider", archive)
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("diagnostics", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, getString(R.string.about_export_logs)))
                    getString(R.string.about_export_ready)
                } })
        } }
    }
    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, R.string.application_not_found, Toast.LENGTH_SHORT).show() }
    }
    companion object { private const val SOURCE = "https://github.com/HydeYYHH/litube" }
}
