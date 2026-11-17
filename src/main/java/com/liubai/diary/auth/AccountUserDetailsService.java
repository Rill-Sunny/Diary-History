package com.liubai.diary.auth;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AccountUserDetailsService implements UserDetailsService {
    private final UserMapper users;

    public AccountUserDetailsService(UserMapper users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String identity) throws UsernameNotFoundException {
        AppUser user = users.findByLogin(identity.trim().toLowerCase());
        if (user == null || !user.isEnabled()) {
            throw new UsernameNotFoundException("账号或密码不正确。");
        }
        return user;
    }
}
