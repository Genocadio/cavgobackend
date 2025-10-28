package com.gocavgo.ridehail.auth.dto;

public class AuthResponse {
    private String accessToken;
    private Long userId;
    private String role;
    private String firstName;
    private String lastName;
    private String phone;

    public AuthResponse() {}
    public AuthResponse(String accessToken, Long userId, String role, String firstName, String lastName, String phone) {
        this.accessToken = accessToken;
        this.userId = userId;
        this.role = role;
        this.firstName = firstName;
        this.lastName = lastName;
        this.phone = phone;
    }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
}


