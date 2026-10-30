package mobile

import "github.com/xtls/xray-core/features/stats"

func TunUplinkBytes() int64 { return tunBytes("uplink") }

func TunDownlinkBytes() int64 { return tunBytes("downlink") }

func tunBytes(direction string) int64 {
	runtimeState.Lock()
	defer runtimeState.Unlock()
	if runtimeState.instance != nil {
		manager, ok := runtimeState.instance.GetFeature(stats.ManagerType()).(stats.Manager)
		if ok {
			if counter := manager.GetCounter("inbound>>>android-tun>>>traffic>>>" + direction); counter != nil {
				return counter.Value()
			}
		}
	}
	return 0
}
