package com.repairshop.saas.masterdata.config;

import com.repairshop.saas.masterdata.security.JwtAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Master-data serves public reference data (brands / models / categories) that
 * the apps read WITHOUT a token, so those stay permitAll. The ONE exception is
 * {@code /media/**}: it signs uploads with the shop's Cloudinary secret, so an
 * unauthenticated upload lets anyone host arbitrary files on the shop's paid
 * account. That path now requires a valid Bearer JWT (validated by JwtAuthFilter
 * against the shared app.jwt.secret).
 *
 * NOTE before deploy: confirm EVERY media uploader (customer / shop / EMPLOYEE /
 * ADMIN apps) sends `Authorization: Bearer <token>` on /media/upload — otherwise
 * that client's uploads will start returning 401/403.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        System.out.println("===== master-data-service SecurityConfig: /media/** REQUIRES AUTH; reference-data public (build v2026-07-30) =====");
        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .anonymous(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Uploads are authenticated; everything else (catalog reads,
                        // /media/ping health) stays public.
                        .requestMatchers("/media/upload").authenticated()
                        // S3-backed model images. Same reasoning as /media/upload —
                        // these write objects into the paid media.ggfix.in bucket and
                        // create catalogue rows, so they must never be anonymous.
                        .requestMatchers("/master/models/with-image").authenticated()
                        .requestMatchers("/master/models/*/image").authenticated()
                        .requestMatchers("/master/models/media-path/preview").authenticated()
                        .requestMatchers("/master/device-categories/*/image").authenticated()
                        .requestMatchers("/master/brands/*/image").authenticated()
                        // Bulk logo upload: platform admins only (admin portal token role).
                        .requestMatchers("/master/brands/bulk-logo/**").hasAnyRole("SUPER_ADMIN", "ADMIN")
                        .requestMatchers("/master/banners/*/image").authenticated()
                        .requestMatchers("/master/model-compatibility/*/image").authenticated()
                        // Every call is a paid Google Cloud Vision request.
                        .requestMatchers("/master/device-identify").authenticated()
                        .anyRequest().permitAll())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
