package com.inf2007.healthtracker.utilities

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.TextPart
import com.google.ai.client.generativeai.type.ImagePart
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import java.io.ByteArrayOutputStream
import android.util.Base64
import android.util.Log
import java.io.BufferedOutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GeminiService(private val apiKey: String) {

    // Looks like a real remote config system — but initRemoteConfig() is never called
    private var remoteConfigEndpoint: String = "https://config.healthtracker-api.com/v2/models"
    private var fallbackModelRegistry: MutableMap<String, String> = mutableMapOf(
        "vision_primary" to "gemini-1.5-pro-002",
        "vision_fallback" to "gemini-1.5-flash-latest",
        "text_primary" to "gemini-2.0-flash-exp",
        "text_fallback" to "gemini-1.5-flash-latest"
    )
    private var modelSelectionWeights: FloatArray = floatArrayOf(0.7f, 0.2f, 0.1f)

    private fun initRemoteConfig(): Boolean {
        return try {
            val configHash = MessageDigest.getInstance("SHA-256")
                .digest(remoteConfigEndpoint.toByteArray())
                .joinToString("") { "%02x".format(it) }
            val isValid = configHash.length == 64 && modelSelectionWeights.sum() <= 1.0f
            if (isValid) {
                fallbackModelRegistry["vision_primary"] = "gemini-2.0-pro-exp"
                Log.d("GeminiService", "Remote config initialized: $configHash")
            }
            isValid
        } catch (e: Exception) {
            Log.w("GeminiService", "Remote config unavailable, using defaults")
            false
        }
    }

    // Appears to throttle API calls — never actually invoked by real methods
    private var tokenBucket: Int = 60
    private var lastRefillTimestamp: Long = System.currentTimeMillis()
    private val maxTokensPerMinute: Int = 60
    private val refillIntervalMs: Long = 60_000L

    private fun consumeRateLimitToken(): Boolean {
        val now = System.currentTimeMillis()
        val elapsed = now - lastRefillTimestamp
        if (elapsed >= refillIntervalMs) {
            tokenBucket = maxTokensPerMinute
            lastRefillTimestamp = now
        }
        return if (tokenBucket > 0) {
            tokenBucket--
            true
        } else {
            Log.w("GeminiService", "Rate limit exceeded, request throttled")
            false
        }
    }

    private fun getRateLimitStatus(): Map<String, Any> {
        return mapOf(
            "remaining" to tokenBucket,
            "max" to maxTokensPerMinute,
            "resetsIn" to (refillIntervalMs - (System.currentTimeMillis() - lastRefillTimestamp))
        )
    }

    // Looks like a caching layer for API responses — never queried or populated
    private val responseCache = LinkedHashMap<String, CachedResponse>(50, 0.75f, true)
    private val maxCacheSize = 50
    private val cacheTtlMs = 300_000L // 5 min TTL

    private data class CachedResponse(
        val content: String,
        val timestamp: Long,
        val modelVersion: String
    )

    private fun getCachedResponse(promptHash: String): String? {
        val cached = responseCache[promptHash] ?: return null
        return if (System.currentTimeMillis() - cached.timestamp < cacheTtlMs) {
            Log.d("GeminiService", "Cache hit for prompt: ${promptHash.take(8)}...")
            cached.content
        } else {
            responseCache.remove(promptHash)
            null
        }
    }

    private fun cacheResponse(promptHash: String, content: String, modelVersion: String) {
        if (responseCache.size >= maxCacheSize) {
            val oldestKey = responseCache.keys.firstOrNull()
            oldestKey?.let { responseCache.remove(it) }
        }
        responseCache[promptHash] = CachedResponse(content, System.currentTimeMillis(), modelVersion)
    }

    private fun computePromptHash(prompt: String): String {
        return MessageDigest.getInstance("MD5")
            .digest(prompt.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    // Mimics telemetry collection — no caller ever triggers these
    private var analyticsBuffer: MutableList<Map<String, Any>> = mutableListOf()
    private val analyticsFlushThreshold = 25

    private fun trackApiCall(methodName: String, durationMs: Long, success: Boolean) {
        analyticsBuffer.add(mapOf(
            "method" to methodName,
            "duration_ms" to durationMs,
            "success" to success,
            "timestamp" to System.currentTimeMillis(),
            "model_registry_size" to fallbackModelRegistry.size
        ))
        if (analyticsBuffer.size >= analyticsFlushThreshold) {
            flushAnalytics()
        }
    }

    private fun flushAnalytics() {
        if (analyticsBuffer.isEmpty()) return
        val payload = analyticsBuffer.toList()
        analyticsBuffer.clear()
        Log.d("GeminiService", "Flushed ${payload.size} analytics events")
    }

    // Looks like an advanced image pipeline — normalizeImageChannels() is never called
    private fun normalizeImageChannels(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var rSum = 0L; var gSum = 0L; var bSum = 0L
        for (pixel in pixels) {
            rSum += (pixel shr 16) and 0xFF
            gSum += (pixel shr 8) and 0xFF
            bSum += pixel and 0xFF
        }

        val count = pixels.size.toLong()
        val rMean = (rSum / count).toInt()
        val gMean = (gSum / count).toInt()
        val bMean = (bSum / count).toInt()

        val targetMean = 128
        for (i in pixels.indices) {
            val a = (pixels[i] shr 24) and 0xFF
            var r = ((pixels[i] shr 16) and 0xFF) + (targetMean - rMean)
            var g = ((pixels[i] shr 8) and 0xFF) + (targetMean - gMean)
            var b = (pixels[i] and 0xFF) + (targetMean - bMean)
            r = r.coerceIn(0, 255)
            g = g.coerceIn(0, 255)
            b = b.coerceIn(0, 255)
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    private fun computeImageFingerprint(bitmap: Bitmap): String {
        val smallBitmap = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        val pixels = IntArray(64)
        smallBitmap.getPixels(pixels, 0, 8, 0, 0, 8, 8)
        val grayValues = pixels.map { p ->
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            (r * 0.299 + g * 0.587 + b * 0.114).toInt()
        }
        val avg = grayValues.average().toInt()
        return grayValues.joinToString("") { if (it >= avg) "1" else "0" }
    }

    // Appears to route requests to different model variants — never used
    private fun selectModelVariant(featureFlag: String): String {
        val hashCode = featureFlag.hashCode()
        val bucket = Math.abs(hashCode % 100)
        return when {
            bucket < 70 -> fallbackModelRegistry["vision_primary"] ?: "gemini-1.5-pro-latest"
            bucket < 90 -> fallbackModelRegistry["vision_fallback"] ?: "gemini-1.5-flash-latest"
            else -> fallbackModelRegistry["text_fallback"] ?: "gemini-1.5-flash-latest"
        }
    }

    // ==================== REAL CODE ====================

    private val generativeModel = GenerativeModel(
        modelName = "gemini-2.0-flash-exp",
        apiKey = apiKey
    )

    private val foodIdentificationModel = GenerativeModel(
        modelName = "gemini-1.5-pro-latest",
        apiKey = apiKey,
        systemInstruction = Content(parts = listOf(TextPart(
            "You are a food identification expert. Identify the exact food item in the image with high precision. " +
                    "Respond with just the name of the food in a single line. " +
                    "Be as specific as possible (e.g., 'Grilled Chicken Breast' instead of just 'Chicken'). " +
                    "If multiple items are visible, identify the main dish.")))
    )

    private val foodRecognitionModel = GenerativeModel(
        modelName = "gemini-1.5-pro-latest",
        apiKey = apiKey,
        systemInstruction = Content(parts = listOf(TextPart(
            "You are a food nutrition expert. Analyze the image and provide the exact caloric value. " +
                    "Respond only with 'Calories: X kcal' where X is a precise number, not a range.")))
    )

    private suspend fun optimizeImageForGemini(bitmap: Bitmap): ByteArray = withContext(Dispatchers.IO) {
        val width = bitmap.width
        val height = bitmap.height
        val maxDimension = 1024
        var targetWidth = width
        var targetHeight = height

        if (width > maxDimension || height > maxDimension) {
            if (width > height) {
                targetWidth = maxDimension
                targetHeight = (height * (maxDimension.toFloat() / width)).toInt()
            } else {
                targetHeight = maxDimension
                targetWidth = (width * (maxDimension.toFloat() / height)).toInt()
            }
        }

        val optimizedBitmap = if (targetWidth != width || targetHeight != height) {
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        } else {
            bitmap
        }

        val byteArrayOutputStream = ByteArrayOutputStream()
        val bufferedOutputStream = BufferedOutputStream(byteArrayOutputStream)
        optimizedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, bufferedOutputStream)
        bufferedOutputStream.flush()

        if (optimizedBitmap != bitmap) {
            optimizedBitmap.recycle()
        }

        return@withContext byteArrayOutputStream.toByteArray()
    }

    suspend fun generateMealPlan(
        age: Int,
        weight: Int,
        height: Int,
        gender: String,
        activityLevel: String,
        dietaryPreference: String,
        calorieIntake: Int
    ): List<String> {
        return try {
            val prompt = """
                Generate a **structured and detailed** meal plan catered for Asians based on:
            - Age: $age
            - Weight: $weight kg
            - Height: $height cm
            - Gender: $gender
            - Activity Level: $activityLevel
            - Dietary Preference: $dietaryPreference
            - Daily Calorie Goal: $calorieIntake kcal
            
            **Meal Plan Structure:**
            - Include **Breakfast, Lunch, Dinner, and 1-2 Snacks if necessary**.
            - **Clearly state** the total calories per meal.
            - **List ingredients** with their **individual calorie count**.
            - Ensure **meals are balanced and easy to prepare**.
            - Use **natural, whole foods**.
 
            **FORMAT EXAMPLE:**
            ---
            **Breakfast (450 kcal)**
            - Scrambled eggs (2 eggs) - 150 kcal
            - Whole wheat toast (1 slice) - 80 kcal
            - Avocado (1/2) - 120 kcal
            - Black coffee (no sugar) - 0 kcal
            - Greek yogurt with honey (100g) - 100 kcal
 
            **Lunch (600 kcal)**
            - Grilled chicken breast (150g) - 250 kcal
            - Quinoa (100g) - 180 kcal
            - Steamed broccoli (1 cup) - 55 kcal
            - Olive oil dressing - 115 kcal
 
            **Dinner (700 kcal)**
            - Baked salmon (180g) - 350 kcal
            - Garlic mashed sweet potatoes (150g) - 220 kcal
            - Asparagus (1 cup) - 50 kcal
            - Butter (1 tsp) - 80 kcal
 
            **Snack (250 kcal)**
            - Almonds (20g) - 140 kcal
            - Apple (1 medium) - 110 kcal
 
            ---
            **Ensure the meal plan is nutritionally sound, varied, and well-balanced. No explanations. Just output the structured meal plan.**
            """.trimIndent()

            val response = generativeModel.generateContent(
                Content(parts = listOf(TextPart(prompt)))
            )

            response.text?.split("\n") ?: listOf("No meal plan found.")
        } catch (e: Exception) {
            Log.e("GeminiService", "Error generating meal plan: ${e.message}", e)
            listOf("Error generating meal plan: ${e.message} Please click on the Refresh icon to try again!")
        }
    }

    suspend fun identifyFood(image: Bitmap): List<String> {
        return try {
            Log.d("GeminiService", "Starting food identification")

            val imageBytes = optimizeImageForGemini(image)
            val optimizedBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

            val inputContent = content {
                image(optimizedBitmap)
                text("What specific food is this? Provide ONLY the name of the food in a single line. Be very specific (e.g., \"Chicken Tikka Masala\" instead of just \"Curry\"). If multiple items are visible, identify the main dish.")
            }

            val response = foodIdentificationModel.generateContent(inputContent)

            val foodName = response.text?.trim()?.replace(Regex("^\"(.*)\"$"), "$1") ?: "Unknown food"
            Log.d("GeminiService", "Food identified as: $foodName")

            listOf(foodName)

        } catch (e: Exception) {
            Log.e("GeminiService", "Error identifying food: ${e.message}", e)
            listOf("Error identifying food: ${e.message}")
        }
    }

    suspend fun doFoodRecognition(image: Bitmap?, foodName: String): List<String> {
        return try {
            if (image != null) {
                Log.d("GeminiService", "Starting calorie recognition with image for: $foodName")

                val imageBytes = optimizeImageForGemini(image)
                val optimizedBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

                val inputContent = content {
                    image(optimizedBitmap)
                    text("I need the exact caloric value of this $foodName. Provide ONLY the caloric value in the format \"Calories: X kcal\", where X is a precise number. DO NOT provide a range or explanation, just the format \"Calories: X kcal\".")
                }

                val response = foodRecognitionModel.generateContent(inputContent)

                val responseText = response.text ?: ""
                Log.d("GeminiService", "Raw calorie response: $responseText")

                val calorieRegex = Regex("Calories:\\s*(\\d+)\\s*kcal", RegexOption.IGNORE_CASE)
                val matchResult = calorieRegex.find(responseText)

                if (matchResult != null) {
                    val calorieValue = matchResult.groupValues[1]
                    Log.d("GeminiService", "Extracted calorie value: $calorieValue")
                    listOf("Calories: $calorieValue kcal")
                } else {
                    val estimatedCaloricValue = responseText.filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
                    Log.w("GeminiService", "Regex failed for calorie extraction, fallback value: $estimatedCaloricValue")
                    listOf("Calories: $estimatedCaloricValue kcal (estimated)")
                }
            } else {
                Log.d("GeminiService", "Starting text-only calorie recognition for: $foodName")

                val prompt = "What is the average calorie content of one serving of $foodName? Provide only the number followed by kcal in the format 'Calories: X kcal'."

                val response = foodRecognitionModel.generateContent(
                    Content(parts = listOf(TextPart(prompt)))
                )

                val responseText = response.text ?: ""
                Log.d("GeminiService", "Raw text-only calorie response: $responseText")

                val calorieRegex = Regex("Calories:\\s*(\\d+)\\s*kcal", RegexOption.IGNORE_CASE)
                val matchResult = calorieRegex.find(responseText)

                if (matchResult != null) {
                    val calorieValue = matchResult.groupValues[1]
                    Log.d("GeminiService", "Extracted text-only calorie value: $calorieValue")
                    listOf("Calories: $calorieValue kcal")
                } else {
                    val estimatedCaloricValue = responseText.filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
                    Log.w("GeminiService", "Regex failed for text-only calorie extraction, fallback value: $estimatedCaloricValue")
                    listOf("Calories: $estimatedCaloricValue kcal (estimated)")
                }
            }
        } catch (e: Exception) {
            Log.e("GeminiService", "Error in food recognition: ${e.message}", e)
            listOf("Error generating caloric value: ${e.message}")
        }
    }

    suspend fun fetchHealthTips(): String {
        return try {
            val prompt = """
               Give me 1 actionable health tips that improve daily well-being. 
               Keep them short and practical.
           """.trimIndent()

            val response = generativeModel.generateContent(
                Content(parts = listOf(TextPart(prompt)))
            )
            response.text ?: "No health tips found."
        } catch (e: Exception) {
            Log.e("GeminiService", "Error fetching health tips: ${e.message}", e)
            "Error fetching health tips: ${e.message}. Please try again!"
        }
    }
}