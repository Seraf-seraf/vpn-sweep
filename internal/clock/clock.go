package clock

import (
	"context"
	"time"
)

type Timer interface {
	Stop() bool
	Reset(time.Duration) bool
}

type Clock interface {
	Now() time.Time
	WithTimeout(context.Context, time.Duration) (context.Context, context.CancelFunc)
	Wait(context.Context, time.Duration)
	AfterFunc(time.Duration, func()) Timer
}

type System struct{}

func (System) Now() time.Time { return time.Now() }

func (System) WithTimeout(ctx context.Context, d time.Duration) (context.Context, context.CancelFunc) {
	return context.WithTimeout(ctx, d)
}

func (System) Wait(ctx context.Context, d time.Duration) {
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-ctx.Done():
	case <-timer.C:
	}
}

func (System) AfterFunc(d time.Duration, f func()) Timer { return time.AfterFunc(d, f) }

type contextKey struct{}

func WithContext(ctx context.Context, c Clock) context.Context {
	return context.WithValue(ctx, contextKey{}, c)
}

func FromContext(ctx context.Context) Clock {
	if c, ok := ctx.Value(contextKey{}).(Clock); ok && c != nil {
		return c
	}
	return System{}
}
