# Compatibility of saved video series

New searches use `web-connectivity-v1`: GET requests to `https://www.google.com/`,
`https://www.youtube.com/` and `https://github.com/` without query parameters.
Each target must return an HTTP status 200–599 over verified TLS in both fresh-core
rounds. Redirects are not followed; 302, 403, 429 and 500 count as HTTPS connectivity.
Page content, downloads and video delivery are not checked. Candidates with `noise=true`
also require UDP/DNS through SOCKS in both rounds. Saved policies retain their original
semantics; the preset remains live-unverified.

Saved series whose policy includes YouTube video cannot resume; start a new series.
Their status and report remain readable, and already verified results can be exported
under the original saved policy. The old journal and manifest are not migrated.

A saved `representative-video-v1` series without YouTube retains its original
reference-transfer check and can resume. Its MP4 validation confirms bounded transfer,
TLS, reference hash and container structure; it does not establish video playback,
decoding, audio, DRM or general Internet access.
