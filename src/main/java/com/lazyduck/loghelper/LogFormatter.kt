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
 * Replaces `{}` placeholders in [template] with [args] in order (SLF4J-style).
 *
 * - Each `{}` is replaced with the next arg via [formatArg].
 * - Extra `{}` with no matching arg are left as-is.
 * - Extra args with no matching `{}` are silently ignored.
 *
 * Examples:
 * ```
 * formatMessage("user {} logged in", arrayOf("Alice"))      → "user Alice logged in"
 * formatMessage("ids={}", arrayOf(listOf(1, 2, 3)))         → "ids=[1, 2, 3]"
 * formatMessage("{} → {}", arrayOf(mapOf("a" to 1), null)) → "{a=1} → null"
 * ```
 */
internal fun formatMessage(template: String, args: Array<out Any?>): String {
    if (args.isEmpty()) return template
    val sb = StringBuilder(template.length + args.size * 16)
    var argIdx = 0
    var i = 0
    while (i < template.length) {
        // Detect "{}" placeholder
        if (i < template.length - 1 && template[i] == '{' && template[i + 1] == '}') {
            sb.append(if (argIdx < args.size) formatArg(args[argIdx++]) else "{}")
            i += 2
        } else {
            sb.append(template[i++])
        }
    }
    return sb.toString()
}

/**
 * Converts a single argument to its log-friendly string representation.
 *
 * | Type              | Output example                        |
 * |-------------------|---------------------------------------|
 * | `null`            | `"null"`                              |
 * | Primitive / other | `toString()`                          |
 * | `ByteArray`       | `"[1, 2, 3]"`                         |
 * | `Array<*>`        | `"[a, [b, c]]"` (deep)               |
 * | `Collection<*>`   | `"[1, 2, 3]"`                         |
 * | `Map<*, *>`       | `"{key1=val1, key2=val2}"`            |
 */
internal fun formatArg(arg: Any?): String = when (arg) {
    null -> "null"
    is ByteArray -> arg.contentToString()
    is Array<*> -> arg.joinToString(prefix = "[", postfix = "]") { formatArg(it) }
    is Collection<*> -> arg.joinToString(prefix = "[", postfix = "]") { formatArg(it) }
    is Map<*, *> -> arg.entries.joinToString(prefix = "{", postfix = "}") { (k, v) -> "${formatArg(k)}=${formatArg(v)}" }
    else -> arg.toString()
}
