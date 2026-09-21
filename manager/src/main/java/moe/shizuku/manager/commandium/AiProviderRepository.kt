package moe.shizuku.manager.commandium

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.shizuku.manager.ShizukuSettings
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Prefs-backed store for the user's AI providers plus the active selection.
 *
 * Each provider's API key lives in its own Keystore-encrypted pref entry
 * (comput_api_key_<id>), never in the provider JSON list.
 *
 * First access migrates the legacy single-provider prefs (name/url/model/key,
 * themselves already migrated from the old Gemini-only prefs) into one
 * provider entry, so all existing call sites keep working untouched.
 */
object AiProviderRepository {

    private const val KEY_PROVIDERS = "comput_ai_providers"
    private const val KEY_ACTIVE_ID = "comput_ai_active_id"
    private const val KEY_API_KEY_PREFIX = "comput_api_key_"

    internal const val LEGACY_API_KEY = "comput_api_key"
    internal const val LEGACY_NAME = "comput_ai_name"
    internal const val LEGACY_BASE_URL = "comput_ai_base_url"
    internal const val LEGACY_MODEL = "comput_ai_model"
    private const val KEY_MODELS_PREFIX = "comput_ai_models_"
    private const val MAX_CACHED_MODELS = 5000
    private const val MODEL_CACHE_TTL_MS = 24L * 60 * 60 * 1000
    internal const val LEGACY_DEFAULT_BASE_URL = "https://openrouter.ai/api/v1/"

    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "SheveryGeminiKey"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private val json = Json { ignoreUnknownKeys = true }

    private fun prefs() = ShizukuSettings.getPreferences()

    // -- provider list ---------------------------------------------------

    fun getProviders(): List<AiProvider> {
        migrateFromLegacyIfNeeded()
        val raw = prefs().getString(KEY_PROVIDERS, null) ?: return emptyList()
        val list = try {
            json.decodeFromString<List<AiProvider>>(raw)
        } catch (e: Throwable) {
            return emptyList()
        }
        // The first migration carried the old Gemini-era model string
        // ("gemini-3.6-flash" — a flat name that only exists on Google's
        // endpoint) into the provider JSON. On any OpenAI-style endpoint
        // (OpenRouter/Groq/...) that slug 404s as "model not found".
        // Drop flat gemini-only slugs once so requests carry a real model.
        var changed = false
        val clean = list.map { p ->
            // A flat gemini slug is only valid on Google's own OpenAI-compatible
            // endpoint; on any other host (OpenRouter/Groq/...) it 404s as
            // "model not found". Guard on the provider's own base URL so a real
            // Google Gemini provider keeps its picked model.
            val onGoogle = p.baseUrl.contains("generativelanguage.googleapis.com")
            if (!onGoogle && p.model.startsWith("gemini") && !p.model.contains("/")) {
                changed = true
                p.copy(model = "")
            } else p
        }
        if (changed) saveProviders(clean)
        return clean
    }

    private fun saveProviders(providers: List<AiProvider>) {
        prefs().edit().putString(KEY_PROVIDERS, json.encodeToString(providers)).apply()
    }

    fun getActiveId(): String? {
        migrateFromLegacyIfNeeded()
        return prefs().getString(KEY_ACTIVE_ID, null)
    }

    fun getActive(): AiProvider? {
        val providers = getProviders()
        if (providers.isEmpty()) return null
        val activeId = getActiveId()
        return providers.firstOrNull { it.id == activeId } ?: providers[0]
    }

    fun setActive(id: String) {
        prefs().edit().putString(KEY_ACTIVE_ID, id).apply()
    }

    fun add(name: String, baseUrl: String, model: String): AiProvider {
        val provider = AiProvider(
            id = UUID.randomUUID().toString(),
            name = name,
            baseUrl = baseUrl.trim(),
            model = model,
        )
        val updated = getProviders() + provider
        saveProviders(updated)
        if (updated.size == 1) setActive(provider.id)
        return provider
    }

