package com.singularity_universe.explorer.remote

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * ADB client identity for talking to this phone's own Wireless debugging service.
 *
 * The RSA key and self-signed certificate are generated once and kept in the app's private
 * no-backup storage; Android remembers them after pairing, so later connections need no code.
 */
internal class AdbManager(context: Context) : AbsAdbConnectionManager() {
    private val keyFile = File(context.noBackupFilesDir, "adb/key.pk8")
    private val certFile = File(context.noBackupFilesDir, "adb/cert.der")

    private val privateKey: PrivateKey
    private val certificate: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        setTimeout(10, TimeUnit.SECONDS)
        val stored = runCatching { load() }.getOrNull()
        if (stored != null) {
            privateKey = stored.first
            certificate = stored.second
        } else {
            val generated = generate()
            privateKey = generated.first
            certificate = generated.second
            keyFile.parentFile?.mkdirs()
            keyFile.writeBytes(privateKey.encoded)
            certFile.writeBytes(certificate.encoded)
        }
    }

    override fun getPrivateKey(): PrivateKey = privateKey

    override fun getCertificate(): Certificate = certificate

    override fun getDeviceName(): String = "Explorer"

    /** Removes the stored identity, so the next connection has to pair again. */
    fun forget() {
        keyFile.delete()
        certFile.delete()
    }

    private fun load(): Pair<PrivateKey, Certificate> {
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
        val cert = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        return key to cert
    }

    private fun generate(): Pair<PrivateKey, Certificate> {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=Explorer")
        val now = System.currentTimeMillis()
        val holder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            Date(now),
            Date(now + TimeUnit.DAYS.toMillis(365L * 30)),
            name,
            keyPair.public,
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))
        return keyPair.private to JcaX509CertificateConverter().getCertificate(holder)
    }
}
