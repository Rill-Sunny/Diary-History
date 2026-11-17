package com.liubai.diary.auth;

import com.liubai.diary.journal.NotebookMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminInitializer implements ApplicationRunner {
    private final UserMapper users;
    private final NotebookMapper notebooks;
    private final PasswordEncoder encoder;
    private final String email;
    private final String password;
    private final String username;
    private final String nickname;

    public AdminInitializer(UserMapper users, NotebookMapper notebooks, PasswordEncoder encoder,
                            @Value("${app.admin.email:}") String email,
                            @Value("${app.admin.password:}") String password,
                            @Value("${app.admin.username:admin}") String username,
                            @Value("${app.admin.nickname:系统管理员}") String nickname) {
        this.users = users;
        this.notebooks = notebooks;
        this.encoder = encoder;
        this.email = email;
        this.password = password;
        this.username = username;
        this.nickname = nickname;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank()) return;
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
            throw new IllegalStateException("APP_ADMIN_EMAIL must be a valid email address");
        if (password.length()<10 || password.length()>72)
            throw new IllegalStateException("APP_ADMIN_PASSWORD must contain 10 to 72 characters");
        if (!username.matches("^[A-Za-z0-9_.-]{3,24}$"))
            throw new IllegalStateException("APP_ADMIN_USERNAME must contain 3 to 24 valid characters");
        AppUser existing = users.findByEmail(email.trim());
        if (existing != null) {
            if (!"ADMIN".equals(existing.getRole())) {
                throw new IllegalStateException("APP_ADMIN_EMAIL belongs to a non-admin account; refusing to change its role");
            }
            return;
        }
        if(users.findByUsername(username.trim())!=null)
            throw new IllegalStateException("APP_ADMIN_USERNAME belongs to another account");
        AppUser admin = new AppUser();
        admin.setEmail(email.trim());
        admin.setUsername(username.trim());
        admin.setNickname(nickname.trim());
        admin.setPasswordHash(encoder.encode(password));
        admin.setRole("ADMIN");
        admin.setEnabled(true);
        admin.setEmailVerified(true);
        admin.setTimeZone("Asia/Shanghai");
        users.insert(admin);
        notebooks.insertDefault(admin.getId());
    }
}
