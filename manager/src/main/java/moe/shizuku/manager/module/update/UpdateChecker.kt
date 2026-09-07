package moe.shizuku.manager.module.update

import android.util.Log
import moe.shizuku.manager.module.AdbModule
import moe.shizuku.manager.module.discovery.DiscoveredModule
import moe.shizuku.manager.module.discovery.RateLimitTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class UpdateChecker private constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val rateLimit = RateLimitTracker()

    suspend fun checkUpdate(
        module: AdbModule,
        catalogModules: List<DiscoveredModule> = emptyList(),
        githubPat: String? = null
    ): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val moduleProp = readModuleProp(module)
            val updateJsonUrl = moduleProp["updateJson"]

            // 1. Check via explicit updateJson if provided in module.prop
            if (!updateJsonUrl.isNullOrBlank()) {
                val jsonResult = checkViaUpdateJson(module, updateJsonUrl, githubPat)
                if (jsonResult.hasUpdate) {
                    return@withContext jsonResult
                }
            }

            // 2. Check via catalog comparison
            val matchingCatalog = catalogModules.firstOrNull {
                it.moduleId.equals(module.id, ignoreCase = true)
            }

            if (matchingCatalog != null) {
                return@withContext checkViaCatalog(module, matchingCatalog, githubPat)
            }

            // 3. Fallback: check if module.prop contains repository url
            val repoUrl = moduleProp["url"] ?: moduleProp["repo"]
            if (!repoUrl.isNullOrBlank() && repoUrl.contains("github.com/")) {
                val path = repoUrl.substringAfter("github.com/").trim('/')
                val parts = path.split("/")
                if (parts.size >= 2) {
                    val owner = parts[0]
                    val repoName = parts[1].removeSuffix(".git")
                    return@withContext checkViaReleases(module, owner, repoName, githubPat)
                }
            }

            UpdateResult(
                moduleId = module.id,
                hasUpdate = false,
                currentVersion = module.version,
                currentVersionCode = module.versionCode,
                latestVersion = null,
                latestVersionCode = null,
                downloadUrl = null,
                changelog = null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed for ${module.id}", e)
            UpdateResult(
                moduleId = module.id,
                hasUpdate = false,
                currentVersion = module.version,
                currentVersionCode = module.versionCode,
                latestVersion = null,
                latestVersionCode = null,
                downloadUrl = null,
                changelog = null
            )
        }
    }

    private fun readModuleProp(module: AdbModule): Map<String, String> {
        val propsFile = module.directory.resolve("module.prop")
        if (!propsFile.isFile) return emptyMap()
        return propsFile.readText().lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("=") }
            .associate {
                val index = it.indexOf('=')
                it.substring(0, index).trim() to it.substring(index + 1).trim()
            }
    }

    private fun checkViaUpdateJson(
        module: AdbModule,
        updateJsonUrl: String,
        githubPat: String?
    ): UpdateResult {
        val request = buildRequest(updateJsonUrl, githubPat)
        val response = client.newCall(request).execute()

        val unsuccessful = UpdateResult(
            moduleId = module.id,
            hasUpdate = false,
            currentVersion = module.version,
            currentVersionCode = module.versionCode,
            latestVersion = null,
            latestVersionCode = null,
            downloadUrl = null,
            changelog = null
        )

        response.use { resp ->
            rateLimit.update(resp.headers)

            if (!resp.isSuccessful) return unsuccessful

            val body = resp.body?.string() ?: return unsuccessful

            val updateInfo = json.decodeFromString<UpdateJsonResponse>(body)
            val latestVersionCode = updateInfo.versionCode
            val currentVersionCode = module.versionCode

            val hasUpdate = if (latestVersionCode != null && currentVersionCode != null) {
                latestVersionCode > currentVersionCode
            } else if (updateInfo.version != null && module.version != null) {
                compareSemanticVersions(updateInfo.version, module.version) > 0
            } else {
                false
            }

            return UpdateResult(
                moduleId = module.id,
                hasUpdate = hasUpdate,
                currentVersion = module.version,
                currentVersionCode = module.versionCode,
                latestVersion = updateInfo.version,
                latestVersionCode = updateInfo.versionCode,
                downloadUrl = updateInfo.zipUrl,
                changelog = updateInfo.changelog
            )
        }
    }

    private fun checkViaCatalog(
        module: AdbModule,
        catalog: DiscoveredModule,
        githubPat: String?
    ): UpdateResult {
        val owner = catalog.repoFullName.substringBefore('/')
        val repo = catalog.repoFullName.substringAfter('/')

        val currentVersionCode = module.versionCode
        val catalogVersionCode = catalog.versionCode

        val currentVersion = module.version
        val catalogVersion = catalog.version

        val hasUpdate = if (catalogVersionCode != null && currentVersionCode != null) {
            catalogVersionCode > currentVersionCode
        } else if (!catalogVersion.isNullOrBlank() && !currentVersion.isNullOrBlank()) {
            compareSemanticVersions(catalogVersion, currentVersion) > 0
        } else {
            false
        }

        var downloadUrl: String? = null
        var changelog = catalog.description

        if (hasUpdate) {
            try {
                val rel = fetchLatestRelease(owner, repo, githubPat)
                if (rel != null) {
                    val zipAsset = rel.assets.firstOrNull { asset ->
                        asset.name.endsWith(".zip", ignoreCase = true) &&
                                (asset.name.contains(catalog.moduleId, ignoreCase = true) ||
                                        asset.name.contains(repo, ignoreCase = true))
                    } ?: rel.assets.firstOrNull { it.name.endsWith(".zip", ignoreCase = true) }
                    downloadUrl = zipAsset?.browserDownloadUrl
                    if (!rel.body.isNullOrBlank()) {
                        changelog = rel.body
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not fetch release assets for ${catalog.repoFullName}", e)
            }
        }

        return UpdateResult(
            moduleId = module.id,
            hasUpdate = hasUpdate,
            currentVersion = module.version,
            currentVersionCode = module.versionCode,
            latestVersion = catalog.version,
            latestVersionCode = catalog.versionCode,
            downloadUrl = downloadUrl ?: catalog.repoUrl,
            changelog = changelog,
            repoOwner = owner,
            repoName = repo,
            subPath = catalog.subPath
        )
    }

    private fun checkViaReleases(
        module: AdbModule,
        owner: String,
        repoName: String,
        githubPat: String?
    ): UpdateResult {
        val url = "https://api.github.com/repos/$owner/$repoName/releases/latest"

        val request = buildRequest(url, githubPat)
        val response = client.newCall(request).execute()

        val unsuccessful = UpdateResult(
            moduleId = module.id,
            hasUpdate = false,
            currentVersion = module.version,
            currentVersionCode = module.versionCode,
            latestVersion = null,
            latestVersionCode = null,
            downloadUrl = null,
            changelog = null
        )

        response.use { resp ->
            rateLimit.update(resp.headers)

            if (!resp.isSuccessful) return unsuccessful

            val body = resp.body?.string() ?: return unsuccessful

            val release = json.decodeFromString<GitHubRelease>(body)
            val tagVersion = release.tagName.removePrefix("v")

            val zipAsset = release.assets.firstOrNull { asset ->
                asset.name.endsWith(".zip", ignoreCase = true) &&
                        (asset.name.contains(module.id, ignoreCase = true) ||
                                asset.name.contains(repoName, ignoreCase = true))
            } ?: release.assets.firstOrNull { it.name.endsWith(".zip", ignoreCase = true) }

            val downloadUrl = zipAsset?.browserDownloadUrl

            val currentVersionCode = module.versionCode
            val latestVersionCode = extractVersionCodeFromTag(release.tagName)

            val hasUpdate = if (latestVersionCode != null && currentVersionCode != null) {
                latestVersionCode > currentVersionCode
            } else if (module.version != null) {
                compareSemanticVersions(tagVersion, module.version) > 0
            } else {
                false
            }

            return UpdateResult(
                moduleId = module.id,
                hasUpdate = hasUpdate,
                currentVersion = module.version,
                currentVersionCode = module.versionCode,
                latestVersion = tagVersion,
                latestVersionCode = latestVersionCode,
                downloadUrl = downloadUrl,
                changelog = release.body,
                repoOwner = owner,
                repoName = repoName
            )
        }
    }

    private fun fetchLatestRelease(owner: String, repo: String, githubPat: String?): GitHubRelease? {
        val url = "https://api.github.com/repos/$owner/$repo/releases/latest"
        val request = buildRequest(url, githubPat)
        val response = client.newCall(request).execute()
        return response.use { resp ->
            rateLimit.update(resp.headers)
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            json.decodeFromString<GitHubRelease>(body)
        }
    }

    private fun buildRequest(url: String, githubPat: String?): Request {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")

        if (!githubPat.isNullOrBlank() && isGitHubDomain(url)) {
            builder.header("Authorization", "Bearer $githubPat")
        }

        return builder.build()
    }

    private fun isGitHubDomain(url: String): Boolean {
        return try {
            val host = java.net.URL(url).host.lowercase()
            host == "api.github.com" || host.endsWith(".api.github.com")
        } catch (_: Exception) {
            false
        }
    }

    fun compareSemanticVersions(v1: String, v2: String): Int {
        val clean1 = v1.removePrefix("v").removePrefix("V").trim()
        val clean2 = v2.removePrefix("v").removePrefix("V").trim()
        if (clean1.equals(clean2, ignoreCase = true)) return 0

        val regex = Regex("(\\d+)")
        val parts1 = regex.findAll(clean1).mapNotNull { it.value.toIntOrNull() }.toList()
        val parts2 = regex.findAll(clean2).mapNotNull { it.value.toIntOrNull() }.toList()

        for (i in 0 until maxOf(parts1.size, parts2.size)) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1.compareTo(p2)
        }
        return clean1.compareTo(clean2, ignoreCase = true)
    }

    private fun extractVersionCodeFromTag(tag: String): Long? {
        val numericPart = tag.removePrefix("v").replace(Regex("[^0-9]"), "")
        return numericPart.toLongOrNull()
    }

    fun getRateLimit(): RateLimitTracker = rateLimit

    @Serializable
    private data class UpdateJsonResponse(
        val version: String? = null,
        val versionCode: Long? = null,
        val zipUrl: String? = null,
        val changelog: String? = null
    )

    companion object {
        private const val TAG = "UpdateChecker"

        @Volatile
        private var instance: UpdateChecker? = null

        fun getInstance(): UpdateChecker {
            return instance ?: synchronized(this) {
                instance ?: UpdateChecker().also {
                    instance = it
                }
            }
        }
    }
}
