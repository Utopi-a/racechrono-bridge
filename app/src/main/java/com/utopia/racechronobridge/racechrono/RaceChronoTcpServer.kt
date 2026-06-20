package com.utopia.racechronobridge.racechrono

import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

data class RaceChronoTcpStatus(
    val running: Boolean,
    val clientConnected: Boolean,
    val host: String = RaceChronoTcpServer.HOST,
    val port: Int = RaceChronoTcpServer.PORT,
)

class RaceChronoTcpServer(
    private val onStatusChanged: (RaceChronoTcpStatus) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val lock = Any()

    private var serverThread: Thread? = null
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var clientWriter: BufferedWriter? = null

    fun start() {
        if (!running.compareAndSet(false, true)) {
            onLog("RaceChrono TCP server is already running.")
            return
        }

        serverThread = Thread(::acceptClients, "RaceChronoTcpServer").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) {
            publishStatus(running = false, clientConnected = false)
            return
        }

        closeClient()
        closeServerSocket()
        serverThread?.interrupt()
        serverThread = null
        publishStatus(running = false, clientConnected = false)
        onLog("RaceChrono TCP server stopped.")
    }

    fun send(sentence: String) {
        synchronized(lock) {
            val writer = clientWriter ?: return
            try {
                writer.write(sentence)
                writer.flush()
            } catch (error: Exception) {
                onLog("RaceChrono TCP send failed: ${error.message}")
                closeClientLocked()
                publishStatus(running = running.get(), clientConnected = false)
            }
        }
    }

    private fun acceptClients() {
        try {
            ServerSocket().use { socket ->
                serverSocket = socket
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(InetAddress.getByName(HOST), PORT))
                publishStatus(running = true, clientConnected = false)
                onLog("RaceChrono TCP server listening on $HOST:$PORT.")

                while (running.get()) {
                    val accepted = socket.accept()
                    accepted.tcpNoDelay = true
                    synchronized(lock) {
                        closeClientLocked()
                        clientSocket = accepted
                        clientWriter = BufferedWriter(
                            OutputStreamWriter(accepted.getOutputStream(), StandardCharsets.US_ASCII),
                        )
                    }
                    publishStatus(running = true, clientConnected = true)
                    onLog("RaceChrono TCP client connected: ${accepted.remoteSocketAddress}.")
                }
            }
        } catch (error: Exception) {
            if (running.get()) {
                onLog("RaceChrono TCP server failed: ${error.message}")
            }
        } finally {
            closeClient()
            closeServerSocket()
            running.set(false)
            publishStatus(running = false, clientConnected = false)
        }
    }

    private fun closeServerSocket() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        } finally {
            serverSocket = null
        }
    }

    private fun closeClient() {
        synchronized(lock) {
            closeClientLocked()
        }
    }

    private fun closeClientLocked() {
        try {
            clientWriter?.close()
        } catch (_: Exception) {
        }
        try {
            clientSocket?.close()
        } catch (_: Exception) {
        }
        clientWriter = null
        clientSocket = null
    }

    private fun publishStatus(running: Boolean, clientConnected: Boolean) {
        onStatusChanged(RaceChronoTcpStatus(running = running, clientConnected = clientConnected))
    }

    companion object {
        const val HOST = "127.0.0.1"
        const val PORT = 9876
    }
}
