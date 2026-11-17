package com.liubai.diary.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.liubai.diary.config.ApiException;
import com.liubai.diary.journal.NotebookMapper;
import com.liubai.diary.journal.AccountCleanupService;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/auth")
@Validated
public class AuthController {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String VERIFY_EMAIL = "VERIFY_EMAIL";
    private static final String CHANGE_EMAIL = "CHANGE_EMAIL";
    private static final String RESET_PASSWORD = "RESET_PASSWORD";
    private final UserMapper users;
    private final EmailTokenMapper tokens;
    private final NotebookMapper notebooks;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contextRepository;
    private final EmailService emailService;
    private final AccountCleanupService accountCleanup;
    private final Clock clock;
    private final AuthMailLimiter mailLimiter;

    public AuthController(UserMapper users,
                          EmailTokenMapper tokens,
                          NotebookMapper notebooks,
                          PasswordEncoder passwordEncoder,
                          AuthenticationManager authenticationManager,
                          SecurityContextRepository contextRepository,
                          EmailService emailService,
                          AccountCleanupService accountCleanup,
                          Clock clock,
                          AuthMailLimiter mailLimiter) {
        this.users = users;
        this.tokens = tokens;
        this.notebooks = notebooks;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.contextRepository = contextRepository;
        this.emailService = emailService;
        this.accountCleanup = accountCleanup;
        this.clock = clock;
        this.mailLimiter = mailLimiter;
    }

    @GetMapping("/csrf")
    public Map<String, String> csrf(HttpServletRequest request) {
        Object attribute = request.getAttribute(CsrfToken.class.getName());
        if (attribute == null) attribute = request.getAttribute("_csrf");
        if (!(attribute instanceof CsrfToken token)) {
            throw ApiException.unavailable("CSRF token is not available");
        }
        return Map.of("token", token.getToken());
    }

