/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.bg.test

import android.annotation.SuppressLint
import android.os.Build
import android.os.SystemClock
import io.nekohasekai.sagernet.fmt.LOCALHOST
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket

data class SpeedTestResult(
    /**
     * true/false for the quick test (does the speed exceed the threshold), null for the full test.
     */
    val exceeded: Boolean?,
    /**
     * Measured transfer rate in KB/s. For a passed quick test this is the rate at the
     * cut, i.e. a lower bound of the real rate.
     */
    val kbps: Int,
    val error: String? = null,
)

object SpeedTester {

    private const val WARMUP_MS = 1000L
    private const val MIN_JUDGE_MS = 200L
    private const val MAX_REDIRECTS = 3
    private const val BUF_SIZE = 64 * 1024
    private val REDIRECT_CODES = intArrayOf(301, 302, 303, 307, 308)

    /**
     * Download [urlStr] through the local SOCKS5 inbound on [socksPort]
     * (a V2RayTestInstance with that inbound must already be running).
     *
     * quick = true: cut the connection immediately once the measured rate exceeds
     * [thresholdKBps] and return exceeded = true; exceeded = false when [durationMs]
     * elapses without exceeding it.
     *
     * quick = false: measure the average rate for [durationMs].
     */
    fun measure(
        socksPort: Int,
        urlStr: String,
        durationMs: Long,
        thresholdKBps: Int,
        quick: Boolean,
    ): SpeedTestResult {
        var current = urlStr
        var error: String? = null
        for (hop in 0..MAX_REDIRECTS) {
            val conn = try {
                Connection.open(current, socksPort)
            } catch (e: Exception) {
                return SpeedTestResult(if (quick) false else null, 0, "${e.javaClass.simpleName}: ${e.message}")
            }
            conn.use {
                val code = conn.responseCode
                if (code in REDIRECT_CODES) {
                    val location = conn.header("location")
                    if (location != null) {
                        current = URL(URL(current), location).toString()
                        return@use
                    }
                }
                if (code != 200) {
                    return SpeedTestResult(if (quick) false else null, 0, "HTTP $code")
                }
                return conn.pump(durationMs, thresholdKBps, quick)
            }
        }
        return SpeedTestResult(if (quick) false else null, 0, error ?: "too many redirects")
    }

