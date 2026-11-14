package io.github.outcast292.jvmupdater;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;

/**
 * Ed25519 keys and signatures, with nothing but the JDK.
 *
 * <p>Keys are read from PEM ({@code -----BEGIN PUBLIC KEY-----}, as written by
 * {@code openssl genpkey -algorithm ed25519}) or from the bare base64 of the same DER bytes. A
 * signature file holds the base64 of the 64 signature bytes on one line.
 */
public final class Signatures {

    private static final String ALGORITHM = "Ed25519";

    private Signatures() {
    }

    public static KeyPair generateKeyPair() {
        try {
            return KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("This JDK has no Ed25519 support", e);
        }
    }

    public static PublicKey readPublicKey(String text) {
        try {
            return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(decode(text, "PUBLIC KEY")));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Not an Ed25519 public key", e);
        }
    }

    public static PrivateKey readPrivateKey(String text) {
        try {
            return KeyFactory.getInstance(ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(decode(text, "PRIVATE KEY")));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Not an Ed25519 private key", e);
        }
    }

    /** One-line base64 form of a public key, handy for command line arguments. */
    public static String toBase64(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static String toPem(PublicKey key) {
        return pem("PUBLIC KEY", key.getEncoded());
    }

    public static String toPem(PrivateKey key) {
        return pem("PRIVATE KEY", key.getEncoded());
    }

    /** Signs {@code data} and returns the content of a signature file. */
    public static byte[] sign(byte[] data, PrivateKey key) {
        try {
            Signature signature = Signature.getInstance(ALGORITHM);
            signature.initSign(key);
            signature.update(data);
            String encoded = Base64.getEncoder().encodeToString(signature.sign()) + "\n";
            return encoded.getBytes(StandardCharsets.US_ASCII);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Could not sign with this key", e);
        }
    }

    /** True when one of {@code trustedKeys} made {@code signatureFile} for exactly {@code data}. */
    public static boolean verify(byte[] data, byte[] signatureFile, Collection<PublicKey> trustedKeys) {
        byte[] signatureBytes;
        try {
            signatureBytes = Base64.getDecoder().decode(new String(signatureFile, StandardCharsets.US_ASCII).strip());
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (PublicKey key : trustedKeys) {
            try {
                Signature signature = Signature.getInstance(ALGORITHM);
                signature.initVerify(key);
                signature.update(data);
                if (signature.verify(signatureBytes)) {
                    return true;
                }
            } catch (GeneralSecurityException e) {
                // Wrong key type or malformed signature: this key didn't sign it, try the next one.
            }
        }
        return false;
    }

    private static byte[] decode(String text, String pemType) {
        String body = text.strip();
        String begin = "-----BEGIN " + pemType + "-----";
        String end = "-----END " + pemType + "-----";
        if (body.startsWith("-----BEGIN")) {
            int from = body.indexOf(begin);
            int to = body.indexOf(end);
            if (from != 0 || to < 0) {
                throw new IllegalArgumentException("Expected a PEM block of type " + pemType);
            }
            body = body.substring(begin.length(), to);
        }
        try {
            return Base64.getMimeDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Key is neither PEM nor base64", e);
        }
    }

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }
}
