package com.movtery.zalithlauncher.trident

import com.movtery.zalithlauncher.game.account.Account
import com.movtery.zalithlauncher.game.account.isLocalAccount
import com.movtery.zalithlauncher.game.account.isMicrosoftAccount
import com.movtery.zalithlauncher.path.PathManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

class TridentException(val code: String, message: String) : Exception(message)

data class ShopItem(val id: String, val name: String, val price: Int)

data class Friend(val name: String, val status: String, val server: String)

data class Wallet(
    val coins: Int,
    val owned: List<String>,
    val equipped: List<String>,
    val shop: List<ShopItem>
)

data class SyncState(
    val name: String,
    val friends: List<Friend>,
    val incoming: List<String>,
    val outgoing: List<String>,
    val wallet: Wallet?
)

object TridentNet {
    const val BASE_URL = "https://api.tridentclient.com"
    private const val SESSION_JOIN = "https://sessionserver.mojang.com/session/minecraft/join"
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val json = Json { ignoreUnknownKeys = true }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private data class Cached(val key: String, val token: String, val until: Long)

    @Volatile private var cached: Cached? = null

    private val dir: File
        get() = File(PathManager.DIR_FILES_PRIVATE, "trident").apply { mkdirs() }

    fun installId(): String {
        val file = File(dir, "install-id")
        val old = runCatching { file.readText().trim().lowercase() }.getOrNull()
        if (old != null && old.length == 32 && old.all { it in '0'..'9' || it in 'a'..'f' }) return old
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val id = bytes.joinToString("") { "%02x".format(it) }
        runCatching { file.writeText(id) }
        return id
    }

    private fun linkFile(name: String) = File(dir, "link-${name.lowercase()}.json")

    fun offlineLink(name: String): Pair<String, String>? {
        val text = runCatching { linkFile(name).readText() }.getOrNull() ?: return null
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val n = obj.str("name") ?: return null
        val device = obj.str("device") ?: return null
        return n to device
    }

    private fun saveLink(name: String, device: String) {
        val body = buildJsonObject {
            put("name", name)
            put("device", device)
        }
        runCatching { linkFile(name).writeText(body.toString()) }
    }

    fun unlink(name: String) {
        runCatching { linkFile(name).delete() }
        cached = null
    }

    fun message(code: String): String = when (code) {
        "not_found" -> "Ese jugador todavía no usa Trident Client."
        "self" -> "No podés agregarte a vos mismo."
        "already_friends" -> "Ya son amigos."
        "already_requested" -> "Ya le mandaste una solicitud."
        "too_many_friends" -> "Llegaste al máximo de amigos."
        "too_many_pending" -> "Hay demasiadas solicitudes pendientes."
        "invalid_name" -> "Ese nombre no es válido."
        "not_enough_coins" -> "No te alcanzan los Trident Coins."
        "not_owned" -> "Todavía no compraste ese cosmético."
        "weak_password" -> "La contraseña es muy corta o muy larga."
        "name_taken" -> "Ese nombre ya tiene contraseña en Trident Net. Si es tuyo, entrá con tu contraseña."
        "premium_name" -> "Ese nombre es de una cuenta premium: entrá con tu cuenta de Microsoft."
        "bad_password" -> "Contraseña incorrecta."
        "no_account" -> "Ese nombre todavía no tiene cuenta en Trident Net. Creala con una contraseña."
        "device_unknown" -> "Tu vinculación venció: volvé a entrar con tu contraseña."
        "rate_limited" -> "Demasiados intentos. Esperá un momento."
        "unauthorized" -> "Trident Net no pudo verificar tu cuenta. Volvé a iniciar sesión con Microsoft."
        "need_link" -> "Cuenta no premium: entrá o creá tu contraseña de Trident Net."
        "no_account_selected" -> "Elegí una cuenta en el launcher primero."
        "unsupported_account" -> "Este tipo de cuenta no funciona con Trident Net."
        "network" -> "No se pudo conectar con Trident Net. Revisá tu conexión."
        else -> "Trident Net respondió con un error ($code)."
    }

    private fun fail(code: String): Nothing = throw TridentException(code, message(code))

    private suspend fun call(path: String, body: JsonObject?, token: String? = null): JsonObject =
        withContext(Dispatchers.IO) {
            val payload = (body ?: JsonObject(emptyMap())).toString().toRequestBody(JSON_TYPE)
            val builder = Request.Builder()
                .url(BASE_URL + path)
                .header("User-Agent", "TridentClient-Android")
                .post(payload)
            if (token != null) builder.header("Authorization", "Bearer $token")
            val response = try {
                http.newCall(builder.build()).execute()
            } catch (e: Exception) {
                fail("network")
            }
            response.use { r ->
                val text = r.body.string()
                val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: JsonObject(emptyMap())
                if (!r.isSuccessful) fail(obj.str("error") ?: "http_${r.code}")
                obj
            }
        }

