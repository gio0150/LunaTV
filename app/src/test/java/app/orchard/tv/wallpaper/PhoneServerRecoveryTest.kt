package app.orchard.tv.wallpaper

import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class PhoneServerRecoveryTest {
    @Test fun startsOnAnotherPortWhenDefaultIsOccupied() {
        ServerSocket(PhoneServer.PORT).use {
            val server = PhoneServer.listen(RuntimeEnvironment.getApplication())
            assertNotNull(server)
            try {
                assertEquals(PhoneServer.PORT + 1, server!!.listeningPort)
                assertTrue(server.isAlive)
            } finally {
                server?.stop()
            }
        }
    }

    @Test fun tokenAddressWithoutSlashRedirectsToCanonicalPage() {
        val server = PhoneServer.listen(RuntimeEnvironment.getApplication())!!
        try {
            val field = server.javaClass.getDeclaredField("token").apply { isAccessible = true }
            val token = field.get(server) as String
            val connection = java.net.URL("http://127.0.0.1:${server.listeningPort}/$token")
                .openConnection() as java.net.HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            try {
                assertEquals(301, connection.responseCode)
                assertEquals("/$token/", connection.getHeaderField("Location"))
            } finally { connection.disconnect() }
        } finally { server.stop() }
    }

    @Test fun fallbackOnlyAcceptsRecognizedPhysicalInterfaces() {
        listOf("eth0", "wlan0", "wifi0", "en0").forEach { assertTrue(it, PhoneServer.isPhysicalLanName(it)) }
        listOf("tun0", "ppp0", "lo", "ap0", "wifi-aware0").forEach { assertFalse(it, PhoneServer.isPhysicalLanName(it)) }
    }
}
