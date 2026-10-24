package dev.pgcp.agent.tls;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.springframework.stereotype.Component;

import dev.pgcp.agent.config.AgentException;
import dev.pgcp.agent.config.AgentProperties;

/**
 * Local CA that signs server certificates of managed instances, so clients can use sslmode=verify-full
 * after downloading the CA certificate. Created on first use and kept in the agent data directory.
 */
@Component
public class InstanceCa {

    public record IssuedCertificate(String certificatePem, String privateKeyPem) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path caCert;
    private final Path caKey;

    public InstanceCa(AgentProperties props) {
        this.caCert = props.caDir().resolve("instances-ca.crt");
        this.caKey = props.caDir().resolve("instances-ca.key");
    }

    public synchronized String caCertificatePem() {
        ensureCa();
        try {
            return Files.readString(caCert);
        } catch (IOException e) {
            throw new AgentException(AgentException.Kind.FAILED, "cannot read CA certificate", e);
        }
    }

    public synchronized IssuedCertificate issueServerCertificate(String commonName, List<String> hostnames) {
        ensureCa();
        try {
            X509Certificate ca = readCertificate(caCert);
            PrivateKey key = readPrivateKey(caKey);
            KeyPair pair = newKeyPair();

            List<GeneralName> names = new ArrayList<>();
            for (String h : new LinkedHashSet<>(hostnames)) {
                names.add(new GeneralName(GeneralName.dNSName, h));
            }
            names.add(new GeneralName(GeneralName.iPAddress, "127.0.0.1"));

            Instant now = Instant.now();
            var builder = new JcaX509v3CertificateBuilder(ca, serial(), Date.from(now.minus(Duration.ofMinutes(5))),
                    Date.from(now.plus(Duration.ofDays(825))), new X500Name("CN=" + commonName), pair.getPublic());
            var ext = new JcaX509ExtensionUtils();
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
            builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
            builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(names.toArray(GeneralName[]::new)));
            builder.addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(pair.getPublic()));
            builder.addExtension(Extension.authorityKeyIdentifier, false, ext.createAuthorityKeyIdentifier(ca));
            X509Certificate cert = sign(builder, key);
            return new IssuedCertificate(toPem(cert), toPem(pair.getPrivate()));
        } catch (GeneralSecurityException | IOException | org.bouncycastle.operator.OperatorCreationException e) {
            throw new AgentException(AgentException.Kind.FAILED, "cannot issue certificate", e);
        }
    }

    private void ensureCa() {
        if (Files.exists(caCert) && Files.exists(caKey)) {
            return;
        }
        try {
            Files.createDirectories(caKey.getParent());
            KeyPair pair = newKeyPair();
            X500Name name = new X500Name("CN=pgcp instances CA");
            Instant now = Instant.now();
            var builder = new JcaX509v3CertificateBuilder(name, serial(), Date.from(now.minus(Duration.ofMinutes(5))),
                    Date.from(now.plus(Duration.ofDays(3650))), name, pair.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(0));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            builder.addExtension(Extension.subjectKeyIdentifier, false,
                    new JcaX509ExtensionUtils().createSubjectKeyIdentifier(pair.getPublic()));
            X509Certificate cert = sign(builder, pair.getPrivate());
            writePrivate(caKey, toPem(pair.getPrivate()));
            Files.writeString(caCert, toPem(cert), StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException | IOException | org.bouncycastle.operator.OperatorCreationException e) {
            throw new AgentException(AgentException.Kind.FAILED, "cannot create instance CA", e);
        }
    }

    public static void writePrivate(Path file, String content) throws IOException {
        Files.deleteIfExists(file);
        Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static X509Certificate sign(JcaX509v3CertificateBuilder builder, PrivateKey key)
            throws GeneralSecurityException, org.bouncycastle.operator.OperatorCreationException {
        var signer = new JcaContentSignerBuilder("SHA256withECDSA").build(key);
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private static KeyPair newKeyPair() throws GeneralSecurityException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        return kpg.generateKeyPair();
    }

    private static BigInteger serial() {
        return new BigInteger(128, RANDOM);
    }

    private static String toPem(Object obj) throws IOException {
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter w = new JcaPEMWriter(sw)) {
            if (obj instanceof PrivateKey key) {
                w.writeObject(new JcaPKCS8Generator(key, null));
            } else {
                w.writeObject(obj);
            }
        }
        return sw.toString();
    }

    private static X509Certificate readCertificate(Path file) throws IOException, GeneralSecurityException {
        try (Reader r = Files.newBufferedReader(file); PEMParser p = new PEMParser(r)) {
            return new JcaX509CertificateConverter().getCertificate((X509CertificateHolder) p.readObject());
        }
    }

    private static PrivateKey readPrivateKey(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file); PEMParser p = new PEMParser(r)) {
            return new JcaPEMKeyConverter().getPrivateKey((PrivateKeyInfo) p.readObject());
        }
    }
}
