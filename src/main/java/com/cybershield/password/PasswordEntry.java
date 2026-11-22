package com.cybershield.password;

public class PasswordEntry {

    private int id;
    private int userId;
    // The vault whose key encrypted this entry. 0 when not set yet.
    private int vaultId;
    private String website;
    private String username;
    private String password;
    private String notes;
    private String updatedAt;

    public PasswordEntry() {
    }

    public PasswordEntry(int userId,
                         String website,
                         String username,
                         String password,
                         String notes) {

        this.userId = userId;
        this.website = website;
        this.username = username;
        this.password = password;
        this.notes = notes;

    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getUserId() {
        return userId;
    }

    public int getVaultId() {
        return vaultId;
    }

    public void setVaultId(int vaultId) {
        this.vaultId = vaultId;
    }

    public void setUserId(int userId) {
        this.userId = userId;
    }

    public String getWebsite() {
        return website;
    }

    public void setWebsite(String website) {
        this.website = website;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    /** Last change time as stored by SQLite (UTC, "yyyy-MM-dd HH:mm:ss"). */
    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }
    

}