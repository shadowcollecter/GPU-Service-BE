package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.util.Optional;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Service
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserUsageSummaryRepository userUsageSummaryRepository;

    @Autowired
    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder, UserUsageSummaryRepository userUsageSummaryRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.userUsageSummaryRepository = userUsageSummaryRepository;
    }

    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    public boolean checkPassword(User user, String rawPassword) {
        return passwordEncoder.matches(rawPassword, user.getHashedPassword());
    }

    public User saveOrUpdateLoginUser(String userId, String name, String email, User.Role role) {
        Optional<User> existing = userRepository.findById(userId);
        if (existing.isPresent()) {
            User user = existing.get();
            user.setLastLogin(LocalDateTime.now());
            user.setName(name != null ? name : user.getName());
            user.setEmail(email != null ? email : user.getEmail());
            user.setRole(role != null ? role : user.getRole());
            userRepository.save(user);
            // ensure summary exists for this user
            if (userUsageSummaryRepository.findById(userId).isEmpty()) {
                LocalDateTime now = LocalDateTime.now();
                LocalDateTime periodEnd = now.plus(6, ChronoUnit.MONTHS);
                UserUsageSummary summary = new UserUsageSummary();
                summary.setUserId(userId);
                summary.setTotalUsedTime(0);
                summary.setRemainingTime(360000);
                summary.setTimePeriodStart(now);
                summary.setTimePeriodEnd(periodEnd);
                summary.setLastUpdated(now);
                userUsageSummaryRepository.save(summary);
            }
            return user;
        } else {
            User user = new User();
            user.setUserId(userId);
            user.setName(name);
            user.setEmail(email);
            user.setRole(role);
            user.setCreatedAt(LocalDateTime.now());
            user.setLastLogin(LocalDateTime.now());
            userRepository.save(user);
            // 新用戶自動建立 UserUsageSummary
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime periodEnd = now.plus(6, ChronoUnit.MONTHS);
            UserUsageSummary summary = new UserUsageSummary();
            summary.setUserId(userId);
            summary.setTotalUsedTime(0);
            summary.setRemainingTime(360000); // 100小時
            summary.setTimePeriodStart(now);
            summary.setTimePeriodEnd(periodEnd);
            summary.setLastUpdated(now);
            userUsageSummaryRepository.save(summary);
            return user;
        }
    }
}
