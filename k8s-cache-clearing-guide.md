# 清除 Kubernetes 容器快取的方法

## 方法 1: 重新啟動 Deployment

最簡單的方法是重新啟動相關的 Deployment，這會重新建立 Pod 並清除所有容器快取：

```bash
# 重新啟動後端服務
kubectl rollout restart deployment gpu-service-be -n gpu-service

# 重新啟動前端服務
kubectl rollout restart deployment gpu-service-fe -n gpu-service
```

## 方法 2: 在 Pod 規格中設定 emptyDir 作為暫存目錄

在 `deploy.yaml` 中為應用程式添加 `emptyDir` 卷，作為暫存目錄。這些目錄在 Pod 刪除時會自動清除：

```yaml
# deploy.yaml 範例修改
spec:
  containers:
    - name: gpu-service-be
      # ...existing code...
      volumeMounts:
        - name: cache-volume
          mountPath: /app/cache  # 應用程式的快取路徑
  volumes:
    - name: cache-volume
      emptyDir: {}
```

## 方法 3: 使用 Init Container 清理快取

添加 Init Container 在應用程式容器啟動前清除指定目錄：

```yaml
# deploy.yaml 範例修改
spec:
  initContainers:
    - name: clear-cache
      image: busybox
      command: ["sh", "-c", "rm -rf /app/cache/* || true"]
      volumeMounts:
        - name: cache-volume
          mountPath: /app/cache
  containers:
    - name: gpu-service-be
      # ...existing code...
      volumeMounts:
        - name: cache-volume
          mountPath: /app/cache
  volumes:
    - name: cache-volume
      emptyDir: {}
```

## 方法 4: 使用 Kubernetes Job 清理特定快取

如果需要清理範圍更廣的快取（例如 Redis），您可以建立一個特定的 Job：

```yaml
# cache-clean-job.yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: cache-clean-job
  namespace: gpu-service
spec:
  template:
    spec:
      containers:
      - name: cache-cleaner
        image: redis:alpine
        command: ["redis-cli", "-h", "redis.gpu-service.svc.cluster.local", "FLUSHALL"]
      restartPolicy: Never
  backoffLimit: 2
```

## 方法 5: 添加 Pod 生命週期掛鉤 (Lifecycle Hooks)

使用 `preStop` 掛鉤在容器終止前清理快取：

```yaml
# deploy.yaml 範例修改
spec:
  containers:
    - name: gpu-service-be
      # ...existing code...
      lifecycle:
        preStop:
          exec:
            command: ["sh", "-c", "rm -rf /app/cache/* || true"]
```

## 實施建議

對於 Spring Boot 應用程式，最常見的快取清理策略是：

1. 部署新版本時，使用 `rollout restart` 命令重新啟動 Deployment
2. 對於持久性快取（如 Redis 或資料庫中的快取數據），創建一個專用的清理端點，然後透過 Job 或直接呼叫 API 清理

選擇適合您應用程式架構的方法，確保清理過程不會影響系統穩定性。