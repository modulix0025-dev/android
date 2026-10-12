package com.modulex.translator.signaling

import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicReference

/**
 * One connected client. Outgoing text is queued without suspending so the registry can
 * enqueue messages for both peers atomically (which keeps `joined` ahead of any relayed
 * signal). A single writer coroutine drains the queue, then performs the close, so an
 * error frame is always delivered before its close frame.
 */
class Peer(maxQueued: Int = 256) {
    private val outbox = Channel<String>(maxQueued)
    private val closeReason = AtomicReference<CloseReason?>(null)

    val isClosing: Boolean get() = closeReason.get() != null

    fun send(text: String) {
        if (isClosing) return
        // A full queue means the client is not reading; drop it rather than buffer without bound.
        if (outbox.trySend(text).isFailure) close(CloseReason.Codes.TRY_AGAIN_LATER, "slow consumer")
    }

    /** Queues [error] then a close frame; further sends are ignored. */
    fun fail(error: String, code: CloseReason.Codes) {
        send(Protocol.error(error))
        close(code, error)
    }

    fun close(code: CloseReason.Codes, message: String = "") {
        if (closeReason.compareAndSet(null, CloseReason(code, message))) outbox.close()
    }

    suspend fun runWriter(session: WebSocketSession) {
        try {
            for (text in outbox) session.send(Frame.Text(text))
            closeReason.get()?.let { session.close(it) }
        } catch (_: Exception) {
            // Socket already gone; nothing left to deliver.
        }
    }
}

class Room internal constructor(val id: String, now: Long) {
    internal val peers = ArrayList<Peer>(2)
    internal var lastActivity = now
}

sealed interface JoinResult {
    data class Joined(val room: Room, val peers: Int) : JoinResult
    data object Full : JoinResult
    data object ServerFull : JoinResult
}

/**
 * All rooms. Every critical section is short and non-suspending (sends only enqueue),
 * so a plain monitor is enough and keeps join/relay/leave linearizable.
 */
class RoomRegistry(private val config: Config, private val clock: Clock = Clock.System) {
    private val lock = Any()
    private val rooms = HashMap<String, Room>()

    val roomCount: Int get() = synchronized(lock) { rooms.size }

    fun join(roomId: String, peer: Peer): JoinResult = synchronized(lock) {
        val now = clock.nowMillis()
        val room = rooms[roomId] ?: run {
            if (rooms.size >= config.maxRooms) return JoinResult.ServerFull
            Room(roomId, now).also { rooms[roomId] = it }
        }
        if (room.peers.size >= MAX_PEERS) return JoinResult.Full
        val other = room.peers.firstOrNull()
        room.peers += peer
        room.lastActivity = now
        peer.send(Protocol.joined(room.peers.size))
        other?.send(Protocol.PEER_JOINED)
        JoinResult.Joined(room, room.peers.size)
    }

    /** Sends [text] to the other participant. Returns false if there is none. */
    fun relay(room: Room, from: Peer, text: String): Boolean = synchronized(lock) {
        val other = room.peers.firstOrNull { it !== from } ?: return false
        room.lastActivity = clock.nowMillis()
        other.send(text)
        true
    }

    fun touch(room: Room) = synchronized(lock) { room.lastActivity = clock.nowMillis() }

    fun leave(room: Room, peer: Peer) = synchronized(lock) {
        if (!room.peers.remove(peer)) return
        room.peers.firstOrNull()?.send(Protocol.PEER_LEFT)
        if (room.peers.isEmpty()) rooms.remove(room.id, room)
    }

    /** Drops rooms idle for longer than the configured timeout; returns how many were dropped. */
    fun sweepIdle(): Int {
        val evicted = ArrayList<Peer>()
        var dropped = 0
        synchronized(lock) {
            val now = clock.nowMillis()
            val idleMillis = config.roomIdleTimeout.inWholeMilliseconds
            val it = rooms.values.iterator()
            while (it.hasNext()) {
                val room = it.next()
                if (now - room.lastActivity >= idleMillis) {
                    evicted += room.peers
                    room.peers.clear()
                    it.remove()
                    dropped++
                }
            }
        }
        evicted.forEach { it.close(CloseReason.Codes.GOING_AWAY, "idle") }
        return dropped
    }

    companion object {
        const val MAX_PEERS = 2
    }
}
