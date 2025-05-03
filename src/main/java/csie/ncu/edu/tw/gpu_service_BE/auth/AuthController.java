package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/auth")
@CrossOrigin(origins = "*") // allow cross-origin for OAuth callbacks
public class AuthController {
    private final UserService userService;
    private final JwtUtil jwtUtil;
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private final UserRepository userRepository;
    @Value("${app.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    public AuthController(UserService userService,
                          JwtUtil jwtUtil,
                          UserRepository userRepository) {
        this.userService = userService;
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> loginJson(@RequestBody Map<String, String> body,
                                                         HttpServletRequest request) {
        String username = body.get("username");
        String password = body.get("password");
        Optional<User> opt = userService.findByEmail(username)
                                       .or(() -> userRepository.findById(username));
        if (opt.isPresent() && userService.checkPassword(opt.get(), password)) {
            User user = opt.get();
            userService.saveOrUpdateLoginUser(user.getUserId(), user.getName(), user.getEmail(), user.getRole());
            String accessToken = jwtUtil.generateAccessToken(user.getUserId(), user.getRole().name());
            return ResponseEntity.ok(Map.of("accessToken", accessToken));
        }
        return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
    }

    // Support form-urlencoded login for direct form submissions
    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, String>> loginForm(@RequestParam MultiValueMap<String, String> formParams,
                                                         HttpServletRequest request) throws IOException {
        Map<String, String> body = Map.of(
            "username", formParams.getFirst("username"),
            "password", formParams.getFirst("password")
        );
        return loginJson(body, request);
    }

    @GetMapping("/authorize")
    public void samlAuthorize(HttpServletResponse response) throws IOException {
        response.sendRedirect("/saml2/authenticate/ncu");
    }

    @GetMapping("/saml/callback")
    public ResponseEntity<Map<String, String>> samlCallback(@AuthenticationPrincipal Saml2AuthenticatedPrincipal principal) {
        String userId = principal.getName();
        String email = principal.getFirstAttribute("email");
        User user = userService.saveOrUpdateLoginUser(userId, userId, email, User.Role.USER);
        String accessToken = jwtUtil.generateAccessToken(user.getUserId(), user.getRole().name());
        return ResponseEntity.ok(Map.of("accessToken", accessToken));
    }

    /**
     * Clear the accessToken cookie to log out the user (stateless revocation)
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout() {
        // stateless logout: client should drop its JWT
        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }
}
