# Kubernetes 工作重複提交問題分析報告

## 工作提交流程

根據代碼分析，目前系統的工作提交流程如下：

1. 用戶透過 `TaskController.submitTask()` 上傳 notebook 文件
2. `TaskSubmissionService.submit()` 處理上傳請求，創建 `TaskExecutionRecord`，設置狀態為 PENDING
3. 文件被送到安全掃描隊列 `LlmScannerConfig.ScanTask`
4. `AsyncSecurityScanService.scan()` 異步執行安全掃描
5. 掃描完成後，呼叫 `submitToQueue()` 將任務添加到 Redis 佇列
6. 有兩個排程器負責處理 Redis 佇列中的任務:
   - `ExternalQueueScheduler.submitPendingExternalTasks()`: 每 10 秒執行，將任務提交到 Kubernetes
   - `KubernetesJobMonitorService.submitTasks()`: 監控並提交任務到 Kubernetes

## 問題分析

### 1. 可能的競爭條件問題

`ExternalQueueScheduler` 和 `KubernetesJobMonitorService` 可能同時從 Redis 佇列中讀取相同的任務，各自嘗試將其提交到 Kubernetes。這會導致相同的任務被提交兩次，因為:

- 兩個排程器都會獨立獲取待處理任務 `queueService.getPendingTasks()`
- 兩者都會嘗試將任務提交到 Kubernetes
- 可能在一個排程器成功提交後，但在移除佇列任務前，另一個排程器也讀取了相同的任務

### 2. Redis 佇列任務移除的可靠性問題

當任務成功提交到 Kubernetes 後，系統應該從 Redis 佇列中移除該任務。但是從代碼中可以看到:

```java
// ExternalQueueScheduler.java
batchV1Api.createNamespacedJob(k8sNamespace, job, null, null, null, null);
log.info("Successfully submitted task {} to Kubernetes", submissionId);
rec.setStatus(TaskExecutionRecord.Status.SCHEDULED);
recordRepo.save(rec);
queueService.removeTask(submissionId); // 這一步如果失敗會怎樣？
```

如果 `queueService.removeTask()` 失敗，任務在佇列中仍然存在，會在下一次排程中被再次處理，導致重複提交問題。

### 3. 錯誤處理邏輯中的問題

```java
catch (io.kubernetes.client.openapi.ApiException apiEx) {
    log.error("Kubernetes API error when submitting task {}: code={}, body={}", 
            submissionId, apiEx.getCode(), apiEx.getResponseBody(), apiEx);
    // 處理衝突錯誤 (409) - 表示任務已存在
    if (apiEx.getCode() == 409) {
        log.info("Job {} already exists in Kubernetes, marking as SCHEDULED", submissionId);
        rec.setStatus(TaskExecutionRecord.Status.SCHEDULED);
        recordRepo.save(rec);
        queueService.removeTask(submissionId);
        return;
    }
    // 只在特定情況下將任務標記為失敗，例如資源不可用，否則保留在隊列中重試
    if (apiEx.getCode() == 404 || apiEx.getCode() == 403 || apiEx.getCode() == 400) {
        rec.setStatus(TaskExecutionRecord.Status.FAILED);
        rec.setEndTime(java.time.LocalDateTime.now());
        rec.setRejectionReason("Kubernetes API error: " + apiEx.getMessage());
        recordRepo.save(rec);
        queueService.removeTask(submissionId);
    }
}
```

這裡如果 API 錯誤不是 409、404、403 或 400，任務會保留在佇列中，下次排程會再次處理，可能導致重複提交。

## 解決方案

### 1. 優化 `QueueService.removeTask()` 方法

首先，我們需要確保從 Redis 佇列移除任務的操作是可靠的。

### 2. 使用分佈式鎖防止競爭條件

為了防止兩個排程器同時處理相同的任務，可以使用 Redis 的分佈式鎖機制。

### 3. 優化任務提交前的檢查

就像您之前已經添加的代碼一樣，在任務提交前先檢查 Kubernetes 中是否已存在相同的 Job，如果存在則直接標記為 SCHEDULED 並從佇列移除。

### 4. 合併排程器或明確職責分工

考慮合併 `ExternalQueueScheduler` 和 `KubernetesJobMonitorService` 的功能，或明確劃分它們的職責範圍，避免功能重疊。

### 5. 使用交易確保一致性

在任務提交和佇列清理過程中使用交易確保一致性，特別是在數據庫更新和 Redis 操作之間。