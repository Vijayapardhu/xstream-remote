package com.xstream.remote;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * A client certificate created on this phone the first time it pairs.
 *
 * Android has no public API for creating a self-signed X.509 certificate from
 * an RSA key pair, and pulling in BouncyCastle would dwarf the rest of the APK.
 * The DER writer below covers exactly the fields the TV pairing handshake needs:
 * a CN, a DNS SAN, key usage, and a SHA-256 RSA signature.
 */
final class ClientIdentity {

    static final String CLIENT_NAME = "Xstream Remote";

    final byte[] certDer;
    final byte[] keyDer;
    final String fingerprint;

    private ClientIdentity(byte[] certDer, byte[] keyDer, String fingerprint) {
        this.certDer = certDer;
        this.keyDer = keyDer;
        this.fingerprint = fingerprint;
    }

    static ClientIdentity loadOrCreate(File dir) throws Exception {
        if (dir == null) {
            throw new IOException("No identity directory");
        }
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw new IOException("Could not create identity directory");
        }

        File certFile = new File(dir, "client-cert.der");
        File keyFile = new File(dir, "client-key.der");
        if (certFile.isFile() && keyFile.isFile()) {
            try {
                return load(certFile, keyFile);
            } catch (Exception ignored) {
                // A half-written or corrupt identity is replaced below. The user
                // will be asked to pair again, which is the only safe recovery.
            }
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, new SecureRandom());
        KeyPair pair = generator.generateKeyPair();
        byte[] cert = SelfSigned.create(pair, CLIENT_NAME);
        byte[] key = pair.getPrivate().getEncoded();
        if (key == null || key.length == 0) {
            throw new IOException("RSA key could not be encoded");
        }

