package com.cybershield.vault;

public class Vault {

    private int id;

    private byte[] salt;

    private String verificationData;

    private String createdAt;

    public Vault() {
    }

    public Vault(
            byte[] salt,
            String verificationData
    ) {

        this.salt = salt;
        this.verificationData = verificationData;

    }

    public int getId() {

        return id;

    }

    public void setId(int id) {

        this.id = id;

    }

    public byte[] getSalt() {

        return salt;

    }

    public void setSalt(byte[] salt) {

        this.salt = salt;

    }

    public String getVerificationData() {

        return verificationData;

    }

    public void setVerificationData(
            String verificationData
    ) {

        this.verificationData = verificationData;

    }

    public String getCreatedAt() {

        return createdAt;

    }

    public void setCreatedAt(
            String createdAt
    ) {

        this.createdAt = createdAt;

    }

}