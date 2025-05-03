package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;

@Service
public class TaskQueryService {

    @Autowired
    private TaskExecutionRecordRepository repository;

    public Page<TaskDto> findAllTasks(TaskFilter filter, Pageable pageable) {
        Specification<TaskExecutionRecord> spec = (root, query, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            if (filter.getUserId() != null) {
                preds.add(cb.equal(root.get("userId"), filter.getUserId()));
            }
            if (filter.getSubmissionId() != null) {
                preds.add(cb.equal(root.get("submissionId"), filter.getSubmissionId()));
            }
            if (filter.getStatus() != null) {
                preds.add(cb.equal(root.get("status"), filter.getStatus()));
            }
            if (filter.getStartDate() != null) {
                preds.add(cb.greaterThanOrEqualTo(root.get("startTime"), filter.getStartDate()));
            }
            if (filter.getEndDate() != null) {
                preds.add(cb.lessThanOrEqualTo(root.get("startTime"), filter.getEndDate()));
            }
            return cb.and(preds.toArray(new Predicate[0]));
        };
        return repository.findAll(spec, pageable)
                         .map(this::toDto);
    }

    public java.util.Optional<TaskDto> getTaskBySubmissionId(String submissionId) {
        return repository.findBySubmissionId(submissionId)
                         .map(this::toDto);
    }

    private TaskDto toDto(TaskExecutionRecord rec) {
        TaskDto dto = new TaskDto();
        dto.setRecordId(rec.getRecordId());
        dto.setSubmissionId(rec.getSubmissionId());
        dto.setUserId(rec.getUserId());
        dto.setStatus(rec.getStatus());
        dto.setResourceType(rec.getResourceType());
        dto.setVramSize(rec.getVramSize());
        dto.setCreatedAt(rec.getCreatedAt());
        dto.setDuration(rec.getDuration());
        dto.setRiskScore(rec.getRiskScore());
        dto.setRiskMessage(rec.getRiskMessage());
        return dto;
    }
}