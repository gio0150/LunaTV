package app.orchard.tv.remote

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * Optional system-wide remote using the TV's own adbd on loopback, as OneTV does. The fixed key
 * map is the only shell surface: no user supplied command is ever sent to the shell.
 */
class SystemRemote private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("luma-system-remote", Context.MODE_PRIVATE)
    private val keyDir = File(appContext.filesDir, "adb-key").apply { mkdirs() }

    @Volatile var ready = false; private set
    @Volatile var message = "not_connected"; private set

    private val keyPair: KeyPair by lazy {
        val privateFile = File(keyDir, "private.der")
        val publicFile = File(keyDir, "public.der")
        if (privateFile.exists() && publicFile.exists()) {
            try {
                KeyPair(
                    KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicFile.readBytes())),
                    KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(privateFile.readBytes())),
                )
            } catch (_: Throwable) {
                privateFile.delete()
                publicFile.delete()
                createKeys(privateFile, publicFile)
            }
        } else createKeys(privateFile, publicFile)
    }

    private fun createKeys(privateFile: File, publicFile: File): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().also {
            privateFile.writeBytes(it.private.encoded)
            publicFile.writeBytes(it.public.encoded)
        }

    fun enabled() = prefs.getBoolean("enabled", false)
    fun port() = prefs.getInt("port", 5555)

    fun disconnect() {
        prefs.edit().putBoolean("enabled", false).apply()
        ready = false
        message = "not_connected"
    }

    fun connect(port: Int): Boolean {
        if (port !in 1024..65535) {
            message = "invalid_port"
            return false
        }
        return try {
            val result = shell("echo luma-adb-ready", port, true)
            check(result.contains("luma-adb-ready"))
            prefs.edit().putInt("port", port).putBoolean("enabled", true).apply()
            ready = true
            message = "connected"
            true
        } catch (e: Exception) {
            ready = false
            message = e.message ?: "connect_failed"
            false
        }
    }

    fun restore(): Boolean {
        if (!enabled()) return false
        if (ready) return true
        return try {
            check(shell("echo luma-adb-ready", port(), false).contains("luma-adb-ready"))
            ready = true
            message = "connected"
            true
        } catch (e: Exception) {
            ready = false
            message = e.message ?: "connect_failed"
            false
        }
    }

    fun command(cmd: String): Boolean {
        if (!enabled()) return false
        val key = when (cmd) {
            "up" -> 19
            "down" -> 20
            "left" -> 21
            "right" -> 22
            "ok" -> 23
            "back" -> 4
            "home" -> 3
            "menu" -> 82
            "power" -> 26
            "volumeUp" -> 24
            "volumeDown" -> 25
            "volumeMute" -> 164
            else -> return false
        }
        return try {
            val output = shell("input keyevent " + (if (cmd == "power") "--longpress " else "") + key, port(), false)
            check(!Regex("permission denied|exception|usage:|error:|unknown|not found", RegexOption.IGNORE_CASE).containsMatchIn(output))
            ready = true
            message = "connected"
            true
        } catch (e: Exception) {
            ready = false
            message = e.message ?: "command_failed"
            false
        }
    }

    fun castPid(pkg: String): Result<String> = castShell(pkg, "pidof $pkg", 5_000)

    fun castProjection(pkg: String): Result<String> = castShell(pkg, "dumpsys media_projection", 6_000)

    /**
     * Bringing MainActivity to the front is the exported entry point that starts the receiver's
     * foreground service. Home then puts the TV back on its launcher while the service keeps running.
     */
    fun startCast(pkg: String): Result<String> =
        castShell(pkg, "pm enable $pkg >/dev/null; am start -W -n $pkg/.ui.MainActivity; sleep 1; input keyevent 3; pidof $pkg", 20_000)

    /**
     * Disabling the package keeps the receiver's own BootReceiver from starting it again after a
     * reboot, so "off" survives a power cycle instead of coming back on its own.
     */
    fun stopCast(pkg: String): Result<String> =
        castShell(pkg, "am force-stop $pkg; pm disable-user --user 0 $pkg >/dev/null; sleep 1; pidof $pkg", 12_000)

    private fun castShell(pkg: String, command: String, timeout: Int): Result<String> {
        if (pkg !in CAST_PACKAGES) return Result.failure(IllegalArgumentException("unsupported_receiver"))
        if (!enabled()) return Result.failure(IllegalStateException("not_connected"))
        return try {
            val out = shell(command, port(), false, timeout)
            if (Regex("\\berror:|exception|permission denied|not found", RegexOption.IGNORE_CASE).containsMatchIn(out)) {
                Result.failure(IllegalStateException(out.trim().take(240)))
            } else {
                ready = true
                message = "connected"
                Result.success(out)
            }
        } catch (e: Exception) {
            ready = false
            message = e.message ?: "cast_failed"
            Result.failure(e)
        }
    }

    private fun shell(command: String, port: Int, authorize: Boolean, timeout: Int = 5000): String {
        val deadline = SystemClock.elapsedRealtime() + if (authorize) 60_000 else 30_000
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 2500)
            socket.soTimeout = if (authorize) 30_000 else timeout
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = socket.getOutputStream()
            fun readPacket(): AdbWire.Packet {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) throw SocketTimeoutException("adb_timeout")
                socket.soTimeout = minOf(remaining, if (authorize) 30_000L else timeout.toLong()).coerceAtLeast(1000).toInt()
                return AdbWire.read(input)
            }
            AdbWire.write(output, "CNXN", 0x01000000, 4096, "host::\u0000".toByteArray())
            var sentSignature = false
            var connected = false
            repeat(5) {
                if (!connected) {
                    val packet = readPacket()
                    when (packet.command) {
                        "CNXN" -> connected = true
                        "STLS" -> error("tls_not_supported")
                        "AUTH" -> {
                            check(packet.arg0 == 1 && packet.data.size == 20) { "unsupported_auth" }
                            if (!sentSignature) {
                                AdbWire.write(output, "AUTH", 2, 0, AdbWire.sign(keyPair.private, packet.data))
                                sentSignature = true
                            } else {
                                check(authorize) { "authorization_required" }
                                val publicKey = AdbWire.publicKey(keyPair.public as RSAPublicKey)
                                AdbWire.write(output, "AUTH", 3, 0, "$publicKey luma@android\u0000".toByteArray())
                            }
                        }
                        else -> error("adb_protocol_error")
                    }
                }
            }
            check(connected) { "authorization_required" }
            AdbWire.write(output, "OPEN", 1, 0, "shell:$command\u0000".toByteArray())
            val text = ByteArrayOutputStream()
            var remote = 0
            repeat(64) {
                val packet = readPacket()
                when (packet.command) {
                    "OKAY" -> remote = packet.arg0
                    "WRTE" -> {
                        remote = packet.arg0
                        check(text.size() + packet.data.size <= 65_536) { "adb_response_too_large" }
                        text.write(packet.data)
                        AdbWire.write(output, "OKAY", 1, remote)
                    }
                    "CLSE" -> {
                        AdbWire.write(output, "CLSE", 1, packet.arg0)
                        return text.toString("UTF-8")
                    }
                    else -> error("adb_stream_closed")
                }
            }
            error("adb_response_too_large")
        }
    }

    companion object {
        private val CAST_PACKAGES = setOf("com.weekd.miracastreceiver", "top.jast.miracastreceiver")
        @Volatile private var instance: SystemRemote? = null
        fun get(context: Context): SystemRemote = instance ?: synchronized(this) {
            instance ?: SystemRemote(context).also { instance = it }
        }
    }
}

