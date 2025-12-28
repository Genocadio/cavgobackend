package com.gocavgo.Navigation.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gocavgo.Navigation.model.NavigationState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class RedisStateStore {
    private static final String REDIS_KEY_PREFIX = "navigation:state:";
    private static final long TTL_SECONDS = 86400; // 24 hours
    
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    
    /**
     * Save navigation state to Redis
     */
    public void saveNavigationState(String carId, NavigationState state) {
        try {
            String key = REDIS_KEY_PREFIX + carId;
            String json = objectMapper.writeValueAsString(state);
            redisTemplate.opsForValue().set(key, json, TTL_SECONDS, TimeUnit.SECONDS);
            log.debug("Saved navigation state for carId: {}", carId);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize navigation state for carId: {}", carId, e);
        }
    }
    
    /**
     * Get navigation state from Redis
     */
    public NavigationState getNavigationState(String carId) {
        try {
            String key = REDIS_KEY_PREFIX + carId;
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return null;
            }
            NavigationState state = objectMapper.readValue(json, NavigationState.class);
            log.debug("Loaded navigation state for carId: {}", carId);
            return state;
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize navigation state for carId: {}", carId, e);
            return null;
        }
    }
    
    /**
     * Delete navigation state from Redis
     */
    public void deleteNavigationState(String carId) {
        String key = REDIS_KEY_PREFIX + carId;
        redisTemplate.delete(key);
        log.debug("Deleted navigation state for carId: {}", carId);
    }
}
