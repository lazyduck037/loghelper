/*
 * Copyright 2024 GHN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lazyduck.loghelper

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

private const val CONFIG_FILE_NAME = ".log_config"

/**
 * Persists and retrieves the log-enabled flag, and can sync it from a remote REST endpoint.
 *
 * ## Flag file
 * Stored at  [filesDir]/mail_logs/.log_config  as plain text ("true" / "false").
 * Default when absent: **enabled**.
 *
 * ## REST API sync
 * Call [fetchRemoteEnabled] with a URL that returns JSON  `{ "enabled": true }`.
 * The result is returned to the caller ([LogHelper]) which then calls [save] and updates its
 * in-memory flag — keeping a single source of truth in [LogHelper.isEnabled].
 */
class LogConfigManager(context: Context, private val httpClient: OkHttpClient) {

    private val configFile = File(context.filesDir, "$LOG_DIR/$CONFIG_FILE_NAME")

    // ── Persistence ───────────────────────────────────────────────────────────

    /** Persists [enabled] to disk so it survives process restarts. */
    fun save(enabled: Boolean) {
        configFile.parentFile?.mkdirs()
        configFile.writeText(enabled.toString(), Charsets.UTF_8)
    }

    /** Loads the persisted flag. Returns `true` (enabled) when no config file exists yet. */
    fun load(): Boolean = if (configFile.exists()) {
        configFile.readText(Charsets.UTF_8).trim().toBoolean()
    } else {
        true
    }

    // ── REST API sync ─────────────────────────────────────────────────────────

    /**
     * Fetches the log-enabled state from [url].
     *
     * Expected response (HTTP 200, JSON):
     * ```json
     * { "enabled": true }
     * ```
     *
     * @return `true` / `false` from the remote config, or `null` on any network / parse error
     *         (caller keeps the current state unchanged on null).
     */
    suspend fun fetchRemoteEnabled(url: String): Boolean? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).get().build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            JSONObject(body).optBoolean("enabled", true)
        } catch (e: Exception) {
            null // network error, timeout, bad JSON → keep existing state
        }
    }
}
