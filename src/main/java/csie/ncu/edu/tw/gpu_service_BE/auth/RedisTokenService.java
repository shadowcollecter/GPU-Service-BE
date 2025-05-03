package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.TimeUnit;

@Service
public class RedisTokenService {
    @Autowired
    private StringRedisTemplate redisTemplate;

    public void storeRefreshToken(String jti, String userId, long expirationMillis) {
        redisTemplate.opsForValue().set("refresh:" + jti, userId, expirationMillis, TimeUnit.MILLISECONDS);
    }

    public boolean isRefreshTokenValid(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey("refresh:" + jti));
    }

    public void removeRefreshToken(String jti) {
        redisTemplate.delete("refresh:" + jti);
    }

    public void blacklistAccessToken(String jti, long expirationMillis) {
        redisTemplate.opsForValue().set("access:" + jti, "blacklisted", expirationMillis, TimeUnit.MILLISECONDS);
    }

    public boolean isAccessTokenBlacklisted(String jti) {
        return Boolean.TRUE.equals(redisTemplate.hasKey("access:" + jti));
    }
}
