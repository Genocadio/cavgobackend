package com.gocavgo.delivary.entity.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "idempotency_records")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@IdClass(IdempotencyRecordEntity.IdempotencyKey.class)
public class IdempotencyRecordEntity {

    @Id
    @Column(name = "key", length = 128, nullable = false)
    private String key;

    @Id
    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Id
    @Column(name = "mutation_name", length = 64, nullable = false)
    private String mutationName;

    @Column(name = "response_payload", nullable = false, columnDefinition = "TEXT")
    private String responsePayload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static class IdempotencyKey implements Serializable {
        private String key;
        private Long actorId;
        private String mutationName;

        public IdempotencyKey() {}

        public IdempotencyKey(String key, Long actorId, String mutationName) {
            this.key = key;
            this.actorId = actorId;
            this.mutationName = mutationName;
        }

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }

        public Long getActorId() { return actorId; }
        public void setActorId(Long actorId) { this.actorId = actorId; }

        public String getMutationName() { return mutationName; }
        public void setMutationName(String mutationName) { this.mutationName = mutationName; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            IdempotencyKey that = (IdempotencyKey) o;
            return Objects.equals(key, that.key) &&
                   Objects.equals(actorId, that.actorId) &&
                   Objects.equals(mutationName, that.mutationName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, actorId, mutationName);
        }
    }
}
