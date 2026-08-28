package search

import (
	"sync"
	"time"
)

// CircuitBreaker protects the primary search provider from repeated failures.
//
// State machine:
//   - closed:   fewer than threshold consecutive failures; primary is used.
//   - open:     threshold reached; primary is bypassed until the cooldown
//     elapses. ShouldBypass reports true here.
//   - half-open: cooldown elapsed; a single probe request is allowed through.
//     If it fails the breaker re-opens; if it succeeds the breaker closes.
type CircuitBreaker struct {
	mu                  sync.Mutex
	threshold           int
	cooldown            time.Duration
	consecutiveFailures int
	openedAt            time.Time
}

func NewCircuitBreaker(threshold int, cooldown time.Duration) *CircuitBreaker {
	return &CircuitBreaker{
		threshold: threshold,
		cooldown:  cooldown,
	}
}

// ShouldBypass reports whether the primary provider must be skipped because
// the breaker is open (not yet ready to probe).
func (b *CircuitBreaker) ShouldBypass() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.consecutiveFailures < b.threshold {
		return false
	}
	return time.Since(b.openedAt) < b.cooldown
}

// IsOpen reports whether the breaker is currently open (including the
// half-open probe window). Used for observability only.
func (b *CircuitBreaker) IsOpen() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.consecutiveFailures >= b.threshold
}

// IsHalfOpen reports whether the breaker is probing the primary provider.
func (b *CircuitBreaker) IsHalfOpen() bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.consecutiveFailures >= b.threshold && time.Since(b.openedAt) >= b.cooldown
}

// Success records a successful request, closing the breaker.
func (b *CircuitBreaker) Success() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.consecutiveFailures = 0
	b.openedAt = time.Time{}
}

// Failure records a failed request, opening the breaker at the threshold.
func (b *CircuitBreaker) Failure() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.consecutiveFailures++
	if b.consecutiveFailures >= b.threshold {
		b.openedAt = time.Now()
	}
}

// State returns the breaker failure count and whether it is open.
func (b *CircuitBreaker) State() (failureCount int, open bool) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.consecutiveFailures, b.consecutiveFailures >= b.threshold
}
