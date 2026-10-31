package mobile

import (
	"sync"
	"testing"
	"time"
)

func TestAndroidSearchPreparedCancelLinearizesBeforeRun(t *testing.T) {
	worker := &fakeEngineWorker{openStarted: make(chan struct{}), openRelease: make(chan struct{})}
	session, _ := searchSessionFixture(t, worker)
	if err := session.PreparePhase(1); err != nil {
		t.Fatal(err)
	}
	entered, release := make(chan struct{}), make(chan struct{})
	var releaseOnce sync.Once
	var cancelOnce sync.Once
	defer releaseOnce.Do(func() { close(release) })
	session.mu.Lock()
	cancel := session.phaseCancel
	session.phaseCancel = func() {
		cancelOnce.Do(func() { close(entered); <-release })
		cancel()
	}
	session.mu.Unlock()
	cancelDone := make(chan bool, 1)
	go func() { cancelDone <- session.CancelPhase(1) }()
	<-entered
	runDone := make(chan string, 1)
	go func() {
		status, err := session.RunPhase(1)
		if err != nil {
			runDone <- "error"
			return
		}
		runDone <- status
	}()
	select {
	case <-worker.openStarted:
		releaseOnce.Do(func() { close(release) })
		<-cancelDone
		<-runDone
		t.Fatal("worker opened after cancellation was accepted")
	case <-time.After(50 * time.Millisecond):
	}
	releaseOnce.Do(func() { close(release) })
	if !<-cancelDone {
		t.Fatal("matching cancellation was not accepted")
	}
	if status := <-runDone; status != "interrupted" || worker.opens != 0 {
		t.Fatalf("cancelled prepared phase returned %q, opens=%d", status, worker.opens)
	}
}

func TestAndroidSearchPreparedPhaseCancelBeforeRun(t *testing.T) {
	worker := &fakeEngineWorker{}
	session, _ := searchSessionFixture(t, worker)
	if err := session.PreparePhase(1); err != nil {
		t.Fatal(err)
	}
	if !session.CancelPhase(1) || !session.CancelPhase(1) {
		t.Fatal("prepared cancellation not accepted idempotently")
	}
	status, err := session.RunPhase(1)
	if err != nil || status != "interrupted" {
		t.Fatalf("cancelled phase returned %q: %v", status, err)
	}
	if worker.opens != 0 || session.CancelPhase(1) {
		t.Fatal("cancelled phase opened a worker or remained live")
	}
}

func TestAndroidSearchStalePhaseCancelDoesNotAffectResume(t *testing.T) {
	worker := &fakeEngineWorker{openStarted: make(chan struct{}), openRelease: make(chan struct{})}
	session, _ := searchSessionFixture(t, worker)
	if err := session.PreparePhase(1); err != nil || !session.CancelPhase(1) {
		t.Fatal("first phase was not prepared and cancelled", err)
	}
	if status, err := session.RunPhase(1); err != nil || status != "interrupted" {
		t.Fatalf("first phase returned %q: %v", status, err)
	}
	if err := session.PreparePhase(2); err != nil {
		t.Fatal(err)
	}
	defer session.CancelPhase(2)
	if session.CancelPhase(1) {
		t.Fatal("stale cancellation was accepted")
	}
	done := make(chan string, 1)
	go func() {
		status, err := session.RunPhase(2)
		if err != nil {
			done <- "error"
			return
		}
		done <- status
	}()
	select {
	case <-worker.openStarted:
	case <-time.After(time.Second):
		t.Fatal("resumed fake worker did not open")
	}
	if session.CancelPhase(1) {
		t.Fatal("stale cancellation affected running phase")
	}
	select {
	case <-worker.openRelease:
		t.Fatal("stale cancellation released the active worker")
	default:
	}
	if !session.CancelPhase(2) {
		t.Fatal("active cancellation was not accepted")
	}
	if status := <-done; status != "interrupted" {
		t.Fatalf("active phase returned %q", status)
	}
	if worker.opens != 1 || worker.closes != 1 || session.CancelPhase(2) {
		t.Fatal("active phase cleanup or final state is incorrect")
	}
}

func TestAndroidSearchPreparedPhaseIsExclusiveAndEpochMonotonic(t *testing.T) {
	session, _ := searchSessionFixture(t, &fakeEngineWorker{})
	if err := session.PreparePhase(0); err == nil {
		t.Fatal("zero epoch accepted")
	}
	if session.CancelPhase(1) {
		t.Fatal("unprepared phase cancelled")
	}
	if err := session.PreparePhase(1); err != nil {
		t.Fatal(err)
	}
	if err := session.PreparePhase(2); err == nil {
		t.Fatal("second phase prepared while first is live")
	}
	if _, err := session.RunPhase(2); err == nil {
		t.Fatal("wrong epoch ran")
	}
	if _, err := session.Run(); err == nil {
		t.Fatal("legacy Run bypassed prepared phase")
	}
	if err := session.Close(); err == nil {
		t.Fatal("prepared phase closed prematurely")
	}
	if !session.CancelPhase(1) {
		t.Fatal("prepared phase was not cancelled")
	}
	if status, err := session.RunPhase(1); err != nil || status != "interrupted" {
		t.Fatalf("cancelled phase returned %q: %v", status, err)
	}
	if err := session.PreparePhase(1); err == nil {
		t.Fatal("stale epoch was reused")
	}
}