    @GetMapping("/me")
    public UserResponse me(@org.springframework.security.core.annotation.AuthenticationPrincipal AppUser user) {
        return response(user);
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, String> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest servletRequest) {
        if (!emailService.isConfigured()) {
            throw ApiException.unavailable("SMTP 尚未配置，不能验证邮箱；账号未创建。");
        }
        limitMail(servletRequest);
        validateNewPassword(request.password());
        String email = request.email().trim();
        String username = request.username().trim();
        if (users.findByEmail(email) != null || users.findByUsername(username) != null) {
            throw ApiException.conflict("邮箱或用户名已被使用。");
        }
        AppUser user = new AppUser();
        user.setEmail(email);
        user.setUsername(username);
        user.setNickname(request.nickname().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole("USER");
        user.setEnabled(true);
        user.setEmailVerified(false);
        user.setTimeZone("Asia/Shanghai");
        try {
            users.insert(user);
        } catch (DuplicateKeyException exception) {
            throw ApiException.conflict("邮箱或用户名已被使用。");
        }
        notebooks.insertDefault(user.getId());
        emailService.sendVerification(email, issueToken(user.getId(), VERIFY_EMAIL, 30, null));
        return Map.of("message", "账号已建立，请查收验证邮件。邮件尚未验证前不能登录。");
    }

    @PostMapping("/verify-email")
    @Transactional
    public Map<String, String> verifyEmail(@Valid @RequestBody TokenRequest request) {
        EmailTokenMapper.TokenRow token = validToken(request.token(), VERIFY_EMAIL);
        AppUser user = users.lockById(token.userId());
        if (user == null || !user.getEnabled() || user.isEmailVerified()
                || tokens.markUsed(token.id()) != 1 || users.verifyEmail(token.userId()) != 1) {
            throw ApiException.conflict("验证链接已使用或已失效，请重新申请。");
        }
        return Map.of("message", "邮箱验证成功，现在可以登录。");
    }

    @PostMapping("/resend-verification")
    @Transactional
    public Map<String, String> resendVerification(@Valid @RequestBody EmailRequest request, HttpServletRequest servletRequest) {
        if (!emailService.isConfigured()) throw ApiException.unavailable("SMTP 尚未配置，验证邮件未发送。");
        limitMail(servletRequest);
        AppUser user = users.findByEmail(request.email());
        if (user != null) {
            user = users.lockById(user.getId());
            if (user != null && user.getEnabled() && !user.isEmailVerified() && !tokens.issuedRecently(user.getId(), VERIFY_EMAIL)) {
                emailService.sendVerification(user.getEmail(), issueToken(user.getId(), VERIFY_EMAIL, 30, null));
            }
        }
        return Map.of("message", "如果该邮箱对应尚未验证的账号，验证说明已经发送。");
    }

    @PostMapping("/login")
    public UserResponse login(@Valid @RequestBody LoginRequest request,
                              HttpServletRequest servletRequest,
                              HttpServletResponse servletResponse) {
        Authentication authentication;
        try {
            if (!fitsBcrypt(request.password())) throw new org.springframework.security.authentication.BadCredentialsException("Invalid password");
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.identity().trim(), request.password()));
        } catch (org.springframework.security.core.AuthenticationException exception) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "邮箱或用户名、密码不正确，或邮箱尚未验证。");
        }
        new ChangeSessionIdAuthenticationStrategy().onAuthentication(authentication, servletRequest, servletResponse);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, servletRequest, servletResponse);
        return response((AppUser) authentication.getPrincipal());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) session.invalidate();
    }

    @PostMapping("/forgot-password")
    @Transactional
    public Map<String, String> forgotPassword(@Valid @RequestBody EmailRequest request, HttpServletRequest servletRequest) {
        if (!emailService.isConfigured()) throw ApiException.unavailable("SMTP 尚未配置，密码重置邮件未发送。");
        limitMail(servletRequest);
        AppUser user = users.findByEmail(request.email().trim());
        if (user != null) {
            user = users.lockById(user.getId());
            if (user != null && user.isEnabled() && user.getEmail().equalsIgnoreCase(request.email())
                    && !tokens.issuedRecently(user.getId(), RESET_PASSWORD)) {
                emailService.sendPasswordReset(user.getEmail(), issueToken(user.getId(), RESET_PASSWORD, 60, null));
            }
        }
        return Map.of("message", "如果该邮箱对应有效账号，重置说明已经发送。");
    }

    @PostMapping("/reset-password")
    @Transactional
    public Map<String, String> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        validateNewPassword(request.password());
        EmailTokenMapper.TokenRow token = validToken(request.token(), RESET_PASSWORD);
        AppUser user = users.lockById(token.userId());
        if (user == null || !user.isEnabled()) throw ApiException.notFound();
        if (tokens.markUsed(token.id()) != 1) throw ApiException.conflict("重置链接已被使用。");
        changePassword(user, request.password());
        return Map.of("message", "密码已更新，其他登录会话已失效。");
    }

    @PostMapping("/change-password")
    @Transactional
    public Map<String, String> changePassword(@org.springframework.security.core.annotation.AuthenticationPrincipal AppUser principal,
                                              @Valid @RequestBody ChangePasswordRequest request) {
        AppUser user = requirePassword(principal.getId(), request.currentPassword());
        validateNewPassword(request.newPassword());
        changePassword(user, request.newPassword());
        return Map.of("message", "密码已更新，请用新密码重新登录。");
    }

    @PostMapping("/change-email")
    @Transactional
    public Map<String, String> requestEmailChange(@org.springframework.security.core.annotation.AuthenticationPrincipal AppUser principal,
                                                 @Valid @RequestBody ChangeEmailRequest request,
                                                 HttpServletRequest servletRequest) {
        if (!emailService.isConfigured()) throw ApiException.unavailable("SMTP 尚未配置，确认邮件未发送。");
        limitMail(servletRequest);
        AppUser user = requirePassword(principal.getId(), request.password());
        String email = request.email().trim();
        if (users.findByEmail(email) != null) throw ApiException.conflict("此邮箱已被使用。");
        if (tokens.issuedRecently(user.getId(), CHANGE_EMAIL)) throw tooManyMailRequests();
        emailService.sendEmailChange(email, issueToken(user.getId(), CHANGE_EMAIL, 30, email));
        return Map.of("message", "确认邮件已发送至新邮箱。确认前仍可使用原邮箱登录。");
    }

    @PostMapping("/verify-email-change")
    @Transactional
    public Map<String, String> verifyEmailChange(@Valid @RequestBody TokenRequest request) {
        EmailTokenMapper.TokenRow token = validToken(request.token(), CHANGE_EMAIL);
        AppUser user = users.lockById(token.userId());
        if (user == null || !user.isEnabled() || tokens.markUsed(token.id()) != 1)
            throw ApiException.badRequest("确认链接已失效，请重新申请。");
        try {
            users.updateEmail(user.getId(), token.targetEmail());
        } catch (DuplicateKeyException exception) {
            throw ApiException.conflict("此邮箱已被使用，请改用其他邮箱。");
        }
        tokens.invalidate(user.getId(), RESET_PASSWORD);
        return Map.of("message", "新邮箱已确认，现在可以使用新邮箱或用户名登录。");
    }

    @PostMapping("/deactivate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deactivate(@org.springframework.security.core.annotation.AuthenticationPrincipal AppUser principal,
                           @Valid @RequestBody DeactivateRequest request) {
        AppUser user = requirePassword(principal.getId(), request.password());
        accountCleanup.deleteAccount(user.getId());
    }

    private AppUser requirePassword(long userId, String password) {
        AppUser user = users.lockById(userId);
        if (user == null || !user.isEnabled() || !fitsBcrypt(password) || !passwordEncoder.matches(password, user.getPassword())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "password_required", "当前密码不正确。");
        }
        return user;
    }

    private void changePassword(AppUser user, String password) {
        users.updatePassword(user.getId(), passwordEncoder.encode(password));
        tokens.invalidate(user.getId(), RESET_PASSWORD);
        tokens.invalidate(user.getId(), CHANGE_EMAIL);
    }

    private void validateNewPassword(String password) {
        if (!fitsBcrypt(password))
            throw ApiException.badRequest("密码的 UTF-8 长度不能超过 72 字节。");
    }

    private boolean fitsBcrypt(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    private void limitMail(HttpServletRequest request) {
        if (!mailLimiter.acquire(hash(request.getRemoteAddr()))) throw tooManyMailRequests();
    }

    private ApiException tooManyMailRequests() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "mail_rate_limit", "邮件请求过于频繁，请稍后重试。");
    }

    private String issueToken(long userId, String purpose, int minutes, String targetEmail) {
        tokens.invalidate(userId, purpose);
        String token = newToken();
        tokens.create(userId, purpose, hash(token), clock.instant().plus(Duration.ofMinutes(minutes)), targetEmail);
        return token;
    }

    private EmailTokenMapper.TokenRow validToken(String raw, String purpose) {
        EmailTokenMapper.TokenRow token = tokens.findValid(hash(raw), purpose);
        if (token == null) throw ApiException.badRequest("链接已过期、已使用或无效。");
        return token;
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static UserResponse response(AppUser user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getUsername(), user.getNickname(),
                user.getRole(), user.getTimeZone(), user.getTheme(), user.getAccent(), user.isHasAvatar());
    }

    public record UserResponse(long id, String email, String username, String nickname, String role,
                               String timeZone, String theme, String accent, boolean hasAvatar) {}
    public record LoginRequest(@NotBlank @Size(max = 254) String identity, @NotBlank @Size(max = 72) String password) {}
    public record RegisterRequest(
            @Email @NotBlank @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_.-]{3,24}$", message = "用户名需为 3 到 24 位字母、数字、点、下划线或连字符。") String username,
            @NotBlank @Size(max = 40) String nickname,
            @NotBlank @Size(min = 10, max = 72, message = "密码长度需为 10 到 72 个字符。") String password) {}
    public record TokenRequest(@NotBlank @Size(max = 128) String token) {}
    public record EmailRequest(@Email @NotBlank @Size(max = 254) String email) {}
    public record ResetPasswordRequest(@NotBlank String token, @NotBlank @Size(min = 10, max = 72) String password) {}
    public record DeactivateRequest(@NotBlank @Size(max = 72) String password) {}
    public record ChangePasswordRequest(@NotBlank @Size(max = 72) String currentPassword,
                                        @NotBlank @Size(min = 10, max = 72) String newPassword) {}
    public record ChangeEmailRequest(@NotBlank @Size(max = 72) String password,
                                     @Email @NotBlank @Size(max = 254) String email) {}
}