    fun update(provider: AiProvider) {
        val current = getProviders()
        val oldBaseUrl = current.firstOrNull { it.id == provider.id }?.baseUrl
        saveProviders(current.map { if (it.id == provider.id) provider else it })
        if (oldBaseUrl != null && oldBaseUrl != provider.baseUrl) removeModelCache(provider.id)
    }

    /** Removes a provider. Refuses to remove the last one; returns false then. */
    fun remove(id: String): Boolean {
        val providers = getProviders()
        if (providers.size <= 1) return false
        saveProviders(providers.filterNot { it.id == id })
        prefs().edit().remove(KEY_API_KEY_PREFIX + id).apply()
        removeModelCache(id)
        if (getActiveId() == id) {
            getProviders().firstOrNull()?.let { setActive(it.id) }
        }
        return true
    }

    // -- per-provider discovered-model cache (bounded, scoped to base URL)) ----

    fun getCachedModels(id: String, baseUrl: String): List<String> {
        val raw = prefs().getString(KEY_MODELS_PREFIX + id, null) ?: return emptyList()
        val payload = try { json.decodeFromString<CachedModels>(raw) } catch (e: Throwable) { return emptyList() }
        // Filter on read too: caches written before the text-only filter may
        // still hold agentic/image/audio models.
        return if (payload.baseUrl == baseUrl) payload.models.filter { isTextModel(it) } else emptyList()
    }

    fun setCachedModels(id: String, baseUrl: String, models: List<String>) {
        if (models.isEmpty()) return
        prefs().edit().putString(
            KEY_MODELS_PREFIX + id,
            json.encodeToString(CachedModels(baseUrl, displayModels(baseUrl, models).take(MAX_CACHED_MODELS), System.currentTimeMillis()))
        ).apply()
    }

    /** Text-only model slugs are sorted for display; agentic research, computer
     * use, image/video/audio generation, embeddings, moderation and realtime/live
     * models can't answer a plain chat request and are hidden so they can't be
     * picked in the switcher or provider dialog. "image" also catches Google's
     * gemini-*-image and OpenAI's gpt-*-image generation models. */
    private fun isTextModel(model: String): Boolean {
        val slug = model.lowercase()
        val markers = listOf(
            "deep-research", "computer-use", "antigravity", "-live", "live-",
            "imagen", "veo", "nano-banana", "gpt-image", "image", "dall-e",
            "sora", "midjourney", "stable-diffusion", "sdxl", "pixart", "kolors",
            "ideogram", "playground", "flux", "embedding", "voyage", "bge",
            "rerank", "moderation", "whisper", "tts", "speech", "audio",
            "transcribe", "realtime", "voice",
        )
        return markers.none { slug.contains(it) }
    }

    /** Filtered, display-ordered model list: drop non-text models, then apply
     * the provider-aware sort (Google GA-first, others untouched). Used both
     * when caching and whenever a raw discovery list hits the UI. */
    fun displayModels(baseUrl: String, models: List<String>): List<String> =
        sortModelsForDisplay(baseUrl, models.filter { isTextModel(it) })

    /** Google models: current GA generation first so users don't keep landing on
     * the shutting-down gemini-2.* slugs (gemini-2.5-flash cut over Oct 16,
     * 2026). Stable within rank groups; untouched for every other provider. */
    private fun sortModelsForDisplay(baseUrl: String, models: List<String>): List<String> {
        if (!baseUrl.contains("generativelanguage.googleapis.com")) return models
        fun rank(model: String): Int = when {
            model.startsWith("gemini-3.") -> 0
            model.startsWith("gemini-2.") -> 1
            model.startsWith("gemini-1.") -> 2
            else -> 3
        }
        return models.sortedBy { rank(it) }
    }

