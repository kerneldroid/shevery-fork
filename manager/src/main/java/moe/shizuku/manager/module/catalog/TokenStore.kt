package moe.shizuku.manager.module.catalog

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.GeneralSecurityException

object TokenStore {
    private const val PREFS_NAME = "catalog_token_prefs"
    private const val KEY_GITHUB_PAT = "github_pat"
    private const val TAG = "TokenStore"

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    private fun createEncryptedPrefs(appContext: Context, masterKey: MasterKey): SharedPreferences {
        return EncryptedSharedPreferences.create(
            appContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun getOrCreateEncryptedPrefs(context: Context): SharedPreferences {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return try {
            createEncryptedPrefs(appContext, masterKey)
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "EncryptedSharedPreferences creation failed (keystore), migrating: ${e.message}")
            migrateAndRecreate(appContext, masterKey)
        } catch (e: IOException) {
            Log.w(TAG, "EncryptedSharedPreferences creation failed (IO), migrating: ${e.message}")
            migrateAndRecreate(appContext, masterKey)
        }
    }

    /**
     * Atomic migration: read legacy plaintext token, create new encrypted prefs,
     * copy token in, delete legacy file. If any step fails, legacy file (and token) survives.
     */
    private fun migrateAndRecreate(appContext: Context, masterKey: MasterKey): SharedPreferences {
        var legacyToken: String? = null
        try {
            val plain = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            legacyToken = plain.getString(KEY_GITHUB_PAT, null)
        } catch (_: Exception) {
            // ignore — best-effort migration
        }

        val fresh = try {
            createEncryptedPrefs(appContext, masterKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create new encrypted prefs; legacy file kept", e)
            throw e
        }

        if (!legacyToken.isNullOrBlank()) {
            try {
                fresh.edit().putString(KEY_GITHUB_PAT, legacyToken).apply()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist migrated token into encrypted prefs", e)
                throw e
            }
        }

        synchronized(this) { cachedPrefs = null }
        try {
            appContext.deleteSharedPreferences(PREFS_NAME)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete legacy prefs after migration (non-fatal)", e)
        }
        return fresh
    }

    private fun getCachedPrefs(context: Context): SharedPreferences {
        return cachedPrefs ?: synchronized(this) {
            cachedPrefs ?: getOrCreateEncryptedPrefs(context).also { cachedPrefs = it }
        }
    }

    fun getToken(context: Context): String? {
        return getCachedPrefs(context).getString(KEY_GITHUB_PAT, null)
    }

    fun setToken(context: Context, token: String) {
        getCachedPrefs(context).edit().putString(KEY_GITHUB_PAT, token).apply()
    }

    fun clearToken(context: Context) {
        getCachedPrefs(context).edit().remove(KEY_GITHUB_PAT).apply()
    }

    fun isValidTokenFormat(token: String): Boolean {
        val trimmed = token.trim()
        return trimmed.length >= 30 &&
                (trimmed.startsWith("ghp_") || trimmed.startsWith("github_pat_"))
    }
}
