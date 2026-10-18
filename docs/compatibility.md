# Compatibility contract

Target Xray commit: `ac5d8a83ba798c390cd89f235c3d4c35c77c1586`.
Version label `26.10.10` alone is insufficient: tag v26.10.10 points at another commit.
The runner checks version output for the exact commit prefix and records binary SHA-256.
Imported configuration never selects an executable or executes scripts.

Sources inspected during planning:
- https://github.com/XTLS/Xray-core/blob/ac5d8a83ba798c390cd89f235c3d4c35c77c1586/infra/conf/freedom.go
- https://github.com/XTLS/Xray-core/blob/ac5d8a83ba798c390cd89f235c3d4c35c77c1586/proxy/freedom/freedom.go
- https://github.com/XTLS/Xray-core/blob/ac5d8a83ba798c390cd89f235c3d4c35c77c1586/infra/conf/xray.go
- https://github.com/HappDev/happ_su/blob/f19d6f266111b9b370fbefa32e8bb9c599c2fb00/dev-docs/app-management.md
- https://github.com/HappDev/happ_su/blob/f19d6f266111b9b370fbefa32e8bb9c599c2fb00/dev-docs/examples-of-links-and-parameters.md

Fragment applies to TCP, noise to UDP. Inner UDP over TCP does not make external noise applicable.
maxSplit caps parts, not IP packets. Range values on screenshots are not field bounds.
Mux TCP 1..128 and XUDP 1..1024 have separate sentinel semantics.
QUIC skip bypasses Mux, not the proxy. reject can trigger application TCP fallback.

Happ version/build is unknown. All happ execution is blocked until a built-in verified mapping is available.
No user-supplied string can opt an unverified mapping into verified status.
Advanced fragmentation, array/RandRange and IP-preference mapping are unsupported.
Full JSON passthrough does not imply UI settings are applied.

Android device acceptance and actual target-core config validation remain separate from compile-time support.
