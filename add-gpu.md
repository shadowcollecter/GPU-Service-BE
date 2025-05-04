# 如何新增 GPU 類型

**日期**：2025-05-04

本指南針對運維／後端工程師，說明在系統中新增一種 GPU 類型的完整流程，包含 Job 模板、後端配置與管理介面三大步驟。

---

## 一、前提說明

1. 後端已實現動態讀取 `application.properties`（或 Helm `values.yaml`）中  
   `gpu.types[N].yaml` → 模板檔案名稱 的映射。  
2. 管理介面（Admin）提供了 `/api/v1/admin/gpus` 的 GET/POST/DELETE，讀寫 `gpu_type` 資料表。  
3. 系統使用 Redis 隊列存儲任務，並通過 `KubernetesJobMonitorService` 從隊列中提取任務並提交到 Kubernetes。
4. 任務依賴 `customize-yaml` 目錄中的 YAML 模板提交到 Kubernetes。

---

## 二、新增 Job Template

1. **複製現有模板**  
   ```bash
   cd customize-yaml
   cp gpu-requested-job-a40.yaml gpu-requested-job-v100.yaml
   ```
2. **編輯新模板**  
   - 修改 `metadata.labels` 和 `nodeSelector` 以適配新的 GPU 類型
   - 確保 annotations 區塊包含：
     ```yaml
     gpu-service-be.csie.ncu.edu.tw/gpu-type: "${GPU_TYPE}"
     ```  
   - 如有特殊資源請求，修改 `resources.requests.nvidia.com/...` 參數
   - 確保回調腳本正確設置（保持不變）:
     ```yaml
     command:
     - /bin/bash
     - -c
     - |
       set -euo pipefail
       # 上传原始 notebook
       aws --endpoint-url=$MINIO_ENDPOINT s3 cp input.ipynb \
         s3://$MINIO_BUCKET/submissions/$USER_ID/${TIMESTAMP}_original/notebook.ipynb
       papermill input.ipynb output.ipynb \
         --no-progress-bar --log-output
       # 上传执行结果
       aws --endpoint-url=$MINIO_ENDPOINT s3 cp output.ipynb \
         s3://$MINIO_BUCKET/submissions/$USER_ID/${TIMESTAMP}_results/result_notebook.ipynb
       curl -X POST "$CALLBACK_BASE_URL/$SUBMISSION_ID" \
            -H "Content-Type: application/json" \
            -d '{"submissionId":"'"$SUBMISSION_ID"'","status":"COMPLETED","resultPath":"s3://'$MINIO_BUCKET'/submissions/'"$USER_ID"'/'"${TIMESTAMP}"'_results/result_notebook.ipynb"}'
     ```

3. **注意回調機制**  
   模板中的回調腳本（curl 命令）會在任務完成後向後端報告狀態，確保 callback URL 指向正確的服務端點。這是任務完成後更新狀態的關鍵機制。

---

## 三、後端配置映射

1. **修改程式設定檔**  
   在 `src/main/resources/application.properties` 或 Helm `values.yaml` 裡，新增：
   - `src/main/resources/application.properties` (或對應 environment 檔)：
     ```properties
     # 例如新增 NVIDIA-V100
     gpu.types[2].type=NVIDIA-V100-16GB
     gpu.types[2].yaml=gpu-requested-job-v100.yaml
     gpu.types[2].queue=gpu-queue-v100

     # 同步將 queue 名稱和 Redis 鍵加入
     queue.names=cpu-queue,gpu-queue-a100,gpu-queue-a40,gpu-queue-v100
     queue.redisKeys.gpu-queue-v100=task_queue_v100
     
     # 設置 V100 的並發限制（可選）
     k8s.job.concurrent-limit.nvidia-v100-16gb=1
     ```

   - 若使用 Helm，修改 `charts/gpu-service-be/values.yaml`：
     ```yaml
     gpu:
       types:
       - type: NVIDIA-V100-16GB
         yaml: gpu-requested-job-v100.yaml
         queue: gpu-queue-v100

     queue:
       names:
         - cpu-queue
         - gpu-queue-a100
         - gpu-queue-a40
         - gpu-queue-v100    # 新增
       redisKeys:
         cpu-queue: task_queue_cpu
         gpu-queue-a100: task_queue_a100
         gpu-queue-a40: task_queue_a40
         gpu-queue-v100: task_queue_v100  # 新增
     ```

2. **確保 Redis 和任務監控配置正確**
   ```properties
   # Redis 隊列模式配置
   task.submit.mode=queue
   
   # K8s 任務監控和清理配置
   k8s.job.monitor.interval-ms=10000
   k8s.job.clean.completed-jobs-ttl-seconds=3600
   k8s.job.clean.failed-jobs=true
   ```

3. **重新啟動後端服務**  
   ```bash
   ./gradlew bootRun --args='--spring.profiles.active=dev'
   ```  
   或重新部署 Helm chart 並設定 `--set application.activeProfile=dev`。

---

## 四、管理界面新增 GPU 類型

1. 前往「管理後台 → GPU 類型管理」頁面。  
2. 按下【新增 GPU】，輸入類型名稱（**必須**與上面 `application.properties` 的 key 完全一致），如：
   ```
   NVIDIA-V100-16GB
   ```
3. 提交後，可於下拉清單或呼叫 API `GET /api/v1/admin/gpus` 查看新加項。
4. 確保將 `enabled` 設置為 `true`，使該 GPU 類型可在前端被選擇。

---

## 五、驗證

1. **提交任務**  
   - 在前端提交一個 `gpuRequired=true`、`gpuType=NVIDIA-V100-16GB` 的作業。  
2. **檢查 Redis 隊列**
   - 使用 Redis CLI 檢查隊列：`redis-cli -h <host> -p <port> -a <password> LRANGE task_queue_v100 0 -1`
   - 確認任務已被正確添加到隊列中
3. **檢查 Kubernetes**  
   - `kubectl get jobs -n gpu-service`  
   - 確認從隊列中提取的任務已被提交到 Kubernetes
   - 確認 Job 有正確的 annotation
4. **後端記錄**  
   - 檢查 `task_execution_record.gpu_type` 欄位已存入 `NVIDIA-V100-16GB`。
   - 任務完成後，確認 `status` 已更新為 `COMPLETED` 或 `FAILED`。

完成以上步驟，即可將新 GPU 類型無縫接入整體排程與管理平台。

## 六、任務處理流程說明

1. **任務提交**：用戶提交任務後，任務經過安全檢查，被添加到對應 GPU 類型的 Redis 隊列
2. **隊列監控**：`KubernetesJobMonitorService` 定期（默認 10 秒）監控 Kubernetes 中的任務狀態
3. **任務調度**：當 Kubernetes 中特定 GPU 類型的運行任務數低於並發限制時，從對應隊列提取新任務
4. **任務執行**：任務在 Kubernetes 中執行，完成後通過回調 API 更新狀態
5. **資源清理**：已完成或失敗的任務會在 TTL 過期後被自動清理

## 目前有的 GPU 類型

- NVIDIA-A100-80GB-PCIe
- NVIDIA-A40-48GB-PCIe
