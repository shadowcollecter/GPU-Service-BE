package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.http.HttpStatus;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.security.config.Customizer;
import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Value;
import csie.ncu.edu.tw.gpu_service_BE.auth.UserRepository;
import csie.ncu.edu.tw.gpu_service_BE.auth.JwtUtil;
import csie.ncu.edu.tw.gpu_service_BE.auth.UserService;
import csie.ncu.edu.tw.gpu_service_BE.auth.User;
import csie.ncu.edu.tw.gpu_service_BE.auth.RedisTokenService;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import java.util.List;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@EnableAsync
class SecurityConfiguration {
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private UserService userService;
    @Autowired
    private RedisTokenService redisTokenService;
    @Value("${app.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    @Bean
    public UserDetailsService userDetailsService() {
        return username -> userRepository.findByEmail(username)
            .map(u -> org.springframework.security.core.userdetails.User.withUsername(u.getEmail())
                          .password(u.getHashedPassword())
                          .roles(u.getRole().name())
                          .build())
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService());
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    @Autowired
    @Lazy
    private JwtAuthFilter jwtAuthFilter;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowCredentials(true);
        config.setAllowedOrigins(List.of(frontendUrl));
        config.addAllowedHeader("*");
        config.addAllowedMethod("GET");
        config.addAllowedMethod("POST");
        config.addAllowedMethod("PUT");
        config.addAllowedMethod("DELETE");
        config.addAllowedMethod("PATCH");
        config.addAllowedMethod("OPTIONS");
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // register authentication provider
        http.authenticationProvider(authenticationProvider());
        http.cors(Customizer.withDefaults());
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        http.authorizeHttpRequests(auth -> auth
                // allow all auth-related endpoints and health
                .requestMatchers("/api/v1/auth/**", "/actuator/**", "/saml2/**", "/login/saml2/**").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated());
        // enable SAML2 login with custom success handler
        http.saml2Login(saml2 -> saml2.successHandler((request, response, authn) -> {
            Saml2AuthenticatedPrincipal principal = (Saml2AuthenticatedPrincipal) authn.getPrincipal();
            String userId = principal.getName();
            String email = principal.getFirstAttribute("email");
            User user = userService.saveOrUpdateLoginUser(userId, userId, email, User.Role.USER);
            String accessToken = jwtUtil.generateAccessToken(user.getUserId(), user.getRole().name());
            String refreshToken = jwtUtil.generateRefreshToken(user.getUserId());
            String refreshJti = jwtUtil.getJti(refreshToken);
            redisTokenService.storeRefreshToken(refreshJti, user.getUserId(), jwtUtil.getRefreshExpiration());
            response.sendRedirect(frontendUrl + "/login?accessToken=" + accessToken + "&refreshToken=" + refreshToken);
        }));
        // JWT filter
        http.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
