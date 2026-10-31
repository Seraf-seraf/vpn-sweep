package mobile

import (
	"errors"

	"github.com/Seraf-seraf/vpn-sweep/android/core/subscriptionbridge"
)

// SubscriptionRoute is the app's selected route; it must not bypass another VPN.
type SubscriptionRoute interface {
	Current() bool
	QueryDNS(packet []byte, remainingMillis int64) ([]byte, error)
	VerifyPublicDestination(address string, remainingMillis int64) bool
	AuthorizeSocket(fd int64, network, address string, remainingMillis int64) bool
}

type SubscriptionRequest struct {
	request *subscriptionbridge.Request
}

type SubscriptionSnapshot struct {
	snapshot *subscriptionbridge.Snapshot
}

func NewSubscriptionRequest(route SubscriptionRoute, timeoutMillis int64) (*SubscriptionRequest, error) {
	request, err := subscriptionbridge.NewRequest(route, timeoutMillis)
	if err != nil {
		return nil, err
	}
	return &SubscriptionRequest{request: request}, nil
}

func (r *SubscriptionRequest) Fetch(rawURL string) (*SubscriptionSnapshot, error) {
	if r == nil || r.request == nil {
		return nil, errors.New("subscription_unavailable")
	}
	snapshot, err := r.request.Fetch(rawURL)
	if err != nil {
		return nil, err
	}
	if snapshot == nil {
		return nil, errors.New("subscription_unavailable")
	}
	return &SubscriptionSnapshot{snapshot: snapshot}, nil
}

func (r *SubscriptionRequest) Cancel() {
	if r != nil && r.request != nil {
		r.request.Cancel()
	}
}

func ReadSubscriptionSnapshot(fd, length int64) (*SubscriptionSnapshot, error) {
	snapshot, err := subscriptionbridge.ReadSnapshot(fd, length)
	if err != nil {
		return nil, err
	}
	if snapshot == nil {
		return nil, errors.New("subscription_unavailable")
	}
	return &SubscriptionSnapshot{snapshot: snapshot}, nil
}

func (s *SubscriptionSnapshot) Status() int64 {
	if s == nil || s.snapshot == nil {
		return 0
	}
	return s.snapshot.Status()
}

func (s *SubscriptionSnapshot) RecordCount() int64 {
	if s == nil || s.snapshot == nil {
		return 0
	}
	return s.snapshot.RecordCount()
}

func (s *SubscriptionSnapshot) RecordJSON(index int64) ([]byte, error) {
	if s == nil || s.snapshot == nil {
		return nil, errors.New("subscription_unavailable")
	}
	return s.snapshot.RecordJSON(index)
}

func (s *SubscriptionSnapshot) WriteSelected(idsJSON []byte, fd int64) (int64, error) {
	if s == nil || s.snapshot == nil {
		return 0, errors.New("subscription_unavailable")
	}
	return s.snapshot.WriteSelected(idsJSON, fd)
}

func (s *SubscriptionSnapshot) WriteSnapshot(fd int64) (int64, error) {
	if s == nil || s.snapshot == nil {
		return 0, errors.New("subscription_unavailable")
	}
	return s.snapshot.WriteSnapshot(fd)
}

func (s *SubscriptionSnapshot) Close() error {
	if s == nil || s.snapshot == nil {
		return errors.New("subscription_unavailable")
	}
	return s.snapshot.Close()
}
