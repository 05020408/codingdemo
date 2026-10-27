package cn.review.auth;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public record StudentPrincipal(UserRow user) implements UserDetails {
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return List.of(new SimpleGrantedAuthority("ROLE_STUDENT")); }
    @Override public String getPassword() { return user.passwordHash(); }
    @Override public String getUsername() { return user.usernameNorm(); }
    @Override public boolean isEnabled() { return "ACTIVE".equals(user.status()); }
    public String id() { return user.id(); }
}