    suspend fun pingOnline(): Int? = runCatching {
        call("/v1/stats/ping", buildJsonObject { put("id", installId()) }).int("online")
    }.getOrNull()

    private suspend fun joinSession(account: Account, nonce: String) = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("accessToken", account.accessToken)
            put("selectedProfile", account.profileId.replace("-", ""))
            put("serverId", nonce)
        }
        val req = Request.Builder().url(SESSION_JOIN).post(body.toString().toRequestBody(JSON_TYPE)).build()
        val ok = try {
            http.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            fail("network")
        }
        if (!ok) fail("unauthorized")
    }

    suspend fun token(account: Account?): String {
        if (account == null) fail("no_account_selected")
        val key = "${account.uniqueUUID}:${account.username}"
        cached?.let { if (it.key == key && it.until > System.currentTimeMillis()) return it.token }
        val result = when {
            account.isMicrosoftAccount() -> {
                val nonce = call("/v1/auth/challenge", null).str("nonce") ?: fail("server_error")
                joinSession(account, nonce)
                call("/v1/auth/login", buildJsonObject {
                    put("name", account.username)
                    put("nonce", nonce)
                })
            }
            account.isLocalAccount() -> {
                val link = offlineLink(account.username) ?: fail("need_link")
                try {
                    call("/v1/auth/offline/device", buildJsonObject {
                        put("name", link.first)
                        put("device", link.second)
                    })
                } catch (e: TridentException) {
                    if (e.code == "device_unknown") unlink(account.username)
                    throw e
                }
            }
            else -> fail("unsupported_account")
        }
        val token = result.str("token") ?: fail("server_error")
        val ttl = (result.long("expiresIn") ?: 3600L).coerceAtLeast(120L)
        cached = Cached(key, token, System.currentTimeMillis() + (ttl - 60L) * 1000L)
        return token
    }

    suspend fun offlineAuth(account: Account, password: String, create: Boolean) {
        val path = if (create) "/v1/auth/offline/register" else "/v1/auth/offline/login"
        val result = call(path, buildJsonObject {
            put("name", account.username)
            put("password", password)
        })
        val device = result.str("device") ?: fail("server_error")
        saveLink(result.str("name") ?: account.username, device)
        cached = null
    }

    private fun parseWallet(obj: JsonObject?): Wallet? {
        if (obj == null) return null
        val shop = (obj["shop"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            ShopItem(id, o.str("name") ?: id, o.int("price") ?: 0)
        }
        return Wallet(
            coins = obj.int("coins") ?: 0,
            owned = obj.strings("owned"),
            equipped = obj.strings("equipped"),
            shop = shop
        )
    }

    suspend fun sync(account: Account?): SyncState {
        val t = token(account)
        val r = call("/v1/sync", buildJsonObject { put("client", "launcher") }, t)
        val friends = (r["friends"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val name = o.str("name") ?: return@mapNotNull null
            Friend(name, o.str("status") ?: "offline", o.str("server") ?: "")
        }
        return SyncState(
            name = (r["me"] as? JsonObject)?.str("name") ?: account?.username.orEmpty(),
            friends = friends,
            incoming = r.strings("incoming"),
            outgoing = r.strings("outgoing"),
            wallet = parseWallet(r["wallet"] as? JsonObject)
        )
    }

    suspend fun buy(account: Account?, id: String): Wallet? {
        val r = call("/v1/shop/buy", buildJsonObject { put("id", id) }, token(account))
        return parseWallet(r["wallet"] as? JsonObject)
    }

    suspend fun equip(account: Account?, id: String, on: Boolean): Wallet? {
        val r = call("/v1/shop/equip", buildJsonObject {
            put("id", id)
            put("on", on)
        }, token(account))
        return parseWallet(r["wallet"] as? JsonObject)
    }

    suspend fun friend(account: Account?, action: String, name: String) {
        call("/v1/friends/$action", buildJsonObject { put("name", name) }, token(account))
    }

    private fun JsonObject.prim(key: String): JsonPrimitive? = this[key] as? JsonPrimitive
    private fun JsonObject.str(key: String): String? = prim(key)?.contentOrNull
    private fun JsonObject.int(key: String): Int? = prim(key)?.intOrNull
    private fun JsonObject.long(key: String): Long? = prim(key)?.longOrNull
    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}
