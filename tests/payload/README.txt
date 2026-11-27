Standalone checks for PayloadOta (no Android needed): gen_payload.py builds a small incremental payload
(needs: pip install brotli bsdiff4); PayloadHarness.kt applies it with kotlinc + a JVM brotli decoder and
compares the result bit for bit, and runs every patch of a real HyperOS payload through the decoder.
