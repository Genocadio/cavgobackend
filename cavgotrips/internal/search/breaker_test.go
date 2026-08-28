package search

import (
	"testing"
	"time"
)

func TestBreaker_ClosesOnSuccess(t *testing.T) {
	b := NewCircuitBreaker(5, time.Second)
	for i := 0; i < 10; i++ {
		b.Success()
	}
	if b.ShouldBypass() {
		t.Fatal("breaker should not bypass after successes")
	}
	if _, open := b.State(); open {
		t.Fatal("breaker should be closed after successes")
	}
	b.Failure()
	if b.ShouldBypass() {
		t.Fatal("breaker must not open before the threshold is reached")
	}
}

func TestBreaker_OpensAtThresholdAndBypasses(t *testing.T) {
	b := NewCircuitBreaker(3, time.Minute)
	for i := 0; i < 2; i++ {
		b.Failure()
		if b.ShouldBypass() {
			t.Fatalf("breaker should still allow requests after failure %d", i+1)
		}
	}
	b.Failure()
	if !b.IsOpen() {
		t.Fatal("breaker should be open at the threshold")
	}
	if !b.ShouldBypass() {
		t.Fatal("breaker must bypass while open and cooldown not elapsed")
	}
}

func TestBreaker_HalfOpenProbe(t *testing.T) {
	b := NewCircuitBreaker(2, 5*time.Millisecond)
	b.Failure()
	b.Failure()
	if !b.ShouldBypass() {
		t.Fatal("expected bypass immediately after opening")
	}
	time.Sleep(10 * time.Millisecond)

	if !b.IsHalfOpen() {
		t.Fatal("breaker should be half-open after cooldown elapses")
	}
	if b.ShouldBypass() {
		t.Fatal("half-open breaker must allow a probe through")
	}

	// Failed probe re-opens the breaker.
	b.Failure()
	if !b.ShouldBypass() {
		t.Fatal("failed probe must reopen the breaker")
	}
}

func TestBreaker_HalfOpenSuccessCloses(t *testing.T) {
	b := NewCircuitBreaker(2, 1*time.Millisecond)
	b.Failure()
	b.Failure()
	time.Sleep(2 * time.Millisecond)

	b.Success()
	if b.IsOpen() {
		t.Fatal("successful probe must close the breaker")
	}
	if _, open := b.State(); open {
		t.Fatal("state should report closed")
	}
}

func TestBreaker_StateCounts(t *testing.T) {
	b := NewCircuitBreaker(4, time.Minute)
	b.Failure()
	b.Failure()
	count, open := b.State()
	if count != 2 || open {
		t.Fatalf("state = (%d, %v), want (2, false)", count, open)
	}
	b.Failure()
	b.Failure()
	count, open = b.State()
	if count != 4 || !open {
		t.Fatalf("state = (%d, %v), want (4, true)", count, open)
	}
}
