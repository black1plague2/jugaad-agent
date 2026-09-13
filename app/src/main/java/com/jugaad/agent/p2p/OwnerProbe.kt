package com.jugaad.agent.p2p

import com.jugaad.agent.core.Logx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Liveness probe (v13 plan §1): connects to a candidate owner, sends PING and returns the
 * deviceId carried by its PONG reply, or null if it never answered within [timeoutMs] (a dead
 * or frozen owner, an unreachable host, or any other connect/read failure). Never throws, so
 * every caller (step-down, takeover, owner pick) can treat the result as a plain "is it there"
 * check without its own try/catch. [com.jugaad.agent.core.config.AppConfig] isn't owned by this
 * change, so the default timeout lives here rather than in [com.jugaad.agent.core.config.Sync].
 */
object OwnerProbe {
    const val DEFAULT_TIMEOUT_MS = 3000

    suspend fun ping(host: String, port: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): String? =
        withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                    socket.soTimeout = timeoutMs
                    SyncProtocol.write(socket.getOutputStream(), SyncProtocol.Message(SyncProtocol.PING, SyncProtocol.Header(), null))
                    val reply = SyncProtocol.read(socket.getInputStream())
                    if (reply.type == SyncProtocol.PONG) reply.header.deviceId else null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                null
            } catch (e: Exception) {
                Logx.w("OwnerProbe: ping to $host:$port threw", e)
                null
            }
        }
}