        writeAtomic(certFile, cert);
        writeAtomic(keyFile, key);
        return new ClientIdentity(cert, key, sha256(cert));
    }

    private static ClientIdentity load(File certFile, File keyFile) throws Exception {
        byte[] certDer = readAll(certFile);
        byte[] keyDer = readAll(keyFile);
        if (certDer.length == 0 || keyDer.length == 0) {
            throw new IOException("Empty identity");
        }

        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        X509Certificate certificate = (X509Certificate) factory.generateCertificate(
                new ByteArrayInputStream(certDer));
        certificate.checkValidity();

        PrivateKey privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(keyDer));

        // Make sure the certificate and private key belong together before
        // handing them to the TLS stack.
        byte[] probe = "xstream-identity-check".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(probe);
        byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(certificate.getPublicKey());
        verifier.update(probe);
        if (!verifier.verify(signature)) {
            throw new IOException("Identity key does not match certificate");
        }

        return new ClientIdentity(certDer, keyDer, sha256(certDer));
    }

    private static byte[] readAll(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static void writeAtomic(File file, byte[] data) throws IOException {
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(data);
            out.flush();
            out.getFD().sync();
        }
        if (!temp.renameTo(file)) {
            // Some Android filesystems refuse rename over an existing file.
            if (file.exists() && !file.delete()) {
                throw new IOException("Could not replace " + file.getName());
            }
            if (!temp.renameTo(file)) {
                throw new IOException("Could not write " + file.getName());
            }
        }
        file.setReadable(false, false);
        file.setReadable(true, true);
        file.setWritable(false, false);
        file.setWritable(true, true);
    }

    static String sha256(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                if (sb.length() > 0) {
                    sb.append(':');
                }
                sb.append(String.format(Locale.US, "%02X", b & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Minimal DER encoder and self-signed certificate builder.
     */
    private static final class SelfSigned {

        private static final String SHA256_RSA = "1.2.840.113549.1.1.11";
        private static final String RSA = "1.2.840.113549.1.1.1";
        private static final String CN = "2.5.4.3";
        private static final String SAN = "2.5.29.17";
        private static final String BASIC_CONSTRAINTS = "2.5.29.19";
        private static final String KEY_USAGE = "2.5.29.15";
        private static final String EXT_KEY_USAGE = "2.5.29.37";
        private static final String CLIENT_AUTH = "1.3.6.1.5.5.7.3.2";

        static byte[] create(KeyPair pair, String commonName) throws Exception {
            if (!(pair.getPublic() instanceof RSAPublicKey)) {
                throw new IllegalArgumentException("RSA key pair required");
            }
            RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
            Date now = new Date();
            Date notBefore = new Date(now.getTime() - 24L * 60L * 60L * 1000L);
            Date notAfter = new Date(now.getTime() + 10L * 365L * 24L * 60L * 60L * 1000L);

            byte[] signatureAlgorithm = sequence(oid(SHA256_RSA), NULL);
            byte[] name = name(commonName);
            byte[] validity = sequence(utcTime(notBefore), utcTime(notAfter));
            byte[] subjectPublicKeyInfo = subjectPublicKeyInfo(publicKey);

            byte[] extensions = context(3, sequence(
                    extension(SAN, false, sequence(generalName(2, "xstream-remote"))),
                    extension(BASIC_CONSTRAINTS, true, sequence()),
                    extension(KEY_USAGE, true, bitString(new byte[]{(byte) 0xA0}, 5)),
                    extension(EXT_KEY_USAGE, false, sequence(oid(CLIENT_AUTH)))));

            byte[] tbs = sequence(
                    context(0, integer(BigInteger.valueOf(2))),
                    integer(new BigInteger(63, new SecureRandom()).add(BigInteger.ONE)),
                    signatureAlgorithm,
                    name,
                    validity,
                    name,
                    subjectPublicKeyInfo,
                    extensions);

            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(pair.getPrivate());
            signer.update(tbs);
            byte[] signature = signer.sign();

            byte[] der = sequence(tbs, signatureAlgorithm,
                    bitString(signature, 0));

            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            X509Certificate certificate = (X509Certificate) factory.generateCertificate(
                    new ByteArrayInputStream(der));
            certificate.verify(certificate.getPublicKey());
            return der;
        }

        private static byte[] name(String commonName) {
            return sequence(set(sequence(oid(CN),
                    tlv(0x0C, commonName.getBytes(StandardCharsets.UTF_8)))));
        }

        private static byte[] subjectPublicKeyInfo(RSAPublicKey key) {
            byte[] rsa = sequence(integer(key.getModulus()), integer(key.getPublicExponent()));
            return sequence(sequence(oid(RSA), NULL), bitString(rsa, 0));
        }

        private static byte[] generalName(int tag, String value) {
            return tlv(0x80 | tag, value.getBytes(StandardCharsets.US_ASCII));
        }

        private static byte[] extension(String oid, boolean critical, byte[] value) {
            if (critical) {
                return sequence(oid(oid), tlv(0x01, new byte[]{(byte) 0xFF}), octet(value));
            }
            return sequence(oid(oid), octet(value));
        }

        private static final byte[] NULL = tlv(0x05, new byte[0]);

        private static byte[] integer(BigInteger value) {
            return tlv(0x02, value.toByteArray());
        }

        private static byte[] utcTime(Date date) {
            SimpleDateFormat format = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            return tlv(0x17, format.format(date).getBytes(StandardCharsets.US_ASCII));
        }

        private static byte[] bitString(byte[] data, int unusedBits) {
            byte[] content = new byte[data.length + 1];
            content[0] = (byte) unusedBits;
            System.arraycopy(data, 0, content, 1, data.length);
            return tlv(0x03, content);
        }

        private static byte[] octet(byte[] data) {
            return tlv(0x04, data);
        }

        private static byte[] oid(String dotted) {
            String[] parts = dotted.split("\\.");
            if (parts.length < 2) {
                throw new IllegalArgumentException("Bad OID " + dotted);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            long first = Long.parseLong(parts[0]) * 40L + Long.parseLong(parts[1]);
            writeBase128(out, first);
            for (int i = 2; i < parts.length; i++) {
                writeBase128(out, Long.parseLong(parts[i]));
            }
            return tlv(0x06, out.toByteArray());
        }

        private static void writeBase128(ByteArrayOutputStream out, long value) {
            int groups = 1;
            long copy = value;
            while ((copy >>>= 7) != 0) {
                groups++;
            }
            for (int i = groups - 1; i >= 0; i--) {
                int b = (int) ((value >>> (i * 7)) & 0x7F);
                if (i != 0) {
                    b |= 0x80;
                }
                out.write(b);
            }
        }

        private static byte[] context(int tag, byte[] content) {
            return tlv(0xA0 | tag, content);
        }

        private static byte[] sequence(byte[]... chunks) {
            return tlv(0x30, cat(chunks));
        }

        private static byte[] set(byte[]... chunks) {
            return tlv(0x31, cat(chunks));
        }

        private static byte[] tlv(int tag, byte[] content) {
            return cat(new byte[]{(byte) tag}, length(content.length), content);
        }

        private static byte[] length(int length) {
            if (length < 0x80) {
                return new byte[]{(byte) length};
            }
            int bytes = 0;
            int copy = length;
            while (copy > 0) {
                bytes++;
                copy >>>= 8;
            }
            byte[] out = new byte[bytes + 1];
            out[0] = (byte) (0x80 | bytes);
            for (int i = 0; i < bytes; i++) {
                out[bytes - i] = (byte) (length >>> (i * 8));
            }
            return out;
        }

        private static byte[] cat(byte[]... chunks) {
            int size = 0;
            for (byte[] chunk : chunks) {
                if (chunk != null) {
                    size += chunk.length;
                }
            }
            byte[] out = new byte[size];
            int at = 0;
            for (byte[] chunk : chunks) {
                if (chunk != null) {
                    System.arraycopy(chunk, 0, out, at, chunk.length);
                    at += chunk.length;
                }
            }
            return out;
        }
    }
}
