package dev.nucleusframework.nativessl

import dev.nucleusframework.core.runtime.Platform
import dev.nucleusframework.nativessl.linux.LinuxCertificateProvider
import dev.nucleusframework.nativessl.mac.NativeSslBridge
import dev.nucleusframework.nativessl.windows.WindowsCertificateProvider
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

private const val TAG = "NativeCertificateProvider"

internal object NativeCertificateProvider {
    fun getSystemCertificates(): List<X509Certificate> {
        val derCerts = getRawCertificates()
        if (derCerts.isEmpty()) return emptyList()

        val factory = CertificateFactory.getInstance("X.509")
        return derCerts.mapNotNull { der ->
            @Suppress("TooGenericExceptionCaught")
            try {
                factory.generateCertificate(der.inputStream()) as X509Certificate
            } catch (e: Exception) {
                debugln(TAG) { "Skipping unparseable certificate: ${e.message}" }
                null
            }
        }
    }

    private fun getRawCertificates(): List<ByteArray> =
        when (Platform.Current) {
            Platform.MacOS -> NativeSslBridge.getSystemCertificates()
            Platform.Linux -> LinuxCertificateProvider.getSystemCertificates()
            Platform.Windows -> WindowsCertificateProvider.getSystemCertificates()
            Platform.Unknown -> emptyList()
        }
}
