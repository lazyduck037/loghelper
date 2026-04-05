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
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

// ── Formatter helpers (private to this file) ─────────────────────────────────

/**
 * Replaces `{}` placeholders in [template] with [args] in order (SLF4J-style).
 * Extra `{}` with no matching arg are left as-is. Extra args with no `{}` are ignored.
 */

/**
 * Converts one argument to its log-friendly string.
 * Collections and Maps are expanded; arrays use deep formatting.
 */

// ── LogHelper ─────────────────────────────────────────────────────────────────

/**
 * App-wide file logger. Register as a Koin singleton; use static helpers anywhere.
 *
 * ## Guarantees
 * | Property         | Mechanism                                                            |
 * |------------------|----------------------------------------------------------------------|
 * | **FIFO order**   | `Channel.UNLIMITED` + single writer coroutine.                       |
 * | **No log loss**  | `Channel.UNLIMITED` never drops; `trySend` is lock-free/non-blocking.|
 * | **Batched I/O**  | Entries accumulate in memory; one `appendText` per 1 KB batch.       |
 * | **Drain on stop**| `finally` block flushes any remaining < 1 KB buffer on shutdown.     |
 * | **Thread-safe**  | `@Volatile isEnabled` + lock-free channel send.                      |
 *
 * ## File rotation / retention
 * - File size tracked **in-memory** (no `File.length()` per entry).
 * - Each file capped at **5 MB**; new suffixed file on overflow.
 * - Files older than **3 days** deleted once at startup.
 *
 * ## Message format
 * `2024-03-22 10:30:01.123 [tid=12] D/TAG: message`
 *
 * ## Usage (after Koin init)
 * ```kotlin
 * LogHelper.d("saved")                              // default tag
 * LogHelper.d("TAG", "saved")                       // explicit tag
 * LogHelper.d("TAG", "user {} status {}", user, 200) // {} placeholders
 * LogHelper.e("TAG", "failed {} ", id, exception)   // last Throwable → cause
 * LogHelper.setEnabled(false)
 * LogHelper.syncRemoteConfig("https://host/log-config")
 * ```
 */
class LogHelper(
    context: Context,
    private val configManager: LogConfigManager,
) {

    @Volatile private var isEnabled: Boolean = false

    private val fileManager = LogFileManager(context)
    private val logChannel = Channel<LogEntry>(capacity = Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        instance = this
        scope.launch {
//            isEnabled = configManager.load()
            fileManager.deleteOldFiles()
            // Print absolute log dir path on successful init
            val messInit = """
                ---------------------------------------------------------
                ----"initialized. Log dir: ${fileManager.logDirPath()}---
                ---------------------------------------------------------
                """
            log("I", DEFAULT_TAG, messInit)
            Log.d(DEFAULT_TAG, messInit)
        }
        // Single writer coroutine — enforces FIFO; batches entries; flushes at 1 KB
        scope.launch {
            try {
                for (entry in logChannel) {
                    if (fileManager.bufferEntry(entry)) fileManager.flush()
                }
            } finally {
                // Drain remaining < 1 KB buffer on channel close or scope cancel
                fileManager.flush()
            }
        }
    }

    fun init(isEnable: Boolean) {
        this.isEnabled = isEnable
    }

    fun flush() {
        fileManager.flush()
    }

    fun zipLogs(onComplete: (Uri?) -> Unit) {
        scope.launch { fileManager.zipLogs(onComplete) }
    }

    fun clearLogFiles() {
        scope.launch { fileManager.clearAllLogFiles() }
    }

    fun close() = logChannel.close()
    // ── Internal ──────────────────────────────────────────────────────────────

    private fun log(level: String, tag: String, message: String, throwable: Throwable? = null) {
        if (!isEnabled) return
        logChannel.trySend(LogEntry(level, tag, message, throwable))
    }

    private fun applyEnabled(enabled: Boolean) {
        isEnabled = enabled
//        configManager.save(enabled)
    }

    private suspend fun fetchAndApplyRemote(url: String) {
        val remote = configManager.fetchRemoteEnabled(url) ?: return
        applyEnabled(remote)
    }

    // ── Companion — static API ────────────────────────────────────────────────

    companion object {
        const val DEFAULT_TAG = "LogHelper"

        @Volatile private var instance: LogHelper? = null

        // ── With explicit tag ─────────────────────────────────────────────────

        fun d(tag: String, message: String) = instance?.log("D", tag, message)
        fun i(tag: String, message: String) = instance?.log("I", tag, message)
        fun w(tag: String, message: String) = instance?.log("W", tag, message)
        fun e(tag: String, message: String, throwable: Throwable? = null) = instance?.log("E", tag, message, throwable)

        /** `{}` placeholders replaced by [args]; last [Throwable] arg used as cause for `e`. */
        fun d(tag: String, template: String, vararg args: Any?) = instance?.log("D", tag, formatMessage(template, args))
        fun i(tag: String, template: String, vararg args: Any?) = instance?.log("I", tag, formatMessage(template, args))
        fun w(tag: String, template: String, vararg args: Any?) = instance?.log("W", tag, formatMessage(template, args))
        fun e(tag: String, template: String, vararg args: Any?) {
            val throwable = if (args.isNotEmpty() && args.last() is Throwable) args.last() as Throwable else null
            val fmtArgs = if (throwable != null) args.copyOfRange(0, args.size - 1) else args
            instance?.log("E", tag, formatMessage(template, fmtArgs), throwable)
        }

        // ── Default tag (DEFAULT_TAG = "LogHelper") ───────────────────────────

        fun d(message: String) = instance?.log("D", DEFAULT_TAG, message)
        fun i(message: String) = instance?.log("I", DEFAULT_TAG, message)
        fun w(message: String) = instance?.log("W", DEFAULT_TAG, message)
        fun e(message: String, throwable: Throwable? = null) = instance?.log("E", DEFAULT_TAG, message, throwable)

        fun d(template: String, vararg args: Any?) = d(DEFAULT_TAG, template, *args)
        fun i(template: String, vararg args: Any?) = i(DEFAULT_TAG, template, *args)
        fun w(template: String, vararg args: Any?) = w(DEFAULT_TAG, template, *args)
        fun e(template: String, vararg args: Any?) = e(DEFAULT_TAG, template, *args)

        // ── Control ───────────────────────────────────────────────────────────

        /** Enables/disables logging; persisted to disk across restarts. */
        fun setEnabled(enabled: Boolean) = instance?.applyEnabled(enabled)

        /** Fetches `{ "enabled": Boolean }` from [url] and applies it. Call from a coroutine. */
        suspend fun syncRemoteConfig(url: String) = instance?.fetchAndApplyRemote(url)

        /** Closes the channel; writer drains and flushes remaining buffer. Call on app exit. */
        fun close() {
            instance?.run {
                flush()
                close()
            }
        }

        /** Current enabled state, or `null` if not yet initialised. */
        fun isEnabled(): Boolean = instance?.isEnabled == true

        /**
         * Zips all log files and invokes [onComplete] with the zip [Uri] on the IO thread,
         * or `null` if the operation fails.
         */
        fun zipLogs(onComplete: (Uri?) -> Unit) {
            instance?.zipLogs(onComplete)
        }

        /** Deletes all log files from disk and resets in-memory file state. */
        fun clearLogFiles() {
            instance?.clearLogFiles()
        }
    }
}
