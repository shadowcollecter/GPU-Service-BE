package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.HashMap;
import java.util.Optional;

import csie.ncu.edu.tw.gpu_service_BE.auth.UserUsageSummary;
import csie.ncu.edu.tw.gpu_service_BE.auth.UserUsageSummaryRepository;
import csie.ncu.edu.tw.gpu_service_BE.auth.TaskExecutionRecordRepository;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private UserUsageSummaryRepository userUsageSummaryRepository;
    @Autowired
    private TaskExecutionRecordRepository taskExecutionRecordRepository;

    @GetMapping
    public ResponseEntity<?> listUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String query
    ) {
        Pageable pageable = PageRequest.of(page, size);
        Page<User> users;
        if (query != null && !query.isEmpty()) {
            users = userRepository.findByNameContainingIgnoreCaseOrEmailContainingIgnoreCase(query, query, pageable);
        } else {
            users = userRepository.findAll(pageable);
        }
        
        var userItems = users.getContent().stream().map(u -> {
            // Create a HashMap instead of using Map.of() to handle null values
            HashMap<String, Object> userMap = new HashMap<>();
            userMap.put("userId", u.getUserId());
            userMap.put("name", u.getName());
            userMap.put("email", u.getEmail());
            userMap.put("role", u.getRole());
            userMap.put("remainingTime", userUsageSummaryRepository.findById(u.getUserId())
                                    .map(UserUsageSummary::getRemainingTime).orElse(0L));
            userMap.put("totalTime", userUsageSummaryRepository.findById(u.getUserId())
                                    .map(UserUsageSummary::getTotalUsedTime).orElse(0L));
            userMap.put("taskCount", taskExecutionRecordRepository.countByUserId(u.getUserId()));
            userMap.put("createdAt", u.getCreatedAt().format(DateTimeFormatter.ISO_DATE_TIME));
            // Handle null lastLogin safely
            userMap.put("lastLogin", u.getLastLogin() != null ? 
                                  u.getLastLogin().format(DateTimeFormatter.ISO_DATE_TIME) : "");
            return userMap;
        }).collect(Collectors.toList());
        
        // Create a HashMap for the result to handle potential null values
        HashMap<String, Object> result = new HashMap<>();
        result.put("items", userItems);
        result.put("total", users.getTotalElements());
        
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{userId}")
    public ResponseEntity<?> getUserDetail(@PathVariable String userId) {
        return userRepository.findById(userId)
            .map(user -> {
                var usage = userUsageSummaryRepository.findById(userId).orElse(new UserUsageSummary());
                var tasks = taskExecutionRecordRepository
                    .findByUserIdOrderByStartTimeDesc(userId, PageRequest.of(0, 10))
                    .getContent()
                    .stream().map(r -> Map.of(
                        "taskId", r.getSubmissionId(),
                        "submitTime", r.getStartTime(),
                        "status", r.getStatus(),
                        "duration", r.getDuration()
                    )).collect(Collectors.toList());
                Map<String, Object> detail = new HashMap<>();
                detail.put("userId", user.getUserId());
                detail.put("name", user.getName());
                detail.put("email", user.getEmail());
                detail.put("role", user.getRole());
                detail.put("remainingTime", usage.getRemainingTime());
                detail.put("totalTime", usage.getTotalUsedTime());
                detail.put("createdAt", user.getCreatedAt().format(DateTimeFormatter.ISO_DATE_TIME));
                detail.put("lastLogin", user.getLastLogin()!=null ? user.getLastLogin().format(DateTimeFormatter.ISO_DATE_TIME) : null);
                detail.put("recentTasks", tasks);
                return ResponseEntity.ok(detail);
            })
            .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "User not found")));
    }

    @PostMapping
    public ResponseEntity<?> createUser(@RequestBody Map<String, String> body) {
        String userId = body.get("username");
        String name = body.get("name");
        String email = body.get("email");
        String rawPwd = body.get("password");
        String roleStr = body.get("role");
        
        // Check if we're updating an existing user
        Optional<User> existingUser = userRepository.findById(userId);
        if (existingUser.isPresent()) {
            return updateExistingUser(existingUser.get(), name, email, rawPwd, roleStr);
        }
        
        // Check if email already exists with different userId
        if (userRepository.findByEmail(email).isPresent()) {
            return ResponseEntity.status(409).body(Map.of("error", "Email already in use"));
        }
        
        User newUser = new User();
        newUser.setUserId(userId);
        newUser.setName(name);
        newUser.setEmail(email);
        newUser.setRole(User.Role.valueOf(roleStr.toUpperCase()));
        newUser.setHashedPassword(passwordEncoder.encode(rawPwd));
        LocalDateTime now = LocalDateTime.now();
        newUser.setCreatedAt(now);
        newUser.setLastLogin(now);
        userRepository.save(newUser);
        
        // init usage summary
        UserUsageSummary sum = new UserUsageSummary();
        sum.setUserId(userId);
        sum.setTotalUsedTime(0);
        sum.setRemainingTime(360000);
        sum.setTimePeriodStart(now);
        sum.setTimePeriodEnd(now.plusMonths(6));
        sum.setLastUpdated(now);
        userUsageSummaryRepository.save(sum);
        return ResponseEntity.ok(Map.of("userId", userId, "message", "User created"));
    }
    
    /**
     * Updates an existing user's information
     */
    private ResponseEntity<?> updateExistingUser(User user, String name, String email, 
                                               String rawPwd, String roleStr) {
        // Update basic information
        user.setName(name);
        
        // Only update email if it's different and not already taken by another user
        if (!user.getEmail().equals(email)) {
            Optional<User> existingEmail = userRepository.findByEmail(email);
            if (existingEmail.isPresent() && !existingEmail.get().getUserId().equals(user.getUserId())) {
                return ResponseEntity.status(409).body(Map.of("error", "Email already in use by another user"));
            }
            user.setEmail(email);
        }
        
        // Update role if provided
        if (roleStr != null && !roleStr.isEmpty()) {
            try {
                user.setRole(User.Role.valueOf(roleStr.toUpperCase()));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.status(400).body(Map.of("error", "Invalid role: " + roleStr));
            }
        }
        
        // Update password if provided
        if (rawPwd != null && !rawPwd.isEmpty()) {
            user.setHashedPassword(passwordEncoder.encode(rawPwd));
        }
        
        userRepository.save(user);
        return ResponseEntity.ok(Map.of("userId", user.getUserId(), "message", "User updated"));
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<?> deleteUser(@PathVariable String userId) {
        if (!userRepository.existsById(userId)) {
            return ResponseEntity.status(404).body(Map.of("error", "User not found"));
        }
        userRepository.deleteById(userId);
        userUsageSummaryRepository.deleteById(userId);
        return ResponseEntity.ok(Map.of("message", "用戶已刪除"));
    }
}