internal object AdbWire {
    data class Packet(val command: String, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun code(command: String) = ByteBuffer.wrap(command.toByteArray(Charsets.US_ASCII)).order(ByteOrder.LITTLE_ENDIAN).int

    fun write(out: OutputStream, command: String, a: Int, b: Int, data: ByteArray = byteArrayOf()) {
        val code = code(command)
        val checksum = data.sumOf { it.toInt() and 255 }
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(code).putInt(a).putInt(b).putInt(data.size).putInt(checksum).putInt(code.inv()).array()
        out.write(header)
        out.write(data)
        out.flush()
    }

    fun read(input: DataInputStream): Packet {
        val header = ByteArray(24)
        input.readFully(header)
        val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = h.int
        val a = h.int
        val b = h.int
        val length = h.int
        val checksum = h.int
        check(h.int == command.inv() && length in 0..1_048_576) { "invalid_adb_header" }
        val data = ByteArray(length)
        input.readFully(data)
        check(checksum == data.sumOf { it.toInt() and 255 }) { "invalid_adb_checksum" }
        val name = String(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(command).array(), Charsets.US_ASCII)
        return Packet(name, a, b, data)
    }

    fun sign(key: PrivateKey, token: ByteArray): ByteArray {
        val prefix = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
        return Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.ENCRYPT_MODE, key)
            doFinal(prefix + token)
        }
    }

    fun publicKey(key: RSAPublicKey): String {
        require(key.modulus.bitLength() == 2048)
        val n = key.modulus
        val base = BigInteger.ONE.shiftLeft(32)
        val inv = base.subtract(n.modInverse(base)).toLong().toInt()
        fun little(value: BigInteger): ByteArray {
            val result = ByteArray(256)
            val bytes = value.toByteArray().reversedArray()
            System.arraycopy(bytes, 0, result, 0, minOf(bytes.size, 256))
            return result
        }
        val binary = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN).putInt(64).putInt(inv)
            .put(little(n)).put(little(BigInteger.ONE.shiftLeft(4096).mod(n))).putInt(key.publicExponent.toInt()).array()
        return Base64.encodeToString(binary, Base64.NO_WRAP)
    }
}
