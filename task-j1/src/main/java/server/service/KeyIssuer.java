package server.service;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import server.model.RSAKeys;
import server.model.User;

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Выдача пары ключей RSA и сертификата X509 (SRP: только криптография,
 * никакого кэша и никакой сети). Ключом CA подписывает сертификат с Subject CN = имени клиента.
 */
public class KeyIssuer {

    public static final int KEY_SIZE = 8192;
    private static final String SIGNATURE_ALG = "SHA256withRSA";

    private final X500Name issuerName;
    private final PrivateKey caKey;
    private final int keySize;
    private final AtomicInteger serialNumber = new AtomicInteger();

    public KeyIssuer(String issuerName, PrivateKey caKey) {
        this(issuerName, caKey, KEY_SIZE);
    }

    /** Размер ключа — параметр, чтобы тесты не ждали генерацию 8192 бит. */
    public KeyIssuer(String issuerName, PrivateKey caKey, int keySize) {
        this.issuerName = new X500Name(issuerName);
        this.caKey = caKey;
        this.keySize = keySize;
    }

    /** Генерация пары ключей и подпись сертификата. Вызывается только генерирующими нитями. */
    public User issue(String name) throws Exception {
        long startTime = System.currentTimeMillis();
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(keySize);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();

        Instant now = Instant.now();
        X509v3CertificateBuilder certificateBuilder = new JcaX509v3CertificateBuilder(
                issuerName,
                BigInteger.valueOf(serialNumber.incrementAndGet()),
                Date.from(now.minus(Duration.ofDays(1))),
                Date.from(now.plus(Duration.ofDays(365))),
                subjectName(name),
                keyPair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder(SIGNATURE_ALG).build(caKey);
        var certificate = new JcaX509CertificateConverter().getCertificate(certificateBuilder.build(signer));

        System.out.printf("[gen] %s in %d ms%n", name, System.currentTimeMillis() - startTime);
        return new User(name, new RSAKeys(toPem(keyPair.getPrivate()), toPem(keyPair.getPublic()), toPem(certificate)));
    }

    /** Subject DN с CN = переданному имени; builder сам корректно обрабатывает спецсимволы. */
    private static X500Name subjectName(String name) {
        return new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, name).build();
    }

    private static String toPem(Object object) throws IOException {
        StringWriter stringWriter = new StringWriter();
        try (JcaPEMWriter pemWriter = new JcaPEMWriter(stringWriter)) {
            pemWriter.writeObject(object);
        }
        return stringWriter.toString();
    }

    /** Читает приватный ключ CA из PEM-файла (PKCS#8 или PKCS#1). */
    public static PrivateKey loadCaKey(Path file) throws IOException {
        try (PEMParser parser = new PEMParser(Files.newBufferedReader(file))) {
            Object object = parser.readObject();
            PrivateKeyInfo info = object instanceof PrivateKeyInfo pi
                    ? pi
                    : ((PEMKeyPair) object).getPrivateKeyInfo();
            return new JcaPEMKeyConverter().getPrivateKey(info);
        }
    }
}
