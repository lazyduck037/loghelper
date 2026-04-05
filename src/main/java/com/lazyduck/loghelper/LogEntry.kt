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

/**
 * Single pending log entry buffered in the Channel before file write.
 *
 * [threadId] is captured at call-site (the thread that called LogHelper.d/i/w/e),
 * not at write time, so it reflects the originating thread even after batching.
 */
internal data class LogEntry(
    val level: String,
    val tag: String,
    val message: String,
    val throwable: Throwable? = null,
    val threadId: Long = Thread.currentThread().id,
)
