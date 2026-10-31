/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.rustclipsync

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID

internal class HttpFailure(val status: Int) : IOException("HTTP $status")

internal data class ServerState(
    val epoch: String,
    val revision: Long,
    val ackRevision: Long,
    val message: RemoteMessage?,
    val size: Long?,
)

/** HTTPS only, no redirects, bounded bodies, and no credentials in URLs or logs. */
internal class SyncTransport(serverUrl: String) {
    private val base: URI

    init {
        require(serverUrl.length <= 2048)
        val url = URI(serverUrl)
        require(url.scheme.equals("https", true) && !url.host.isNullOrEmpty())
        require(url.rawUserInfo == null && url.rawQuery == null && url.rawFragment == null)
        require(url.port == -1 || url.port in 1..65535)
        require(url.rawPath == null || !url.rawPath.contains(".."))
        base = URI(url.scheme, null, url.host, url.port,
            (url.path ?: "").trimEnd('/') + "/", null, null)
    }

    fun register(deviceId: String, token: String, user: String, password: String): Pair<String, Long> {
        require(user.isNotEmpty() && !user.contains(':') && !user.contains('\n') && !user.contains('\r'))
        val auth = "Basic " + Base64.encodeToString("$user:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val body = JSONObject().put("deviceId", deviceId).put("deviceToken", token).toString().toByteArray(Charsets.UTF_8)
        val response = json(request("POST", "v1/devices", auth, body, "application/json"))
        val epoch = response.getString("epoch")
        requireUuid(epoch)
        val ack = response.getLong("ackRevision")
        val current = response.getLong("currentRevision")
        require(ack in 0..current)
        return epoch to ack
    }

    fun state(session: StoredSession): ServerState = parseState(json(request("GET", "v1/state", bearer(session))))

    fun waitForChanges(session: StoredSession): ServerState? {
        val s = session.state
        requireUuid(s.epoch)
        val bytes = request("GET", "v1/changes?epoch=${s.epoch}&after=${s.cursor}", bearer(session), timeoutMs = 30_000)
        return bytes?.let { parseState(JSONObject(it.toString(Charsets.UTF_8))) }
    }

    fun content(session: StoredSession, message: RemoteMessage): String {
        requireUuid(message.id)
        val bytes = requireNotNull(request("GET", "v1/messages/${message.id}/content", bearer(session), limit = SyncStore.MAX_TEXT_BYTES))
        val decoder = Charsets.UTF_8.newDecoder()
        return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }

    fun send(session: StoredSession, outbox: Outbox): Long {
        requireUuid(outbox.copy.id)
        val bytes = outbox.copy.text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= SyncStore.MAX_TEXT_BYTES)
        val reply = json(request("PUT", "v1/messages/${outbox.copy.id}", bearer(session), bytes,
            "text/plain; charset=utf-8", headers = mapOf("X-RCS-Epoch" to session.state.epoch,
                "X-RCS-Base-Revision" to outbox.baseRevision.toString(),
                "X-RCS-Sensitive" to if (outbox.copy.sensitive) "1" else "0")))
        require(reply.getString("epoch") == session.state.epoch && reply.getString("messageId") == outbox.copy.id)
        return reply.getLong("revision").also { require(it > outbox.baseRevision) }
    }

    /** Returns null when cancelled, or the revision if the request had already committed. */
    fun cancel(session: StoredSession, outbox: Outbox): Long? {
        requireUuid(outbox.copy.id)
        val reply = json(request("DELETE", "v1/messages/${outbox.copy.id}", bearer(session),
            headers = mapOf("X-RCS-Epoch" to session.state.epoch)))
        return when (reply.getString("state")) {
            "cancelled" -> null
            "committed" -> reply.getLong("revision").also { require(it > outbox.baseRevision) }
            else -> throw IOException("invalid cancellation response")
        }
    }

    fun acknowledge(session: StoredSession, ack: Ack) {
        val body = JSONObject().put("epoch", session.state.epoch).put("revision", ack.revision)
            .put("messageId", ack.id).put("result", ack.result).toString().toByteArray(Charsets.UTF_8)
        val reply = json(request("POST", "v1/ack", bearer(session), body, "application/json"))
        require(reply.getLong("ackRevision") >= ack.revision)
    }

    private fun bearer(session: StoredSession) = "Bearer ${session.state.deviceId}:${session.token}"

    private fun parseState(j: JSONObject): ServerState {
        val epoch = j.getString("epoch").also(::requireUuid)
        val revision = j.getLong("revision")
        val ack = j.getLong("ackRevision")
        require(revision >= 0 && ack in 0..revision)
        val message = if (j.isNull("messageId")) null else {
            val id = j.getString("messageId").also(::requireUuid)
            val source = j.getString("sourceDeviceId").also(::requireUuid)
            require(!j.isNull("sensitive"))
            RemoteMessage(id, revision, source, j.getBoolean("sensitive"))
        }
        require((revision == 0L) == (message == null))
        if (message == null) require(j.isNull("sensitive"))
        val size = if (j.isNull("size")) null else j.getLong("size")
        if (message != null) require(size != null && size in 0..SyncStore.MAX_TEXT_BYTES.toLong())
        return ServerState(epoch, revision, ack, message, size)
    }

    private fun json(bytes: ByteArray?) = JSONObject(requireNotNull(bytes).toString(Charsets.UTF_8))

    private fun request(method: String, path: String, auth: String, body: ByteArray? = null,
                        contentType: String? = null, headers: Map<String, String> = emptyMap(),
                        timeoutMs: Int = 10_000, limit: Int = 16 * 1024): ByteArray? {
        val target: URL = base.resolve(path).toURL()
        check(target.protocol.equals("https", true) && target.host.equals(base.host, true) && target.port == base.port)
        val connection = target.openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 10_000
            connection.readTimeout = timeoutMs
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", auth)
            connection.setRequestProperty("Cache-Control", "no-store")
            headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            if (status == 204) return null
            if (status !in 200..299) throw HttpFailure(status)
            require(connection.contentLength < 0 || connection.contentLength <= limit)
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var count = 0
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    count += n
                    require(count <= limit)
                    output.write(chunk, 0, n)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        fun requireUuid(value: String) {
            val uuid = UUID.fromString(value)
            require(uuid.version() == 4 && uuid.toString() == value)
        }
    }
}
