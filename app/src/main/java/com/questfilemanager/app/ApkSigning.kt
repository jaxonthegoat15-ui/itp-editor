package com.questfilemanager.app

import android.content.Context
import com.android.apksig.ApkSigner
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/** Creates a stable local signing identity for APKs modified by this app. */
class ApkSigning(context: Context) {
    private val keyFile = File(context.filesDir, "quest-file-manager-key.pk8")
    private val certFile = File(context.filesDir, "quest-file-manager-cert.der")

    private fun credentials(): Pair<PrivateKey, X509Certificate> {
        if (!keyFile.exists() || !certFile.exists()) createCredentials()
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(certFile.inputStream()) as X509Certificate
        return key to cert
    }

    private fun createCredentials() {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val pair = generator.generateKeyPair()
        val now = System.currentTimeMillis()
        val subject = X500Name("CN=Quest File Manager Local Signing")
        val builder = JcaX509v3CertificateBuilder(
            subject, BigInteger.valueOf(now), Date(now - 60_000),
            Date(now + 10L * 365 * 24 * 60 * 60 * 1000), subject, pair.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(pair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        keyFile.writeBytes(pair.private.encoded)
        certFile.writeBytes(certificate.encoded)
    }

    fun sign(input: File, output: File) {
        val (key, certificate) = credentials()
        val config = ApkSigner.SignerConfig.Builder("QuestFileManager", key, listOf(certificate)).build()
        ApkSigner.Builder(listOf(config))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()
    }
}
