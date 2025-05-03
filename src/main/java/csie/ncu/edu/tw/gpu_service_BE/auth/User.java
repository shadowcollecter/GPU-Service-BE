package csie.ncu.edu.tw.gpu_service_BE.auth;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "users")
public class User {
    @Id
    @Column(name = "user_id", length = 50)
    private String userId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    private Role role;

    @Column(name = "hashed_password", length = 255)
    private String hashedPassword;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_login")
    private LocalDateTime lastLogin;

    public enum Role {
        USER, ADMIN
    }
}
