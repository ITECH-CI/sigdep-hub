package ci.itechciv.sigdep.hub.ingestion.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Sécurité ingestion — auth par clé API opaque (v2.0, remplace Keycloak).
 *
 * L'agent sigdep-sync s'authentifie via {@code X-API-Key: <uuid>}, vérifié par
 * {@link ApiKeyAuthFilter} contre {@code auth.api_keys}. Les endpoints
 * {@code /api/v1/sync/**} exigent une clé valide.
 */
@Configuration
public class IngestionSecurityConfig {

    @Bean
    @Profile("!dev")
    public SecurityFilterChain ingestionFilterChain(HttpSecurity http,
                                                    ApiKeyAuthFilter apiKeyAuthFilter) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/api/v1/sync/**").authenticated()
                        .anyRequest().authenticated())
                // Clé absente/invalide → 401 (et non le 403 par défaut).
                .exceptionHandling(ex -> ex.authenticationEntryPoint(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * Dev profile: no auth at all on sync endpoints. Use only for local
     * development and manual testing — never in staging or production.
     */
    @Bean
    @Profile("dev")
    public SecurityFilterChain devFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
