package com.liubai.diary.auth;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

public class AppUser implements UserDetails {
    private Long id;
    private String email;
    private String username;
    private String nickname;
    private String passwordHash;
    private String role;
    private boolean enabled;
    private boolean emailVerified;
    private String timeZone;
    private String theme;
    private String accent;
    private int authVersion;
    private boolean hasAvatar;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    @Override public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    @Override public String getPassword() { return passwordHash; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    @Override public boolean isEnabled() { return enabled && emailVerified; }
    public boolean getEnabled() { return enabled; }
    public boolean isAccountEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isEmailVerified() { return emailVerified; }
    public void setEmailVerified(boolean emailVerified) { this.emailVerified = emailVerified; }
    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String timeZone) { this.timeZone = timeZone; }
    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }
    public String getAccent() { return accent; }
    public void setAccent(String accent) { this.accent = accent; }
    public int getAuthVersion() { return authVersion; }
    public void setAuthVersion(int authVersion) { this.authVersion = authVersion; }
    public boolean isHasAvatar() { return hasAvatar; }
    public void setHasAvatar(boolean hasAvatar) { this.hasAvatar = hasAvatar; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return enabled; }
    @Override public boolean isCredentialsNonExpired() { return true; }
}