    /** True when a cache entry is missing or older than the TTL (or corrupt). */
    fun isModelCacheStale(id: String, baseUrl: String): Boolean {
        val raw = prefs().getString(KEY_MODELS_PREFIX + id, null) ?: return true
        val payload = try { json.decodeFromString<CachedModels>(raw) } catch (e: Throwable) { return true }
        if (payload.baseUrl != baseUrl) return true
        val age = System.currentTimeMillis() - payload.fetchedAt
        return age > MODEL_CACHE_TTL_MS
    }

    fun removeModelCache(id: String) {
        prefs().edit().remove(KEY_MODELS_PREFIX + id).apply()
    }

    @Serializable
    private data class CachedModels(
        val baseUrl: String = "",
        val models: List<String> = emptyList(),
        val fetchedAt: Long = 0L,
    )

    // -- per-provider keys (Keystore-encrypted, same scheme as before) ---

    fun getKey(id: String): String {
        val raw = prefs().getString(KEY_API_KEY_PREFIX + id, "") ?: ""
        if (raw.isEmpty()) return ""
        if (!raw.contains(":")) {
            // Plain text from a fresh save that failed to encrypt; re-encrypt now.
            try {
                val encrypted = encrypt(raw)
                prefs().edit().putString(KEY_API_KEY_PREFIX + id, encrypted).apply()
                return raw
            } catch (e: Throwable) {
                return raw
            }
        }
        return try {
            decrypt(raw)
        } catch (e: Throwable) {
            ""
        }
    }

    fun setKey(id: String, value: String) {
        val encrypted = try {
            encrypt(value)
        } catch (e: Throwable) {
            value
        }
        prefs().edit().putString(KEY_API_KEY_PREFIX + id, encrypted).apply()
    }

    fun getActiveKey(): String {
        val active = getActive() ?: return ""
        return getKey(active.id)
    }

    fun setActiveKey(value: String) {
        val active = getActive() ?: return
        setKey(active.id, value)
    }

    // -- legacy migration --------------------------------------------------

    private fun migrateFromLegacyIfNeeded() {
        val prefs = prefs()
        if (prefs.contains(KEY_PROVIDERS)) return
        val name = prefs.getString(LEGACY_NAME, "") ?: ""
        val baseUrl = prefs.getString(LEGACY_BASE_URL, LEGACY_DEFAULT_BASE_URL)
            ?: LEGACY_DEFAULT_BASE_URL
        // Legacy model strings were Gemini-era flat names that 404 on
        // OpenAI-style endpoints; require an explicit re-pick after upgrade.
        val provider = AiProvider(
            id = UUID.randomUUID().toString(),
            name = name,
            baseUrl = baseUrl,
            model = "",
        )
        saveProviders(listOf(provider))
        prefs.edit().putString(KEY_ACTIVE_ID, provider.id).apply()
        // The old settings pref must not resurrect through ModuleSettings.
        prefs.edit().remove(LEGACY_MODEL).apply()
        // Carry the existing encrypted key onto the new entry untouched.
        val legacyKey = prefs.getString(LEGACY_API_KEY, "") ?: ""
        if (legacyKey.isNotEmpty()) {
            prefs.edit().putString(KEY_API_KEY_PREFIX + provider.id, legacyKey).apply()
        }
    }

    // -- keystore helpers (moved verbatim from ModuleSettings) -------------

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val key = keyStore.getKey(ALIAS, null) as? SecretKey
        if (key != null) return key

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
          
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val iv = cipher.iv
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val ivString = Base64.encodeToString(iv, Base64.NO_WRAP)
        val encryptedString = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        return "$ivString:$encryptedString"
    }

    private fun decrypt(cipherText: String): String {
        if (cipherText.isEmpty()) return ""
        val parts = cipherText.split(":")
        if (parts.size != 2) return ""
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val encryptedBytes = Base64.decode(parts[1], Base64.NO_WRAP)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
        val decryptedBytes = cipher.doFinal(encryptedBytes)
        return String(decryptedBytes, Charsets.UTF_8)
    }
}
