package com.company.facelogin.models;

public class User {

    private long id;
    private String firstName;
    private String lastName;
    private byte[] faceEmbedding;
    private long createdAt;

    public User() {}

    public User(String firstName, String lastName) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.createdAt = System.currentTimeMillis();
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public byte[] getFaceEmbedding() { return faceEmbedding; }
    public void setFaceEmbedding(byte[] faceEmbedding) { this.faceEmbedding = faceEmbedding; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
