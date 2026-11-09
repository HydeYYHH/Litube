# Contributing to Litube

Use JDK 21 and the Android SDK as described in the [README](README.md).
Gradle resolves the pinned MediaServiceCore release from JitPack.

## Code organization

- Feature repositories expose domain operations and models. Keep service response
  parsing and capability tokens in the data layer; keep Android views out of it.
- `MediaClient` owns content-request admission, cancellation and session checks.
  Reads and writes have different retry rules. Account mutations must use the
  single-attempt HTTP write path and require server confirmation. A business retry
  is allowed only for an explicitly idempotent desired-state operation, with bounded
  attempts, identity and intent ownership, and uncached confirmation before resending.
  Never replay creation, append or opaque-token commands through a global write retry.
  A separate connection recovery is allowed once only when the body has not started
  and DNS/connect/unreachable proves non-delivery; recheck cancellation and identity.
  Unknown IO, TLS policy, timeouts and HTTP responses do not prove non-delivery.
- Playback request plans carry media identity and byte-window constraints.
  Preserve these checks when changing extraction, caching or recovery.
- Download execution belongs to the generation admitted at its entry point.
  A later task snapshot must never give an old execution authority over a new one.
- ViewModels own feature state. Capture the request's identity and revision before
  asynchronous work, and check them before publishing results or notices. Propagate
  coroutine cancellation.
- A read revision discards obsolete reads; it does not own confirmed writes. Capture
  account and business-scope ownership separately. Returning to a previous filter
  creates a new scope. When reads and writes must be ordered, capture the preceding
  job before launching; never look up a mutable job reference while waiting.
- Opening or closing a save sheet owns its reads and form state. A background save
  belongs to the account until completion or account reset. Keep both lifetimes
  explicit, including error, authorization and cleanup paths.
- Rating requests carry the user's desired and displayed previous state. Cached
  documents supply capabilities, not a second interpretation of the click. Reject
  cache fills from reads started before a confirmed mutation or newer read.
- HTTP 200 is a transport acknowledgement. Recognize target-bound server entities
  and explicit failures before falling back to confirmation reads. Missing state
  is unknown; it cannot prove a write was not applied. Keep retry delays outside
  media workers, and retire an older retry when a newer intent for the target arrives.
  Diagnostic reports contain only allowlisted protocol and state summaries, never
  credentials, raw entity keys or response bodies.
  Preserve typed request policy failures through transport normalization; response
  size limits must not become retryable connection errors.
- Publish backends reserve destination names, including unfinished targets. Recovery
  may discard only unfinished targets. Never overwrite another completed download
  or delete the source after a failed rename; decode FileProvider paths once.
- Optional playback metadata must not hold source submission open. Source generation
  owns chapter results and local updates from Cast refreshes, including same-video
  reopen. Private browsing prevents new local search and viewing history; existing
  history remains readable and removable.

## Style and abstraction

Follow the existing Kotlin style and use descriptive domain names. Prefer explicit
imports over long fully qualified expressions. Keep UI state immutable and keep
`StateFlow.update` transforms free of external side effects.

Extract a concept when it gives repeated behavior one owner or makes a rule easier
to verify. A small value object or pure policy is often sufficient. Avoid forwarding
interfaces, generic action runners and base ViewModels that only relocate code or
hide identity and lifecycle rules. File size alone does not justify a new layer.

Explain why a non-obvious recovery or compatibility branch is necessary. Cover the
behavior with a focused regression where practical. Historical investigation notes
belong in development records; production comments should explain the current rule.
When replacing a workaround, preserve the regression that established its purpose.

For paginated browsing, preserve loaded rows on append failure, consume cursors only
after successful reads, and stop repeated cursors. Keep histories separate for each
query, account and section. A loop can end browsing, but does not prove a complete
playlist for batch actions. A failed refresh that retains old rows must retain the
corresponding pagination history too.

## Validation

Run the regression suites relevant to the behavior, then the project checks:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

Run final review checks from a stable checkout and record which sources they
validate. Concurrent edits during compilation can mix production and test versions.

For changes affected by shrinking or reflection, also run the Release and
`minifiedDebug` gates defined in `.github/workflows/android.yml`. Use the existing
offline device fixtures for Android-specific behavior. Live network probes require
the explicit `network=1` instrumentation argument; account mutations require a
dedicated, authorized test. A passing fixture does not establish real receiver or
live service compatibility.

Add tests that reproduce externally observable failures or meaningful concurrency
interleavings. Avoid timing thresholds and assertions that merely repeat the
implementation. Pure formatting and documentation edits do not need new tests.

Describe the problem, resulting behavior, validation and remaining limits in the
pull request. Keep unrelated cleanup separate. Develop and test core changes in the
[MediaServiceCore repository](https://github.com/HydeYYHH/MediaServiceCore), publish a release,
then update the pinned `msc` version in `gradle/libs.versions.toml`.
