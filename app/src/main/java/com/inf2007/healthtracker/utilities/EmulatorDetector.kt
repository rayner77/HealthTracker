package com.inf2007.healthtracker.utilities

import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import android.provider.Settings
import android.util.Log
import java.io.File

object EmulatorDetector {
    private const val TAG = "EmulatorDetector"

    fun isRunningOnEmulator(context: Context): Boolean {
        Log.d(TAG, "===== Emulator Detection Start =====")

        if (checkBuildProperties()) {
            Log.d(TAG, "Detected via Build properties")
            return true
        }

        if (checkTelephonyManager(context)) {
            Log.d(TAG, "Detected via TelephonyManager")
            return true
        }

        if (checkDeviceIds(context)) {
            Log.d(TAG, "Detected via Device IDs")
            return true
        }

        if (checkFiles()) {
            Log.d(TAG, "Detected via Emulator files")
            return true
        }

        if (checkEmulatorSpecificProperties()) {
            Log.d(TAG, "Detected via Emulator-specific properties")
            return true
        }

        Log.d(TAG, "No emulator indicators found")
        return false
    }

    private fun checkBuildProperties(): Boolean {
        val brand = Build.BRAND ?: ""
        val model = Build.MODEL ?: ""
        val manufacturer = Build.MANUFACTURER ?: ""
        val hardware = Build.HARDWARE ?: ""
        val product = Build.PRODUCT ?: ""
        val fingerprint = Build.FINGERPRINT ?: ""
        val device = Build.DEVICE ?: ""

        Log.d(TAG, "BRAND: $brand")
        Log.d(TAG, "MODEL: $model")
        Log.d(TAG, "MANUFACTURER: $manufacturer")
        Log.d(TAG, "HARDWARE: $hardware")
        Log.d(TAG, "PRODUCT: $product")
        Log.d(TAG, "DEVICE: $device")
        Log.d(TAG, "FINGERPRINT: $fingerprint")

        val result =
            fingerprint.startsWith("generic") ||
                    fingerprint.startsWith("unknown") ||
                    model.contains("sdk", ignoreCase = true) ||
                    model.contains("emulator", ignoreCase = true) ||
                    model.contains("android sdk built for x86", ignoreCase = true) ||
                    manufacturer.contains("Genymotion", ignoreCase = true) ||
                    hardware.contains("goldfish", ignoreCase = true) ||
                    hardware.contains("ranchu", ignoreCase = true) ||
                    product.contains("sdk", ignoreCase = true) ||
                    product.contains("emulator", ignoreCase = true) ||
                    product.contains("simulator", ignoreCase = true) ||
                    device.contains("generic", ignoreCase = true)

        Log.d(TAG, "Build check result: $result")
        return result
    }

    private fun checkTelephonyManager(context: Context): Boolean {
        Log.d(TAG, "--- TelephonyManager ---")
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        return try {
            val networkOperator = tm.networkOperatorName
            val networkCountry = tm.networkCountryIso
            val simOperator = tm.simOperatorName

            Log.d(TAG, "networkOperatorName: $networkOperator")
            Log.d(TAG, "networkCountryIso: $networkCountry")
            Log.d(TAG, "simOperatorName: $simOperator")

            val result =
                networkOperator.isNullOrEmpty() &&
                        networkCountry.isNullOrEmpty() &&
                        simOperator.isNullOrEmpty()

            Log.d(TAG, "Telephony check result: $result")
            result
        } catch (e: SecurityException) {
            Log.d(TAG, "Telephony check skipped (no permission)")
            false
        }
    }

    private fun checkDeviceIds(context: Context): Boolean {
        Log.d(TAG, "--- Device IDs ---")
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )

        Log.d(TAG, "ANDROID_ID: $androidId")

        val result =
            androidId == "9774d56d682e549c" ||
                    androidId == "0000000000000000" ||
                    androidId?.matches(Regex("0+")) == true

        Log.d(TAG, "Device ID check result: $result")
        return result
    }

    private fun checkFiles(): Boolean {
        Log.d(TAG, "--- File Checks ---")
        val emulatorFiles = listOf(
            "/system/bin/qemu-props",
            "/dev/socket/qemud",
            "/dev/qemu_pipe",
            "/system/lib/libc_malloc_debug_qemu.so",
            "/sys/qemu_trace",
            "/system/bin/qemu-android"
        )

        emulatorFiles.forEach {
            Log.d(TAG, "Checking file: $it -> exists: ${File(it).exists()}")
        }

        val result = emulatorFiles.any { File(it).exists() }
        Log.d(TAG, "File check result: $result")
        return result
    }

    private fun checkEmulatorSpecificProperties(): Boolean {
        Log.d(TAG, "--- Emulator Specific Properties ---")

        val board = Build.BOARD
        val bootloader = Build.BOOTLOADER
        val radio = Build.getRadioVersion()

        Log.d(TAG, "BOARD: $board")
        Log.d(TAG, "BOOTLOADER: $bootloader")
        Log.d(TAG, "RADIO: $radio")

        val result =
            board.isNullOrEmpty() &&
                    bootloader.isNullOrEmpty() &&
                    radio.isNullOrEmpty()

        Log.d(TAG, "Emulator-specific check result: $result")
        return result
    }
}