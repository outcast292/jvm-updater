package io.github.outcast292.jvmupdater;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignaturesTest {

    private static final byte[] DATA = "format=1\nversion=1.0\nmain-class=a.Main\n".getBytes(StandardCharsets.UTF_8);

    @Test
    void verifiesWhatItSigned() {
        KeyPair keys = Signatures.generateKeyPair();
        byte[] signature = Signatures.sign(DATA, keys.getPrivate());

        assertTrue(Signatures.verify(DATA, signature, List.of(keys.getPublic())));
    }

    @Test
    void refusesChangedData() {
        KeyPair keys = Signatures.generateKeyPair();
        byte[] signature = Signatures.sign(DATA, keys.getPrivate());
        byte[] changed = DATA.clone();
        changed[changed.length - 2] = 'X';

        assertFalse(Signatures.verify(changed, signature, List.of(keys.getPublic())));
    }

    @Test
    void acceptsAnyTrustedKeySoKeysCanBeRotated() {
        KeyPair oldKeys = Signatures.generateKeyPair();
        KeyPair newKeys = Signatures.generateKeyPair();
        byte[] signature = Signatures.sign(DATA, newKeys.getPrivate());

        assertTrue(Signatures.verify(DATA, signature, List.of(oldKeys.getPublic(), newKeys.getPublic())));
        assertFalse(Signatures.verify(DATA, signature, List.of(oldKeys.getPublic())));
    }

    @Test
    void refusesGarbageSignatures() {
        KeyPair keys = Signatures.generateKeyPair();
        assertFalse(Signatures.verify(DATA, "not base64 !".getBytes(StandardCharsets.US_ASCII), List.of(keys.getPublic())));
        assertFalse(Signatures.verify(DATA, "AAAA".getBytes(StandardCharsets.US_ASCII), List.of(keys.getPublic())));
    }

    @Test
    void readsKeysBackFromPemAndBase64() {
        KeyPair keys = Signatures.generateKeyPair();

        assertEquals(keys.getPublic(), Signatures.readPublicKey(Signatures.toPem(keys.getPublic())));
        assertEquals(keys.getPublic(), Signatures.readPublicKey(Signatures.toBase64(keys.getPublic())));

        byte[] signature = Signatures.sign(DATA, Signatures.readPrivateKey(Signatures.toPem(keys.getPrivate())));
        assertTrue(Signatures.verify(DATA, signature, List.of(keys.getPublic())));
    }

    @Test
    void readsOpensslKeys() {
        // openssl genpkey -algorithm ed25519 | openssl pkey -pubout
        String pem = """
                -----BEGIN PUBLIC KEY-----
                MCowBQYDK2VwAyEAWojkQCDd5U7O98hzREadQxnG52nTGfrJAEEbSCpGnj8=
                -----END PUBLIC KEY-----
                """;
        assertEquals("EdDSA", Signatures.readPublicKey(pem).getAlgorithm());
    }

    @Test
    void refusesOtherKeyTypes() throws Exception {
        KeyPair rsa = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        assertThrows(IllegalArgumentException.class,
                () -> Signatures.readPublicKey(java.util.Base64.getEncoder().encodeToString(rsa.getPublic().getEncoded())));
        assertThrows(IllegalArgumentException.class, () -> Signatures.readPrivateKey(Signatures.toPem(Signatures.generateKeyPair().getPublic())));
    }
}
