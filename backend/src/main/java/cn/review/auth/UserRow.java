package cn.review.auth;

public record UserRow(String id, String username, String usernameNorm, String passwordHash, String displayName, String status) {
    public UserView view() { return new UserView(id, username, displayName); }
    public record UserView(String id, String username, String displayName) {}
}
