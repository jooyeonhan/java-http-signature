package com.joyent.http.signature;

import com.joyent.http.signature.KeyPairLoader.DesiredSecurityProvider;
import org.bouncycastle.openssl.PEMEncryptor;
import org.bouncycastle.openssl.jcajce.JcaMiscPEMGenerator;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcePEMEncryptorBuilder;
import org.testng.Assert;
import org.testng.AssertJUnit;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.util.UUID;

import static com.joyent.http.signature.KeyPairLoader.PROVIDER_PKCS11_NSS;

@Test
public class KeyPairLoaderTest {

    private static final String RSA_HEADER = "-----BEGIN RSA PRIVATE KEY-----";

    /**
     * Since serializing a public key to the OpenSSH format would require an external library we've generated
     * a key just for testing. The corresponding private key was destroyed.
     */
    private static final String TEST_PUBLIC_KEY =
            "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAAAgQDZbnRl5z0dd3NAvRprIrUcRJxJzhnNvozxlBUYj47+OtxP6dlETjrvMHtqg7/+NZ" +
                    "C+4/HyXF8MBYmNKQXXzOOThIlG/Cxx4rSNRx3TBeKijQ8+gw2c5QwM/0SHv73WGgL81Br07Fk3y2I7eqHvyBD428jBoOgH" +
                    "+ecGT4pdL/Zyrw==";

    private static final ClassLoader CLASS_LOADER = KeyPairLoaderTest.class.getClassLoader();

    public void willThrowOnNullInputs() {
        Assert.assertThrows(() ->
                KeyPairLoader.getKeyPair((File) null, null));
        Assert.assertThrows(() ->
                KeyPairLoader.getKeyPair((Path) null, null));
        Assert.assertThrows(() ->
                KeyPairLoader.getKeyPair((String) null, null));
        Assert.assertThrows(() ->
                KeyPairLoader.getKeyPair((InputStream) null, null));
    }

    public void willThrowUsefulExceptionsOnEmptyFile() throws IOException {
        final Path emptyFile = Files.createTempFile("id_rsa", "");

        final IOException emptyFileException = Assert.expectThrows(IOException.class, () ->
                KeyPairLoader.getKeyPair(emptyFile));

        // error message should contain the path specified and the words "private key"
        Assert.assertTrue(emptyFileException.getMessage().contains(emptyFile.toString()));
        Assert.assertTrue(emptyFileException.getMessage().contains("private key"));
    }

    public void willThrowUsefulExceptionsOnPublicKeyFile() throws Exception {
        final Path publicKeyFile = Files.createTempFile("id_rsa", ".pub");

        try (final FileOutputStream fos = new FileOutputStream(publicKeyFile.toFile())) {
            fos.write(TEST_PUBLIC_KEY.getBytes(StandardCharsets.UTF_8));
        }

        final IOException publicKeyFileException = Assert.expectThrows(IOException.class, () ->
                KeyPairLoader.getKeyPair(publicKeyFile));

        // error message should contain the path specified and the words "private key"
        Assert.assertTrue(publicKeyFileException.getMessage().contains(publicKeyFile.toString()));
        Assert.assertTrue(publicKeyFileException.getMessage().contains("private key"));
    }

    public void canLoadGeneratedBytesKeyPair() throws Exception {
        final KeyPair keyPair = generateKeyPair();
        final byte[] serializedKey = serializePrivateKey(keyPair, null);

        Assert.assertTrue(new String(serializedKey, StandardCharsets.UTF_8).startsWith(RSA_HEADER));

        final KeyPair loadedKeyPair = KeyPairLoader.getKeyPair(serializedKey, null);

        compareKeyContents(keyPair, loadedKeyPair);
    }

    public void canLoadKeyPairFromFile() throws Exception {
        final KeyPair keyPair = generateKeyPair();
        final byte[] serializedKey = serializePrivateKey(keyPair, null);

        final File keyFile = Files.createTempFile("private-key", "").toFile();
        keyFile.deleteOnExit();
        final FileOutputStream fos = new FileOutputStream(keyFile);
        fos.write(serializedKey);

        final KeyPair loadedKeyPair = KeyPairLoader.getKeyPair(keyFile);

        compareKeyContents(keyPair, loadedKeyPair);
    }

    public void canLoadPasswordProtectedKeyBytes() throws Exception {
        final KeyPair keyPair = generateKeyPair();
        final String passphrase = UUID.randomUUID().toString();
        final byte[] serializedKey = serializePrivateKey(keyPair, passphrase);

        Assert.assertTrue(new String(serializedKey, StandardCharsets.UTF_8).startsWith(RSA_HEADER));

        final KeyPair loadedKeyPair = KeyPairLoader.getKeyPair(serializedKey, passphrase.toCharArray());

        compareKeyContents(keyPair, loadedKeyPair);
    }