    @SuppressLint("CustomX509TrustManager")
    private class Connection private constructor(
        private val socket: Socket,
        private val input: BufferedInputStream,
    ) : AutoCloseable {

        private val headers = HashMap<String, String>()
        private lateinit var statusLine: String

        val responseCode: Int
            get() = if (::statusLine.isInitialized) {
                statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0
            } else 0

        fun header(name: String): String? = headers[name]

        fun pump(durationMs: Long, thresholdKBps: Int, quick: Boolean): SpeedTestResult {
            val stream: InputStream = if (headers["transfer-encoding"]?.contains("chunked") == true) {
                ChunkedInputStream(input)
            } else {
                input
            }
            val start = SystemClock.elapsedRealtime()
            val deadline = start + durationMs
            val thresholdBps = thresholdKBps * 1024L
            var totalBytes = 0L
            var measuredBytes = 0L
            var measureStart = 0L
            var warmedUp = false
            val buf = ByteArray(BUF_SIZE)

            try {
                while (true) {
                    val now = SystemClock.elapsedRealtime()
                    val remaining = deadline - now
                    if (remaining <= 0) break
                    socket.soTimeout = remaining.coerceAtMost(10_000L).toInt()
                    val n = try {
                        stream.read(buf)
                    } catch (_: InterruptedIOException) {
                        break
                    }
                    if (n < 0) break
                    totalBytes += n
                    if (!warmedUp && now - start >= WARMUP_MS) {
                        warmedUp = true
                        measureStart = now
                    }
                    if (warmedUp) {
                        measuredBytes += n
                        if (quick) {
                            val elapsed = SystemClock.elapsedRealtime() - measureStart
                            if (elapsed >= MIN_JUDGE_MS) {
                                val bps = measuredBytes * 1000 / elapsed
                                if (bps >= thresholdBps) {
                                    // threshold exceeded: cut the connection immediately
                                    return SpeedTestResult(true, (bps / 1024L).toInt())
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                return SpeedTestResult(if (quick) false else null, 0, "${e.javaClass.simpleName}: ${e.message}")
            }

            val end = SystemClock.elapsedRealtime()
            val bps = if (warmedUp) {
                measuredBytes * 1000 / (end - measureStart).coerceAtLeast(1)
            } else {
                totalBytes * 1000 / (end - start).coerceAtLeast(1)
            }
            return SpeedTestResult(if (quick) false else null, (bps / 1024L).toInt())
        }

        override fun close() {
            try {
                input.close()
            } catch (_: Exception) {
            }
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }

        companion object {

            fun open(urlStr: String, socksPort: Int): Connection {
                val url = URL(urlStr)
                val tls = url.protocol == "https"
                val port = if (url.port > 0) url.port else if (tls) 443 else 80
                val host = url.host

                // SOCKS5 handshake with remote DNS happens inside connect()
                val socket = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress(LOCALHOST, socksPort)))
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress.createUnresolved(host, port), 10_000)
                socket.soTimeout = 10_000

                val raw = if (tls) {
                    val ssl = HttpsURLConnection.getDefaultSSLSocketFactory()
                        .createSocket(socket, host, port, true) as SSLSocket
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        val params = ssl.sslParameters
                        params.serverNames = listOf(SNIHostName(host))
                        ssl.sslParameters = params
                    }
                    ssl.startHandshake()
                    if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                        ssl.close()
                        throw IOException("hostname verification failed")
                    }
                    ssl
                } else {
                    socket
                }

                val out = raw.getOutputStream()
                val path = url.file.ifEmpty { "/" }
                val request = buildString {
                    append("GET ").append(path).append(" HTTP/1.1\r\n")
                    append("Host: ").append(host)
                    if (port != if (tls) 443 else 80) append(":").append(port)
                    append("\r\n")
                    append("User-Agent: Mozilla/5.0 (Linux; Android) ExclaveSpeedTest/1.0\r\n")
                    append("Accept-Encoding: identity\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }
                out.write(request.toByteArray(Charsets.US_ASCII))
                out.flush()

                val conn = Connection(raw, BufferedInputStream(raw.getInputStream(), BUF_SIZE))
                conn.readHead()
                return conn
            }

        }

        private fun readHead() {
            var first = true
            while (true) {
                val line = readLine() ?: throw IOException("connection closed before response header")
                if (line.isEmpty()) break
                if (first) {
                    statusLine = line
                    first = false
                } else {
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                    }
                }
            }
        }

        private fun readLine(): String? {
            val sb = StringBuilder(80)
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) {
                    if (sb.isNotEmpty() && sb.last() == '\r') sb.setLength(sb.length - 1)
                    return sb.toString()
                }
                sb.append(b.toChar())
            }
        }

    }

    private class ChunkedInputStream(private val input: InputStream) : InputStream() {

        private var remaining = 0L
        private var done = false

        override fun available(): Int = if (done) 0 else remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        override fun read(): Int {
            val buf = ByteArray(1)
            val n = read(buf, 0, 1)
            return if (n < 0) -1 else buf[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (done) return -1
            if (remaining <= 0L) {
                remaining = readChunkHeader()
                if (remaining == 0L) {
                    done = true
                    readTrailers()
                    return -1
                }
            }
            val n = input.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n < 0) throw EOFException()
            remaining -= n
            if (remaining == 0L) {
                if (input.read() != '\r'.code || input.read() != '\n'.code) throw EOFException()
            }
            return n
        }

        private fun readChunkHeader(): Long {
            val line = readLine(input) ?: throw EOFException()
            return line.substringBefore(';').trim().toLong(16)
        }

        private fun readTrailers() {
            while (true) {
                val line = readLine(input) ?: throw EOFException()
                if (line.isEmpty()) return
            }
        }

        private fun readLine(input: InputStream): String? {
            val sb = StringBuilder(40)
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) {
                    if (sb.isNotEmpty() && sb.last() == '\r') sb.setLength(sb.length - 1)
                    return sb.toString()
                }
                sb.append(b.toChar())
            }
        }

    }

}
