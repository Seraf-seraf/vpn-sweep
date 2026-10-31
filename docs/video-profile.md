# Compatibility of saved video series

New searches use `google-youtube-web-noise-v1` and check the Google and YouTube HTML
pages in two fresh-core rounds. For a candidate with `noise=true`, a UDP/DNS probe
through SOCKS must also pass in both rounds; it is not run for `noise=false`. New searches
do not check video delivery or downloads and do not need YouTube resolver dependencies.

Saved series whose policy includes YouTube video cannot resume; start a new series.
Their status and report remain readable, and already verified results can be exported
under the original saved policy. The old journal and manifest are not migrated.

A saved `representative-video-v1` series without YouTube retains its original
reference-transfer check and can resume. Its MP4 validation confirms bounded transfer,
TLS, reference hash and container structure; it does not establish video playback,
decoding, audio, DRM or general Internet access.
