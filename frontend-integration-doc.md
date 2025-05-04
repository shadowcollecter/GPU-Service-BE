# 前後端整合問題與解決方案文檔

## 問題概述

我們在 Kubernetes 環境中部署 GPU Service 應用時遇到了跨域資源共享 (CORS) 問題和 API 通信問題。具體表現為：

1. 前端應用通過 `curl 'http://localhost:8080/api/v1/auth/login'` 嘗試訪問 API，但這個請求在容器化環境中無法正常工作
2. 前端應用在 Kubernetes 環境中可能嘗試使用 localhost 作為 API 端點，而不是使用正確的服務地址
3. 後端的 CORS 配置過於限制，僅允許單一來源訪問 API

## 已實施的解決方案

### 1. 擴展後端 CORS 配置

我們修改了 `SecurityConfig.java` 中的 CORS 配置，現在允許多個來源並且添加了適當的響應頭：

```java
@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowCredentials(true);
    
    // 允許多個來源，包括前端 URL 和本地開發 URL
    config.setAllowedOrigins(Arrays.asList(
        frontendUrl,
        "https://jupyterhub.csie.ncu.edu.tw",
        "http://localhost:3000",
        "http://localhost:4173"
    ));
    
    // 允許所有常用請求頭和方法
    config.addAllowedHeader("*");
    config.addAllowedMethod("*");
    
    // 允許暴露標準響應頭
    config.addExposedHeader("Authorization");
    config.addExposedHeader("Content-Type");
    
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
}
```

### 2. 確保正確的環境變數設置

在 `deploy.yaml` 中，我們為前端容器設置了：
```yaml
env:
- name: API_URL
  value: "http://gpu-service-be.gpu-service.svc.cluster.local:8080"
```

為後端容器設置了：
```yaml
env:
- name: APP_FRONTEND_URL
  value: "https://jupyterhub.csie.ncu.edu.tw"
- name: SPRING_PROFILES_ACTIVE
  value: "k8s"
```

## 需要前端開發人員檢查的事項

請前端工程師協助確認以下幾點：

1. **API 端點使用**：確保前端代碼中使用環境變數 `API_URL` 而非硬編碼的地址來訪問 API。例如：
   ```javascript
   // 正確做法
   const apiUrl = process.env.API_URL || 'http://localhost:8080';
   fetch(`${apiUrl}/api/v1/auth/login`, {...})
   
   // 不要這樣做
   fetch('http://localhost:8080/api/v1/auth/login', {...})
   ```

2. **請求頭設置**：確保 API 請求中包含正確的 Content-Type 和 Accept 頭。例如：
   ```javascript
   fetch(`${apiUrl}/api/v1/auth/login`, {
     headers: {
       'Content-Type': 'application/json',
       'Accept': 'application/json'
     },
     // ...其他配置
   })
   ```

3. **驗證 Proxy 配置**：如果前端使用 webpack-dev-server 或其他開發服務器的代理功能，請檢查 proxy 配置是否與環境相匹配。

## 如何測試 API 通信

1. 在瀏覽器開發者工具的網絡選項卡中觀察實際發出的 API 請求 URL。
2. 使用以下命令檢查環境變數是否正確傳遞到容器：
   ```bash
   kubectl exec -n gpu-service $(kubectl get pods -n gpu-service -l app=gpu-service-fe -o jsonpath='{.items[0].metadata.name}') -- env | grep API_URL
   ```

3. 嘗試從集群內部測試 API：
   ```bash
   kubectl exec -it $(kubectl get pods -n gpu-service -l app=gpu-service-fe -o jsonpath='{.items[0].metadata.name}') -n gpu-service -- curl http://gpu-service-be.gpu-service.svc.cluster.local:8080/actuator/health
   ```

## 下一步工作

1. 前端團隊：檢查並更新前端代碼以正確使用環境變數
2. 後端團隊：監控 API 請求日誌以確認 CORS 配置正確工作
3. DevOps 團隊：確保所有環境變數在所有環境中一致設置

如有任何疑問或需要進一步協助，請隨時與後端團隊聯繫。