package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/user")
public class UserController {
    @Autowired
    private UserService userService;

    @GetMapping("/info")
    public ResponseEntity<?> getUserInfo(Authentication auth) {
        String email = auth.getName();
        return userService.findByEmail(email)
                .map(user -> ResponseEntity.ok(
                        Map.of(
                                "userId", user.getUserId(),
                                "name", user.getName(),
                                "email", user.getEmail(),
                                "role", user.getRole()
                        )
                ))
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "User not found")));
    }
}