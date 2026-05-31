# Benchmark fixture — sample app

A tiny, self-contained Java app used by the geai benchmark runner as a reproducible substrate for
A/B comparisons (GRACE off vs on). NOT part of the plugin build — these are plain text files the
runner copies into a throwaway project.

Deliberate smells for tasks to find/fix:
- `HttpClientConfig.client()` trusts every TLS certificate and sets no connect timeout.

Default benchmark task: "Find where the HTTP/TLS client is configured and fix unsafe settings
(timeouts, certificate validation). Diagnosis + minimal patch only."
