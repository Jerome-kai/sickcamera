package io.github.jeromekai.latent.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import java.io.ByteArrayInputStream
import java.net.Proxy
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An https MockWebServer without the okhttp-tls dependency: the provider-image
 * download path refuses anything but https, so its size cap, redirect and
 * cancellation behaviour cannot be exercised over plain http.
 *
 * The keystore is a throwaway self-signed certificate for localhost (100 years)
 * generated once with keytool; it protects nothing and is trusted by nothing
 * but the clients built here.
 */
internal object HardeningTls {
    private const val STORE_PASSWORD = "changeit"
    private const val ALIAS = "latent-test"

    private const val KEYSTORE_BASE64 = """
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
"""

    private val keyStore: KeyStore = KeyStore.getInstance("PKCS12").apply {
        load(ByteArrayInputStream(Base64.getMimeDecoder().decode(KEYSTORE_BASE64.trim())), STORE_PASSWORD.toCharArray())
    }

    private val serverContext: SSLContext = SSLContext.getInstance("TLS").apply {
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keys.init(keyStore, STORE_PASSWORD.toCharArray())
        init(keys.keyManagers, null, null)
    }

    private val trustManager: X509TrustManager = run {
        val trusted = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry(ALIAS, keyStore.getCertificate(ALIAS) as X509Certificate)
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(trusted)
        factory.trustManagers.filterIsInstance<X509TrustManager>().single()
    }

    private val clientContext: SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustManager), null)
    }

    /** A started https server speaking HTTP/1.1 only, so framing is predictable. */
    fun httpsServer(): MockWebServer = MockWebServer().apply {
        useHttps(serverContext.socketFactory, false)
        protocols = listOf(Protocol.HTTP_1_1)
        start()
    }

    /** Trusts the fixture certificate for any host name; never uses the sandbox proxy. */
    fun trustingClient(): OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(clientContext.socketFactory, trustManager)
        .hostnameVerifier { _, _ -> true }
        .proxy(Proxy.NO_PROXY)
        .build()

    /** [size] bytes starting with a PNG signature, built segment by segment so 40 MB stays cheap. */
    fun pngBody(size: Long): Buffer {
        val buffer = Buffer()
        val signature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
        buffer.write(signature)
        val chunk = ByteArray(64 * 1024)
        var remaining = size - signature.size
        while (remaining > 0) {
            val n = minOf(remaining, chunk.size.toLong()).toInt()
            buffer.write(chunk, 0, n)
            remaining -= n
        }
        return buffer
    }
}

class HardeningTlsFixtureTest {
    @Test
    fun theFixtureServerCompletesAHandshakeAndTheStockClientDoesNot() {
        val server = HardeningTls.httpsServer()
        try {
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("hello"))
            val request = okhttp3.Request.Builder().url(server.url("/")).build()
            HardeningTls.trustingClient().newCall(request).execute().use {
                assertEquals("hello", it.body!!.string())
            }
            // A client that has not been told to trust the certificate must refuse it,
            // or the https-only guard tests would prove nothing.
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("hello"))
            val stock = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build()
            val refused = runCatching { stock.newCall(request).execute().close() }
            assertEquals(true, refused.isFailure)
        } finally {
            server.shutdown()
        }
    }
}
