/*
 * Copyright (c) 2017, Joyent, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.joyent.http.signature;

import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

public abstract class SignerTest {
    private KeyPair testKeyPair;
    private String testKeyFingerprint;

    public abstract String getKeyCode();

    @DataProvider(name = "testData")
    public abstract Object[][] testData();


    @BeforeClass
    public void beforeClass() throws IOException, NoSuchAlgorithmException {
        this.testKeyPair = SignerTestUtil.testKeyPair(getKeyCode());
        this.testKeyFingerprint = SignerTestUtil.testKeyFingerprint(getKeyCode());
    }

    @Test(dataProvider = "testData")
    public void signHeader(String hash, String providerCode) {
        final Signer signer = new Signer.Builder(testKeyPair).hash(hash).providerCode(providerCode).build();
        final String now = signer.defaultSignDateAsString();
        final String authzHeader = signer.createAuthorizationHeader(
                "testy", testKeyFingerprint, testKeyPair, now);
        final boolean verified = signer.verifyAuthorizationHeader(
                testKeyPair, authzHeader, now);
        Assert.assertTrue(verified, "Unable to verify signed authorization header");
    }

    @Test(dataProvider = "testData")
    public void signData(String hash, String providerCode) {
        final Signer signer = new Signer.Builder(testKeyPair).hash(hash).providerCode(providerCode).build();
        final byte[] data = "Hello World".getBytes();
        final byte[] signedData = signer.sign(
                "testy", testKeyFingerprint, testKeyPair, data);
        final boolean verified = signer.verify(
                "testy", testKeyFingerprint, testKeyPair, data, signedData);

        Assert.assertTrue(verified, "Signature couldn't be verified");
    }

    protected Object[][] permuteParameters(String[] hashes, String[] providerCodes) {
        List<String[]> permutations = new ArrayList<>();
        for (int i=0; i< hashes.length; i++) {
            for (int j=0; j< providerCodes.length; j++) {
                permutations.add(new String[] {hashes[i], providerCodes[j]});
            }
        }
        String[][] ret = new String[permutations.size()][];
        for (int i = 0; i < permutations.size(); i++) {
            ret[i] = permutations.get(i);
        }
        return ret;
    }
}
