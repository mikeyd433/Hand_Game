Drop tuning recordings (`hand-tuning-*.jsonl.gz`, saved from the app's tuning recorder) here, then:

    ./gradlew :app:testDebugUnitTest --tests '*RecordingReplayTest*' -i

Recordings hold hand-landmark numbers only (no camera images). They are git-ignored by default.
