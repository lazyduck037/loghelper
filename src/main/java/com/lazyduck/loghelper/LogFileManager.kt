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
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal const val LOG_DIR = "mail_logs"
private const val MAX_FILE_BYTES = 5 * 1024 * 1024L // 5 MB per file
private const val KEEP_DAYS = 3L
private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L
internal const val FLUSH_THRESHOLD = 1024 // flush batch when ≥ 1 KB

/**
 * Manages log file writes, rotation, and old-file cleanup.
 *
 * ## Thread safety
 * All public methods are called exclusively from the single writer coroutine in [LogHelper]
 * (on `Dispatchers.IO`) — no synchronisation is required here.
 *
 * ## No disk I/O on construction
 * Koin creates this class on the **main thread**, so the constructor must be pure.
 * - [logDir] uses `by lazy` → `mkdirs()` runs on first access (IO thread).
 * - File state ([currentFile], [currentFileSize]) is initialised on the first [flush] call
 *   via [ensureFileInited] — also on the IO thread.
 *
 * ## I/O strategy
 * - [bufferEntry] is pure memory: formats the line, appends to [buffer], no disk I/O.
 * - [flush] does a single `appendText` for the whole batch; checks rotation and day-rollover
 *   using the in-memory [currentFileSize] counter — no per-entry `File.length()` calls.
 * - [deleteOldFiles] is called once at startup from the background coroutine in [LogHelper].
 *
 * File naming: `mail_log_YYYYMMDD.log` → `mail_log_YYYYMMDD_1.log` → …
 */
internal class LogFileManager(private val context: Context) {

    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    // Lazy: mkdirs() runs on first access (IO thread), not during construction (main thread)
    private val logDir by lazy { File(context.filesDir, LOG_DIR).also { it.mkdirs() } }

    // ── In-memory file state — initialised lazily on first flush (IO thread) ─

    private var fileInited = false
    private var currentDay = dayFmt.format(Date()) // pure memory — safe on main thread
    private lateinit var currentFile: File
    private var currentFileSize = 0L

    // ── Batch buffer ──────────────────────────────────────────────────────────

    private val buffer = StringBuilder()
    private var pendingBytes = 0

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Formats [entry] and appends it to the in-memory batch buffer. **No disk I/O.**
     *
     * @return `true` when accumulated bytes have reached [FLUSH_THRESHOLD] (1 KB).
     *         Caller ([LogHelper]) should call [flush] when this returns true.
     */
    fun bufferEntry(entry: LogEntry): Boolean {
        val line = buildString {
            append(tsFmt.format(Date()))
            append(" [tid=${entry.threadId}]")
            append(" ${entry.level}/${entry.tag}: ${entry.message}")
            entry.throwable?.let { append("\n${it.stackTraceToString()}") }
            append("\n")
        }
        buffer.append(line)
        pendingBytes += line.toByteArray(Charsets.UTF_8).size
        return pendingBytes >= FLUSH_THRESHOLD
    }

    /** Returns the absolute path of the log directory (triggers [logDir] lazy init). */
    internal fun logDirPath(): String = logDir.absolutePath

    /**
     * Writes the in-memory buffer to disk in **one** `appendText` call, then resets it.
     *
     * On first call: resolves the current log file and reads its size from disk (once only).
     * Subsequent calls use the in-memory [currentFileSize] counter — no `File.length()`.
     *
     * Handles day-rollover and 5 MB rotation before writing.
     * No-op when the buffer is empty.
     */
    fun flush() {
        ensureFileInited()
        if (pendingBytes == 0) return

        // Day rolled over → open a fresh file for today
        val today = dayFmt.format(Date())
        if (today != currentDay) {
            currentDay = today
            currentFile = File(logDir, "mail_log_$today.log")
            currentFileSize = currentFile.length() // new day's file may already exist on restart
        }

        // In-memory size check — no File.length() call
        if (currentFileSize + pendingBytes >= MAX_FILE_BYTES) {
            currentFile = nextFileForDay(currentDay)
            currentFileSize = 0L
        }

        // Single disk write for the entire batch
        currentFile.appendText(buffer.toString(), Charsets.UTF_8)
        currentFileSize += pendingBytes

        buffer.clear()
        pendingBytes = 0
    }

    /**
     * Zips all files in [logDir] into a single archive stored in [Context.cacheDir].
     * Invokes [onComplete] with the [Uri] of the zip, or `null` if the operation fails.
     * Must be called from the IO-thread writer coroutine (same as other public methods).
     */
    fun zipLogs(onComplete: (Uri?) -> Unit) {
        try {
            val zipFile = File(context.cacheDir, "mail_logs_${System.currentTimeMillis()}.zip")
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                logDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        zos.putNextEntry(ZipEntry(file.name))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            onComplete(Uri.fromFile(zipFile))
        } catch (e: Exception) {
            onComplete(null)
        }
    }

    /** Deletes all log files in [logDir] and resets the in-memory file state. */
    fun clearAllLogFiles() {
        logDir.listFiles { f -> f.name.startsWith("mail_log_") }?.forEach { it.delete() }
        fileInited = false
        currentFileSize = 0
    }

    /** Removes log files last modified more than 3 days ago. Called once at [LogHelper] init. */
    fun deleteOldFiles() {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * MILLIS_PER_DAY
        // logDir lazy init happens here — safe, runs on IO thread
        logDir.listFiles { f ->
            f.name.startsWith("mail_log_") && f.lastModified() < cutoff
        }?.forEach { it.delete() }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Resolves [currentFile] and reads its size from disk exactly once per process lifetime.
     * Subsequent calls are a no-op (guard flag [fileInited]).
     * Always runs on the IO-thread writer coroutine.
     */
    private fun ensureFileInited() {
        if (fileInited) return
        currentFile = resolveCurrentFile()
        currentFileSize = currentFile.length()
        fileInited = true
    }

    /**
     * Scans today's file chain and returns the first file under the 5 MB cap.
     * Called once per process (from [ensureFileInited]).
     */
    private fun resolveCurrentFile(): File {
        var file = File(logDir, "mail_log_$currentDay.log")
        if (!file.exists() || file.length() < MAX_FILE_BYTES) return file
        var idx = 1
        while (true) {
            val candidate = File(logDir, "mail_log_${currentDay}_$idx.log")
            if (!candidate.exists() || candidate.length() < MAX_FILE_BYTES) return candidate
            idx++
        }
    }

    /**
     * Returns the next suffix file for [day] that is still under the 5 MB cap.
     * Called only when rotation is triggered inside [flush].
     */
    private fun nextFileForDay(day: String): File {
        var idx = 1
        while (true) {
            val candidate = File(logDir, "mail_log_${day}_$idx.log")
            if (!candidate.exists() || candidate.length() < MAX_FILE_BYTES) return candidate
            idx++
        }
    }
}
