package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.annotation.Value;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Service
public class UsageService {
    private final UserUsageSummaryRepository userUsageSummaryRepository;
    private final TimeAdjustmentLogRepository timeAdjustmentLogRepository;
    private final TaskExecutionRecordRepository taskExecutionRecordRepository;

    @Value("${usage.default-remaining-time-sec:360000}")
    private long defaultRemainingTimeSec;

    @Autowired
    public UsageService(UserUsageSummaryRepository userUsageSummaryRepository, TimeAdjustmentLogRepository timeAdjustmentLogRepository, TaskExecutionRecordRepository taskExecutionRecordRepository) {
        this.userUsageSummaryRepository = userUsageSummaryRepository;
        this.timeAdjustmentLogRepository = timeAdjustmentLogRepository;
        this.taskExecutionRecordRepository = taskExecutionRecordRepository;
    }

    public Optional<UserUsageSummary> getCurrentUsage(String userId) {
        return userUsageSummaryRepository.findById(userId);
    }

    @Transactional
    public void adjustUserTime(String userId, String adminId, long amount, String reason) {
        UserUsageSummary summary = userUsageSummaryRepository.findById(userId).orElseThrow();
        summary.setRemainingTime(summary.getRemainingTime() + amount);
        summary.setLastUpdated(LocalDateTime.now());
        userUsageSummaryRepository.save(summary);
        TimeAdjustmentLog log = new TimeAdjustmentLog();
        log.setUserId(userId);
        log.setAdminId(adminId);
        log.setAdjustmentAmount(amount);
        log.setAdjustmentReason(reason);
        log.setAdjustedAt(LocalDateTime.now());
        timeAdjustmentLogRepository.save(log);
    }

    /**
     * Checks if the user has remaining time to execute a task.
     */
    public boolean hasRemainingTime(String userId) {
        return userUsageSummaryRepository.findById(userId)
            .map(summary -> summary.getRemainingTime() > 0)
            .orElse(true);
    }

    @Scheduled(cron = "0 0 3 * * *") // 每天凌晨3點執行
    @Transactional
    public void cleanOldData() {
        LocalDateTime sixMonthsAgo = LocalDateTime.now().minusMonths(6);
        LocalDateTime oneYearAgo = LocalDateTime.now().minusYears(1);
        // 清理 TaskExecutionRecord
        taskExecutionRecordRepository.deleteByEndTimeBefore(sixMonthsAgo);
        // 清理 TimeAdjustmentLog
        timeAdjustmentLogRepository.deleteByAdjustedAtBefore(oneYearAgo);
        // 可選：將刪除前的資料備份到冷存儲
    }

    /**
     * 每年 6 月 30 日及 12 月 31 日凌晨 2 點，將所有使用者時數重置為半年初始值
     */
    @Scheduled(cron = "0 0 2 30 6 *")
    @Transactional
    public void resetUsageMidYear() {
        resetAllUsage();
    }

    @Scheduled(cron = "0 0 2 31 12 *")
    @Transactional
    public void resetUsageYearEnd() {
        resetAllUsage();
    }

    private void resetAllUsage() {
        LocalDateTime now = LocalDateTime.now();
        List<UserUsageSummary> all = userUsageSummaryRepository.findAll();
        for (UserUsageSummary summary : all) {
            summary.setTotalUsedTime(0);
            summary.setRemainingTime(defaultRemainingTimeSec);
            summary.setLastUpdated(now);
            userUsageSummaryRepository.save(summary);
        }
    }
}
