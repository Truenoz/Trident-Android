package com.movtery.zalithlauncher.trident

import com.movtery.zalithlauncher.game.account.Account
import com.movtery.zalithlauncher.game.account.isLocalAccount
import com.movtery.zalithlauncher.game.addons.modloader.ModLoader
import com.movtery.zalithlauncher.game.version.installed.Version
import com.movtery.zalithlauncher.game.version.installed.VersionFolders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object TridentMods {
    private const val JAR_REPO = "Truenoz/Trident-Client"
    private const val ROLLING_TAG = "mods-rolling"
    private const val INDEX = ".trident-managed.json"
    private const val CHECK_MS = 60L * 60L * 1000L
    private const val NONE_MS = 24L * 60L * 60L * 1000L
    private const val FABRIC_API_ID = "P7dR8mSH"

    val MODS = listOf(
        "fabric-api" to "Fabric API",
        "modmenu" to "Mod Menu",
        "lithium" to "Lithium",
        "ferrite-core" to "FerriteCore",
        "entityculling" to "Entity Culling"
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun isFabric(version: Version): Boolean =
        version.getVersionInfo()?.hasLoader(ModLoader.FABRIC) == true

    private fun readIndex(dir: File): MutableMap<String, JsonObject> {
        val text = runCatching { File(dir, INDEX).readText() }.getOrNull() ?: return mutableMapOf()
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return mutableMapOf()
        return obj.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }.toMap().toMutableMap()
    }

    private fun writeIndex(dir: File, index: Map<String, JsonObject>) {
        runCatching { File(dir, INDEX).writeText(JsonObject(index).toString()) }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun present(dir: File, name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        return File(dir, name).isFile || File(dir, "$name.disabled").isFile
    }

    private fun getJson(url: String): Any? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "TridentClient-Android")
            .header("Accept", "application/json")
            .build()
        http.newCall(req).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            return json.parseToJsonElement(r.body.string())
        }
    }

    private fun sha1(file: File): String {
        val md = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun download(url: String, dest: File, sha1: String?) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        val req = Request.Builder().url(url).header("User-Agent", "TridentClient-Android").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            tmp.outputStream().use { out -> r.body.byteStream().copyTo(out) }
        }
        if (sha1 != null && !sha1(tmp).equals(sha1, ignoreCase = true)) {
            tmp.delete()
            throw IllegalStateException("el archivo bajó dañado")
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    private fun bestVersion(slug: String, mc: String): JsonObject? {
        val url = "https://api.modrinth.com/v2/project/${enc(slug)}/version".toHttpUrl().newBuilder()
            .addQueryParameter("game_versions", "[\"$mc\"]")
            .addQueryParameter("loaders", "[\"fabric\"]")
            .build()
            .toString()
        val list = (getJson(url) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        return list.firstOrNull { it.str("version_type") == "release" } ?: list.firstOrNull()
    }

    private fun primaryFile(version: JsonObject): JsonObject? {
        val files = (version["files"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        return files.firstOrNull { it.bool("primary") == true } ?: files.firstOrNull()
    }

    private fun installModrinth(dir: File, version: JsonObject): String {
        val file = primaryFile(version) ?: throw IllegalStateException("sin archivos")
        val name = File(file.str("filename") ?: throw IllegalStateException("sin nombre")).name
        val url = file.str("url") ?: throw IllegalStateException("sin URL")
        val sha1 = (file["hashes"] as? JsonObject)?.str("sha1")
        download(url, File(dir, name), sha1)
        return name
    }

    private fun installedProjects(index: Map<String, JsonObject>): Set<String> =
        index.values.mapNotNull { it.str("project") }.toSet()

    private fun ensureModrinth(dir: File, mc: String, slug: String, label: String, index: MutableMap<String, JsonObject>, log: (String) -> Unit) {
        val entry = index[slug]
        if (entry != null && present(dir, entry.str("file"))) return
        val noneAt = entry?.long("none") ?: 0L
        if (entry?.str("file") == null && System.currentTimeMillis() - noneAt < NONE_MS) return
        val version = try {
            bestVersion(slug, mc)
        } catch (e: Exception) {
            log("[Trident] $label: no se pudo consultar Modrinth (${e.message}).")
            return
        }
        if (version == null) {
            index[slug] = buildJsonObject { put("none", System.currentTimeMillis()) }
            log("[Trident] $label no tiene versión para Minecraft $mc, se omite.")
            return
        }
        try {
            val name = installModrinth(dir, version)
            index[slug] = buildJsonObject {
                put("file", name)
                put("project", version.str("project_id") ?: slug)
            }
            log("[Trident] $label ${version.str("version_number").orEmpty()} instalado.")
        } catch (e: Exception) {
            log("[Trident] $label: ${e.message}")
            return
        }
        val deps = (version["dependencies"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        for (dep in deps) {
            if (dep.str("dependency_type") != "required") continue
            val pid = dep.str("project_id") ?: continue
            if (pid == FABRIC_API_ID || index.containsKey("dep:$pid") && present(dir, index["dep:$pid"]?.str("file"))) continue
            if (pid in installedProjects(index)) continue
            try {
                val dv = bestVersion(pid, mc) ?: run {
                    log("[Trident] $label necesita un mod que no tiene versión para $mc.")
                    null
                } ?: continue
                val name = installModrinth(dir, dv)
                index["dep:$pid"] = buildJsonObject {
                    put("file", name)
                    put("project", pid)
                }
                log("[Trident] $label: se instaló $name, que es necesario.")
            } catch (e: Exception) {
                log("[Trident] $label: no se pudo instalar una dependencia (${e.message}).")
            }
        }
    }

    private fun ensureTridentJar(dir: File, mc: String, index: MutableMap<String, JsonObject>, log: (String) -> Unit) {
        val entry = index["trident"]
        val have = entry != null && present(dir, entry.str("file"))
        val checked = entry?.long("checked") ?: 0L
        if (have && System.currentTimeMillis() - checked < CHECK_MS) return
        val release = try {
            getJson("https://api.github.com/repos/$JAR_REPO/releases/tags/$ROLLING_TAG") as? JsonObject
        } catch (e: Exception) {
            log("[Trident] No se pudo consultar GitHub (${e.message}).")
            return
        } ?: run {
            log("[Trident] No se encontró el release «$ROLLING_TAG».")
            return
        }
        val assets = (release["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val asset = listOf("trident-client-$mc.jar", "nova-client-$mc.jar")
            .firstNotNullOfOrNull { n -> assets.firstOrNull { it.str("name") == n } }
        if (asset == null) {
            log("[Trident] Trident Client todavía no tiene versión para Minecraft $mc.")
            return
        }
        val updated = asset.str("updated_at").orEmpty()
        val name = "trident-client-$mc.jar"
        if (have && entry != null && entry.str("updated") == updated) {
            index["trident"] = buildJsonObject {
                put("file", entry.str("file"))
                put("updated", updated)
                put("checked", System.currentTimeMillis())
            }
            return
        }
        if (entry?.str("file") != null && File(dir, "${entry.str("file")}.disabled").isFile) return
        val url = asset.str("browser_download_url") ?: return
        try {
            entry?.str("file")?.let { old -> if (old != name) File(dir, old).delete() }
            download(url, File(dir, name), null)
            index["trident"] = buildJsonObject {
                put("file", name)
                put("updated", updated)
                put("checked", System.currentTimeMillis())
            }
            log("[Trident] Trident Client para $mc instalado.")
        } catch (e: Exception) {
            log("[Trident] No se pudo bajar Trident Client: ${e.message}")
        }
    }

    private fun writeOfflineLink(gameDir: File, account: Account) {
        val file = File(gameDir, "config/tridentclient/net-offline.json")
        val link = if (account.isLocalAccount()) TridentNet.offlineLink(account.username) else null
        if (link == null) {
            if (file.exists()) file.delete()
            return
        }
        file.parentFile?.mkdirs()
        val body = buildJsonObject {
            put("name", link.first)
            put("device", link.second)
        }
        runCatching { file.writeText(body.toString()) }
    }

    suspend fun prepare(version: Version, account: Account, log: (String) -> Unit) = withContext(Dispatchers.IO) {
        if (!isFabric(version)) return@withContext
        val mc = version.getVersionInfo()?.minecraftVersion ?: return@withContext
        val gameDir = version.getGameDir()
        val dir = VersionFolders.MOD.getDir(gameDir).apply { mkdirs() }
        val index = readIndex(dir)
        ensureTridentJar(dir, mc, index, log)
        for ((slug, label) in MODS) ensureModrinth(dir, mc, slug, label, index, log)
        writeIndex(dir, index)
        writeOfflineLink(gameDir, account)
    }
}
