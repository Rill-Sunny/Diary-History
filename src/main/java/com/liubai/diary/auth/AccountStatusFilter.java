package com.liubai.diary.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class AccountStatusFilter extends OncePerRequestFilter {
    private final UserMapper users;

    public AccountStatusFilter(UserMapper users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof AppUser principal) {
            AppUser current = users.findById(principal.getId());
            boolean deactivated = current == null || !current.isEnabled();
            if (deactivated || current.getAuthVersion() != principal.getAuthVersion()) {
                SecurityContextHolder.clearContext();
                var session = request.getSession(false);
                if (session != null) session.invalidate();
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(deactivated
                        ? "{\"code\":\"account_deactivated\",\"message\":\"账号已停用。\"}"
                        : "{\"code\":\"session_revoked\",\"message\":\"账号状态已变化，请重新登录。\"}");
                return;
            }
            var requestContext = SecurityContextHolder.createEmptyContext();
            requestContext.setAuthentication(
                    org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
                            current, null, current.getAuthorities()));
            SecurityContextHolder.setContext(requestContext);
        }
        chain.doFilter(request, response);
    }
}
