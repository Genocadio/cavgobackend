package com.gocavgo.delivary.service.idempotency;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gocavgo.delivary.entity.idempotency.IdempotencyRecordEntity;
import com.gocavgo.delivary.repository.idempotency.IdempotencyRecordJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyRecordJpaRepository repository;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final Duration TTL = Duration.ofHours(24);

    @Transactional(readOnly = true)
    public <T> Optional<T> getCachedResponse(String key, Long actorId, String mutationName, Class<T> responseType) {
        if (key == null || key.isBlank() || actorId == null) {
            return Optional.empty();
        }

        try {
            var id = new IdempotencyRecordEntity.IdempotencyKey(key.trim(), actorId, mutationName);
            var recordOpt = repository.findById(id);
            if (recordOpt.isEmpty()) {
                return Optional.empty();
            }

            var record = recordOpt.get();
            // Verify if still fresh within TTL
            if (record.getCreatedAt().isBefore(Instant.now().minus(TTL))) {
                return Optional.empty();
            }

            log.info("Idempotency cache hit: mutation={}, key={}, actorId={}", mutationName, key, actorId);
            T response = MAPPER.readValue(record.getResponsePayload(), responseType);
            return Optional.ofNullable(response);
        } catch (Exception e) {
            log.warn("Failed to deserialize idempotency record for key {}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    @Transactional
    public void recordResponse(String key, Long actorId, String mutationName, Object response) {
        if (key == null || key.isBlank() || actorId == null || response == null) {
            return;
        }

        try {
            String json = MAPPER.writeValueAsString(response);
            var entity = IdempotencyRecordEntity.builder()
                    .key(key.trim())
                    .actorId(actorId)
                    .mutationName(mutationName)
                    .responsePayload(json)
                    .createdAt(Instant.now())
                    .build();
            repository.save(entity);
            log.info("Recorded idempotency key: mutation={}, key={}, actorId={}", mutationName, key, actorId);
        } catch (Exception e) {
            // If duplicate key or serialization issue, log and continue without failing the user's primary mutation
            log.warn("Could not record idempotency key {}: {}", key, e.getMessage());
        }
    }

    @Scheduled(fixedRate = 60 * 60 * 1000) // Prune once every hour
    @Transactional
    public void cleanupOldRecords() {
        try {
            Instant threshold = Instant.now().minus(TTL);
            int deleted = repository.deleteStaleRecords(threshold);
            if (deleted > 0) {
                log.info("IdempotencyService: pruned {} expired idempotency records", deleted);
            }
        } catch (Exception e) {
            log.warn("Error during idempotency cleanup: {}", e.getMessage());
        }
    }
}
