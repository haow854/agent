package com.javaee.common.security;

import com.javaee.common.constant.ErrorCodeEnum;
import com.javaee.common.exception.BusinessException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class RequestUserContext {

    public Long getRequiredUserId() {
        return getCurrentUserId()
                .orElseThrow(() -> new BusinessException(ErrorCodeEnum.UNAUTHORIZED));
    }

    public Optional<Long> getCurrentUserId() {
        Authentication authentication = currentAuthentication();
        if (authentication == null || authentication.getPrincipal() == null) {
            return Optional.empty();
        }
        return parseUserId(authentication.getPrincipal().toString());
    }

    public String getCurrentRole() {
        return authorities().stream().findFirst().orElse("user");
    }

    public Set<String> getCurrentPermissionGroups() {
        return Set.copyOf(authorities());
    }

    public boolean isAdmin() {
        return getCurrentPermissionGroups().stream()
                .anyMatch("admin"::equalsIgnoreCase);
    }

    private List<String> authorities() {
        Authentication authentication = currentAuthentication();
        if (authentication == null || authentication.getAuthorities() == null) {
            return List.of();
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority != null && !authority.isBlank())
                .map(authority -> authority
                        .replaceFirst("^ROLE_", "")
                        .replaceFirst("^GROUP_", ""))
                .collect(Collectors.toList());
    }

    private Authentication currentAuthentication() {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        return authentication;
    }

    private Optional<Long> parseUserId(String raw) {
        if (raw == null || raw.isBlank()
                || "anonymousUser".equalsIgnoreCase(raw)
                || "anonymous".equalsIgnoreCase(raw)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(raw.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
