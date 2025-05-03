# 如何新增 GPU 類型

**日期**：2025-05-03

本指南針對運維／後端工程師，說明在系統中新增一種 GPU 類型的完整流程，包含 Job 模板、後端配置與管理介面三大步驟。

---

## 一、前提說明

1. 後端已實現動態讀取 `application.properties`（或 Helm `values.yaml`）中  
   `gpu.types[N].yaml` → 模板檔案名稱 的映射。  
2. 管理介面（Admin）提供了 `/api/v1/admin/gpus` 的 GET/POST/DELETE，讀寫 `gpu_type` 資料表。  
3. Kubernetes 集群中有對應的 Queue CRD（如 `gpu-queue-a100`、`gpu-queue-a40`），可根據註解調度。

---

## 二、新增 Job Template

1. **複製現有模板**  
   ```bash
   cd customize-yaml
   cp gpu-requested-job-a40.yaml gpu-requested-job-v100.yaml
   ```
2. **編輯新模板**  
   - 修改 `metadata.labels.runai/queue` → 你的新隊列名稱（例如 `gpu-v100-queue`）  
   - 在 annotations 區塊新增：  
     ```yaml
     gpu-service-be.csie.ncu.edu.tw/gpu-type: "${GPU_TYPE}"
     ```  
   - 在 container env 區塊新增：
     ```yaml
     - name: GPU_TYPE
       valueFrom:
         fieldRef:
           fieldPath: metadata.annotations['gpu-service-be.csie.ncu.edu.tw/gpu-type']
     ```
   - 如有特殊資源請求，修改 `resources.requests.nvidia.com/...` 參數。

3. **（可選）新增 Queue CRD**  
   若需要新隊列，於 `customize-yaml/` 加入 `gpu-queue-v100.yaml`：
   ```yaml
   apiVersion: scheduling.run.ai/v2
   kind: Queue
   metadata: 
     name: gpu-queue-v100
   spec:
     priority: 80
     resources:
       cpu:
         quota: 0
       gpu:
         quota: 1
       memory:
         quota: -1
   ```

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

     # 同步將 queue 名稱加入
     queue.names=${queue.names},gpu-queue-v100
     queue.redisKeys.gpu-queue-v100=task_queue_v100
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

2. **重新啟動後端服務**  
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

---

## 五、驗證

1. **提交任務**  
   - 在前端提交一個 `gpuRequired=true`、`gpuType=NVIDIA-V100-16GB` 的作業。  
2. **檢查 Kubernetes**  
   - `kubectl get jobs -n gpu-service`  
   - 確認新 Job 有 annotation：  
     ```
     gpu-service-be.csie.ncu.edu.tw/gpu-type=NVIDIA-V100-16GB
     ```  
   - 確認 Pod 使用了 `gpu-queue-v100` 或指定隊列。  
3. **後端記錄**  
   - 檢查 `task_execution_record.gpu_type` 欄位已存入 `NVIDIA-V100-16GB`。

完成以上步驟，即可將新 GPU 類型無縫接入整體排程與管理平台。


## 目前有的gpu類型

- NVIDIA-A100-80GB-PCIe
- NVIDIA-A40-48GB-PCIe
