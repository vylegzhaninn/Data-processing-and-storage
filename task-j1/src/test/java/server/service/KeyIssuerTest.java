package server.service;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.junit.jupiter.api.Test;
import server.model.User;

import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyIssuerTest {

    private static final String ISSUER = "CN=Test CA";
    private static final int TEST_KEY_SIZE = 1024;

    private static KeyPair generateKeyPair(int keySize) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(keySize);
        return generator.generateKeyPair();
    }

    private static X509Certificate parseCertificate(String pem) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) factory.generateCertificate(
                new java.io.ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
    }

    private static PrivateKey parsePrivateKey(String pem) throws Exception {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            PrivateKeyInfo info = ((PEMKeyPair) parser.readObject()).getPrivateKeyInfo();
            return new JcaPEMKeyConverter().getPrivateKey(info);
        }
    }

    /** Восстанавливает публичный ключ из приватного (RSA CRT содержит модуль и экспоненту). */
    private static PublicKey publicKeyOf(PrivateKey privateKey) throws Exception {
        RSAPrivateCrtKey crtKey = (RSAPrivateCrtKey) KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(privateKey.getEncoded()));
        return KeyFactory.getInstance("RSA").generatePublic(
                new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent()));
    }

    private static PublicKey parsePublicKey(String pem) throws Exception {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            SubjectPublicKeyInfo info = (SubjectPublicKeyInfo) parser.readObject();
            return new JcaPEMKeyConverter().getPublicKey(info);
        }
    }

    @Test
    void issuedUserCarriesMatchingKeyAndCertificate() throws Exception {
        KeyPair ca = generateKeyPair(2048);
        KeyIssuer issuer = new KeyIssuer(ISSUER, ca.getPrivate(), TEST_KEY_SIZE);

        User user = issuer.issue("alice");
        X509Certificate certificate = parseCertificate(user.keys().certificate());
        PrivateKey privateKey = parsePrivateKey(user.keys().privateKey());

        assertEquals("alice", user.userName());
        assertEquals("CN=alice", certificate.getSubjectX500Principal().getName());
        assertEquals(ISSUER, certificate.getIssuerX500Principal().getName());
        assertEquals(TEST_KEY_SIZE, ((RSAPrivateCrtKey) privateKey).getModulus().bitLength());

        assertArrayEquals(publicKeyOf(privateKey).getEncoded(), certificate.getPublicKey().getEncoded());
        PublicKey issuedPublicKey = parsePublicKey(user.keys().publicKey());
        assertArrayEquals(issuedPublicKey.getEncoded(), certificate.getPublicKey().getEncoded());
        certificate.verify(ca.getPublic());

        Date now = new Date();
        assertTrue(certificate.getNotBefore().before(now));
        assertTrue(certificate.getNotAfter().after(now));
    }

    @Test
    void eachCertificateGetsUniqueSerialNumber() throws Exception {
        KeyPair ca = generateKeyPair(2048);
        KeyIssuer issuer = new KeyIssuer(ISSUER, ca.getPrivate(), TEST_KEY_SIZE);

        BigInteger first = parseCertificate(issuer.issue("a").keys().certificate()).getSerialNumber();
        BigInteger second = parseCertificate(issuer.issue("b").keys().certificate()).getSerialNumber();

        assertNotEquals(first, second);
    }

    @Test
    void defaultKeySizeMatchesTaskRequirement() {
        assertEquals(8192, KeyIssuer.KEY_SIZE);
    }

    @Test
    void loadCaKeyReadsBackWhatWasWritten() throws Exception {
        KeyPair ca = generateKeyPair(2048);
        Path file = Files.createTempFile("ca", ".pem");
        try (JcaPEMWriter pemWriter = new JcaPEMWriter(Files.newBufferedWriter(file))) {
            pemWriter.writeObject(ca.getPrivate());
        }

        PrivateKey loaded = KeyIssuer.loadCaKey(file);
        Files.deleteIfExists(file);

        assertArrayEquals(ca.getPrivate().getEncoded(), loaded.getEncoded());
    }

    @Test
    void pemRoundTripIsStable() throws Exception {
        StringWriter buffer = new StringWriter();
        JcaPEMWriter pemWriter = new JcaPEMWriter(buffer);
        pemWriter.writeObject(generateKeyPair(2048).getPrivate());
        pemWriter.close();
        assertTrue(buffer.toString().contains("PRIVATE KEY-----"), () -> "неожиданный PEM:\n" + buffer);
    }
}
