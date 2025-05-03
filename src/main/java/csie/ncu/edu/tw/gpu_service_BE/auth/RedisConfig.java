package csie.ncu.edu.tw.gpu_service_BE.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@Configuration
public class RedisConfig {
    @Bean
    public RedisTemplate<String, TaskInfo> taskInfoRedisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, TaskInfo> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        // configure Jackson with JavaTimeModule to handle Instant
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // use Jackson2JsonRedisSerializer with explicit TaskInfo type; attach custom mapper
        Jackson2JsonRedisSerializer<TaskInfo> serializer = new Jackson2JsonRedisSerializer<>(TaskInfo.class);
        serializer.setObjectMapper(mapper);
        // set key and value serializers
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        template.setDefaultSerializer(serializer);
        return template;
    }
}
