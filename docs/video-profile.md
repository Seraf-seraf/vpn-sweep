# Compatibility of saved video series

New searches use `google-youtube-web-v1` and check Google and YouTube pages in two
rounds. They do not check video delivery or install YouTube resolver dependencies.

Saved series whose policy includes YouTube video cannot resume; start a new series.
Their status and report remain readable, and already verified results can be exported
under the original saved policy. The old journal and manifest are not migrated.

A saved `representative-video-v1` series without YouTube retains its original
reference-transfer check and can resume. Its MP4 validation confirms bounded transfer,
TLS, reference hash and container structure; it does not establish video playback,
decoding, audio, DRM or general Internet access.
