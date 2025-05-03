package csie.ncu.edu.tw.gpu_service_BE.auth;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.security.Key;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Component
public class JwtUtil {
    private final Key key;
    private final String issuer;
    private final String audience;
    private final long ACCESS_EXP = 60 * 60 * 1000;      // 1 hour
    private final long REFRESH_EXP = 7 * 24 * 60 * 60 * 1000; // 7 days

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.issuer}") String issuer,
                   @Value("${jwt.audience}") String audience) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.issuer = issuer;
        this.audience = audience;
    }

    private String buildToken(String subject, Map<String, Object> claims, long expMillis) {
        String jti = UUID.randomUUID().toString();
        JwtBuilder builder = Jwts.builder()
            .setSubject(subject)
            .setId(jti)
            .setIssuedAt(new Date())
            .setExpiration(new Date(System.currentTimeMillis() + expMillis))
            .setIssuer(issuer)
            .setAudience(audience)
            .signWith(key, SignatureAlgorithm.HS256);
        if (claims != null) builder.addClaims(claims);
        return builder.compact();
    }

    public String generateAccessToken(String userId, String role) {
        return buildToken(userId, Map.of("role", role), ACCESS_EXP);
    }

    public String generateRefreshToken(String userId) {
        return buildToken(userId, null, REFRESH_EXP);
    }

    public Claims parseClaims(String token) {
        return Jwts.parserBuilder()
                   .requireIssuer(issuer)
                   .requireAudience(audience)
                   .setSigningKey(key)
                   .build()
                   .parseClaimsJws(token)
                   .getBody();
    }

    public long getAccessExpiration() {
        return ACCESS_EXP;
    }

    public long getRefreshExpiration() {
        return REFRESH_EXP;
    }

    public String getJti(String token) {
        return parseClaims(token).getId();
    }

    public String getUserId(String token) {
        return parseClaims(token).getSubject();
    }

    public String getRole(String token) {
        return parseClaims(token).get("role", String.class);
    }
}
