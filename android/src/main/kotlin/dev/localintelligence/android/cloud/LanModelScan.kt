package dev.localintelligence.android.cloud

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

// ===========================================================================
// Finds model servers on this phone's own Wi-Fi: LM Studio, Ollama and llama.cpp, each on its usual
// port. It runs only when the user taps Find on the Models page, never in the background.
//
// WHAT IT SENDS: a GET of /v1/models to each private address of the local /24 networks, on three
// ports. No key, no body, no data from the phone. A host that answers with a model list is offered;
// anything else is ignored. A found server is then used through the custom endpoint path, so every
// message goes to that computer.
//
// LIMITS, on purpose: only RFC 1918 addresses (10/8, 172.16/12, 192.168/16) of this phone's own
// interfaces are scanned, one /24 each, at most two networks. Connect and read time are short and
// concurrency is low, so the scan does not flood the Wi-Fi. A server on another subnet, or on a port
// not listed here, is not found; it can still be typed in as the custom address.
// ===========================================================================

/** A model server that answered on this network. [baseUrl] is ready for the custom endpoint: http, the host, the port, and /v1. */
data class LanModelServer(
    val name: String,
    val host: String,
    val port: Int,
    val models: List<String>,
) {
    val baseUrl: String get() = "http://$host:$port/v1"

    /** The address a reader sees, host and port, without the scheme or path. */
    val address: String get() = "$host:$port"
}

/** One private /24 network of this phone, named by its first three octets. */
internal data class LocalSubnet(val prefix: String)

object LanModelScan {
    /** The ports the three servers listen on by default, with the name each is shown under. */
    val PORTS: List<Pair<Int, String>> = listOf(1234 to "LM Studio", 11434 to "Ollama", 8080 to "llama.cpp")

    /** Connect time for one address. A host that is on the network answers well inside this. */
    private const val CONNECT_MS = 300

    /** Read time for a model list. Servers that answer slowly are still listed. */
    private const val READ_MS = 2_000

    /** Probes in flight at once. Low on purpose: this is the phone's Wi-Fi, not a benchmark. */
    private const val CONCURRENCY = 12

    /** At most this many private networks are scanned, however many interfaces the phone has. */
    private const val MAX_SUBNETS = 2

    /** A model list is a few kilobytes. More than this is not a model list, so the read stops. */
    private const val MAX_BODY_BYTES = 256 * 1024

    private const val MAX_MODELS = 200

    /**
     * Looks for model servers on the private networks this phone is on. Returns them in the order
     * of [PORTS] and then by address, with the model ids each one lists. Empty when the phone has no
     * private address (mobile data, or no network), or when nothing answers.
     */
    suspend fun scan(): List<LanModelServer> = coroutineScope {
        val own = ownIpv4Addresses()
        val subnets = localSubnets()
        if (subnets.isEmpty()) return@coroutineScope emptyList()
        val gate = Semaphore(CONCURRENCY)
        val targets = subnets.flatMap { subnet ->
            (1..254).flatMap { host ->
                val ip = "${subnet.prefix}.$host"
                if (ip in own) emptyList() else PORTS.map { (port, name) -> Target(ip, port, name) }
            }
        }
        targets.map { target ->
            async(Dispatchers.IO) { gate.withPermit { probe(target) } }
        }.awaitAll().filterNotNull()
    }

    private data class Target(val host: String, val port: Int, val name: String)

    /** One probe. Any failure means "not a model server here"; cancellation is passed on. */
    private fun probe(target: Target): LanModelServer? {
        return try {
            val connection = URL("http://${target.host}:${target.port}/v1/models").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = CONNECT_MS
                connection.readTimeout = READ_MS
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Connection", "close")
                if (connection.responseCode !in 200..299) return null
                val models = parseModelIds(String(readBounded(connection.inputStream), Charsets.UTF_8)) ?: return null
                LanModelServer(target.name, target.host, target.port, models)
            } finally {
                connection.disconnect()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The ids in an OpenAI-style model list, or null when the body is not one. A `data` array is
     * required, so an unrelated web server on the same port is not listed as a model server.
     */
    internal fun parseModelIds(body: String): List<String>? {
        val root = try {
            Json.parseToJsonElement(body)
        } catch (e: Exception) {
            return null
        }
        val data = (root as? JsonObject)?.get("data") as? JsonArray ?: return null
        return data
            .mapNotNull { (it as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= 200 }
            .distinct()
            .sorted()
            .take(MAX_MODELS)
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (out.size() < MAX_BODY_BYTES) {
                val read = stream.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, minOf(read, MAX_BODY_BYTES - out.size()))
            }
            return out.toByteArray()
        }
    }

    /** The private /24 networks of this phone's up interfaces, at most [MAX_SUBNETS]. Public and CGNAT addresses are skipped. */
    internal fun localSubnets(): List<LocalSubnet> = allIpv4().filter { it.isSiteLocalAddress }
        .map { LocalSubnet(it.hostAddress.split('.').take(3).joinToString(".")) }
        .distinctBy { it.prefix }
        .take(MAX_SUBNETS)

    private fun ownIpv4Addresses(): Set<String> = allIpv4().map { it.hostAddress }.toSet()

    private fun allIpv4(): List<Inet4Address> = try {
        NetworkInterface.getNetworkInterfaces()?.let { java.util.Collections.list(it) }.orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
    } catch (e: SocketException) {
        emptyList()
    }
}
