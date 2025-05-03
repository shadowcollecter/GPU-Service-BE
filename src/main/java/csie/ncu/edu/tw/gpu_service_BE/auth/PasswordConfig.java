package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.boot.CommandLineRunner;

@Configuration
public class PasswordConfig {
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CommandLineRunner printPasswordHash(PasswordEncoder encoder) {
        return args -> System.out.println("BCrypt hash for 'admin123': " + encoder.encode("admin123"));
    }
}