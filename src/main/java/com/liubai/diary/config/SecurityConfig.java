package com.liubai.diary.config;

import com.liubai.diary.auth.AccountStatusFilter;
import com.liubai.diary.auth.AccountUserDetailsService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    FilterRegistrationBean<AccountStatusFilter> disableContainerRegistration(AccountStatusFilter filter) {
        FilterRegistrationBean<AccountStatusFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            AccountUserDetailsService users,
                                            AccountStatusFilter statusFilter,
                                            @Qualifier("corsConfigurationSource") CorsConfigurationSource cors,
                                            SecurityContextRepository contextRepository) throws Exception {
        http
                .cors(config -> config.configurationSource(cors))
                .csrf(config -> config.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()))
                .securityContext(config -> config.securityContextRepository(contextRepository))
                .sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .userDetailsService(users)
                .exceptionHandling(config -> config.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(config -> config
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/sw.js", "/manifest.webmanifest",
                                "/assets/**", "/api/health", "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/verify-email",
                                "/api/auth/resend-verification", "/api/auth/verify-email-change",
                                "/api/auth/forgot-password", "/api/auth/reset-password").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .anyRequest().authenticated())
                .formLogin(config -> config.disable())
                .httpBasic(config -> config.disable())
                .logout(config -> config.disable())
                .addFilterBefore(statusFilter, org.springframework.security.web.access.intercept.AuthorizationFilter.class);
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(Environment environment) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(environment.getProperty("app.base-url", "http://localhost:5173")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