    public void canLoadPasswordProtectedKeyFromFile() throws Exception {
        final KeyPair keyPair = generateKeyPair();
        final String passphrase = UUID.randomUUID().toString();
        final byte[] serializedKey = serializePrivateKey(keyPair, passphrase);

        Assert.assertTrue(new String(serializedKey, StandardCharsets.UTF_8).startsWith(RSA_HEADER));

        final File keyFile = Files.createTempFile("private-key-with-passphrase", "").toFile();
        keyFile.deleteOnExit();
        final FileOutputStream fos = new FileOutputStream(keyFile);
        fos.write(serializedKey);

        final KeyPair loadedKeyPair = KeyPairLoader.getKeyPair(keyFile, passphrase.toCharArray());

        compareKeyContents(keyPair, loadedKeyPair);
    }

    public void canLoadKeyPairUsingSpecifiedProvider() throws Exception {

        if (Security.getProvider(PROVIDER_PKCS11_NSS) == null) {
            throw new SkipException(PROVIDER_PKCS11_NSS + " provider is missing.");
        }

        for (final String keyId : SignerTestUtil.keys.keySet()) {
            final SignerTestUtil.TestKeyResource keyResource = SignerTestUtil.keys.get(keyId);
            final KeyPair bouncyKeyPair = loadTestKeyPair(keyResource.resourcePath, DesiredSecurityProvider.BC);
            final String bouncyAlgo = bouncyKeyPair.getPrivate().getAlgorithm().toUpperCase();
            String classAlgoName = bouncyAlgo;
            if (bouncyAlgo.equals("ECDSA")) {
                classAlgoName = "EC";
            }

            Assert.assertEquals(KeyFingerprinter.md5Fingerprint(bouncyKeyPair), keyResource.md5Fingerprint);
            Assert.assertTrue(bouncyKeyPair.getPrivate().getClass().getSimpleName().contains("BC" + classAlgoName + "Private"));
            Assert.assertTrue(bouncyKeyPair.getPublic().getClass().getSimpleName().contains("BC" + classAlgoName + "Public"));

            final String nssAlgo = bouncyKeyPair.getPrivate().getAlgorithm().toUpperCase();
            Assert.assertEquals(bouncyAlgo, nssAlgo);

            final KeyPair nssKeyPair = loadTestKeyPair(keyResource.resourcePath, DesiredSecurityProvider.NSS);
            Assert.assertEquals(KeyFingerprinter.md5Fingerprint(nssKeyPair), keyResource.md5Fingerprint);
            Assert.assertTrue(nssKeyPair.getPrivate().getClass().getSimpleName().contains("P11" + classAlgoName));
            Assert.assertTrue(nssKeyPair.getPublic().getClass().getSimpleName().contains("P11" + classAlgoName));

        }
    }

    public void willThrowWhenPkcs11IsRequestedButUnavailable() throws Exception {
        if (Security.getProvider(PROVIDER_PKCS11_NSS) != null) {
            throw new SkipException("PKCS11 provider is available, can't perform skip test");
        }

        final KeyPair keyPair = generateKeyPair();
        final byte[] serializedKey = serializePrivateKey(keyPair, null);

        Assert.assertTrue(new String(serializedKey, StandardCharsets.UTF_8).startsWith(RSA_HEADER));

        Assert.assertThrows(KeyLoadException.class, () ->
                KeyPairLoader.getKeyPair(new ByteArrayInputStream(serializedKey), null, DesiredSecurityProvider.NSS));
    }

    // TEST UTILITY METHODS

    private KeyPair generateKeyPair() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        return generator.generateKeyPair();
    }

    private byte[] serializePrivateKey(final KeyPair keyPair,
                                       final String passphrase) throws Exception {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final JcaPEMWriter jcaPEMWriter = new JcaPEMWriter(new OutputStreamWriter(baos,
                StandardCharsets.US_ASCII));

        if (passphrase != null) {
            final PEMEncryptor pemEncryptor = new JcePEMEncryptorBuilder("AES-128-CBC").build(passphrase.toCharArray());
            final JcaMiscPEMGenerator pemGenerator = new JcaMiscPEMGenerator(keyPair.getPrivate(), pemEncryptor);
            jcaPEMWriter.writeObject(pemGenerator);
        } else {
            jcaPEMWriter.writeObject(keyPair.getPrivate());
        }

        jcaPEMWriter.flush();
        jcaPEMWriter.close();
        return baos.toByteArray();
    }

    private void compareKeyContents(final KeyPair expectedKeyPair, final KeyPair actualKeyPair) {
        AssertJUnit.assertArrayEquals(
                expectedKeyPair.getPrivate().getEncoded(),
                actualKeyPair.getPrivate().getEncoded());
        AssertJUnit.assertArrayEquals(
                expectedKeyPair.getPublic().getEncoded(),
                actualKeyPair.getPublic().getEncoded());
    }

    private KeyPair loadTestKeyPair(final String resourcePath,
                                    final DesiredSecurityProvider provider) throws IOException {
        final KeyPair loadedKeyPair;

        try (final InputStream inputKey = CLASS_LOADER.getResourceAsStream(resourcePath)) {
            loadedKeyPair = KeyPairLoader.getKeyPair(inputKey, null, provider);
        }
        return loadedKeyPair;
    }
}
