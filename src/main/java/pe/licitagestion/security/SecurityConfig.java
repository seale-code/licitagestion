package pe.licitagestion.security;

import java.io.IOException;
import java.time.Instant;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import pe.licitagestion.persistence.Store;

@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder encoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    UserDetailsService users(Store store) {
        return email -> {
            var u = store.optional(
                "SELECT id, email, password_hash, role, enabled FROM app_user WHERE email=?",
                email.trim().toLowerCase()
            ).orElseThrow(() -> new UsernameNotFoundException("Credenciales inválidas"));

            return User.withUsername(u.get("email").toString())
                .password(u.get("password_hash").toString())
                .roles(u.get("role").toString())
                .disabled(!Boolean.TRUE.equals(u.get("enabled")))
                .build();
        };
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, @Value("${app.require-https}") boolean https, LoginRateLimiter limiter, ClientAddressResolver addressResolver) throws Exception {
        http.addFilterBefore(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                throws ServletException, IOException {
                if ("POST".equalsIgnoreCase(req.getMethod()) && "/login".equals(req.getRequestURI())) {
                    if (limiter.isBlocked(addressResolver.resolve(req.getRemoteAddr(), req.getHeader("X-Forwarded-For")))) {
                        res.sendRedirect("/login?blocked");
                        return;
                    }
                }
                chain.doFilter(req, res);
            }
        }, UsernamePasswordAuthenticationFilter.class)
        .authorizeHttpRequests(a -> a
            .requestMatchers("/login", "/login.css", "/actuator/health", "/actuator/health/**", "/error").permitAll()
            .anyRequest().authenticated()
        )
        .formLogin(f -> f
            .loginPage("/login")
            .successHandler((req, res, auth) -> {
                limiter.recordSuccess(addressResolver.resolve(req.getRemoteAddr(), req.getHeader("X-Forwarded-For")));
                req.getSession().setAttribute("auth_time", Instant.now());
                res.sendRedirect("/");
            })
            .failureHandler((req, res, ex) -> {
                limiter.recordFailure(addressResolver.resolve(req.getRemoteAddr(), req.getHeader("X-Forwarded-For")));
                res.sendRedirect("/login?error");
            })
        )
        .logout(l -> l.logoutUrl("/logout").logoutSuccessUrl("/login?logout"))
        .sessionManagement(s -> s.sessionFixation(f -> f.migrateSession()))
        .exceptionHandling(e -> e
            .defaultAuthenticationEntryPointFor((req, res, ex) -> {
                res.setStatus(401);
                res.setContentType("application/json");
                res.getWriter().write("{\"message\":\"Inicia sesión nuevamente\"}");
            }, req -> req.getRequestURI().startsWith("/api/"))
        )
        .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives(
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"
        )));

        if (https) {
            http.requiresChannel(c -> c.anyRequest().requiresSecure());
        }

        return http.build();
    }
}

