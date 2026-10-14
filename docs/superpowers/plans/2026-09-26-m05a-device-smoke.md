# M05A Device Smoke Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the user select one GGUF through Android SAF, stream it into persistent app-private storage, load it through the existing llama.cpp runtime, and manually verify normal and grammar-constrained inference on one real arm64-v8a device.

**Architecture:** `ModelStorage` owns validated, resumeless URI-to-file copying and reuse decisions; `LocalModelRuntime` owns exactly one native engine; `LocalModelViewModel` owns durable UI state across recomposition/configuration changes; `LocalModelTestScreen` is a minimal diagnostic surface. Preparing a candidate file may occur while the old engine remains active, but switching closes the old engine before loading the new one; a failed load leaves no active native model and preserves both app-private model files.

**Tech Stack:** Kotlin, Android Storage Access Framework/`ContentResolver`, coroutines/StateFlow, Android ViewModel, Jetpack Compose, existing `LlamaCppInferenceEngine`, JUnit 4, Gradle/CMake/NDK, ADB.

**Spec:** User-approved M05A Device Smoke brief and design in the current task; no separate repository spec file.

## Global Constraints

- Preserve all 12 pre-existing staged M05A changes exactly; do not reset, unstage, rewrite, or commit them.
- Do not create commits. New Device Smoke changes remain unstaged unless the user explicitly changes this instruction.
- Keep one active native model. Never hold the old and new engines loaded at the same time.
- Do not add Android Service, Room, model router, automatic model choice, second-model runtime, GPU/Vulkan, scenario integration, agent loop, or M05B work.
- Use SAF and `content://` semantics only; never query `_data`, resolve `/storage/...` paths, or pass a content URI to llama.cpp as a filesystem path.
- Treat the source document as read-only. Store completed copies under app-private `filesDir`, never cache or APK assets.
- Stream large files through a bounded buffer on `Dispatchers.IO`; never materialize the GGUF in memory or copy it on the main thread.
- Filename plus provider byte size is only a minimum copy-reuse heuristic, not model identity. Do not add hashing.
- Copy to a temporary file and expose a final model file only after a successful controlled move. Remove partial files on failure/cancellation.
- Persist read permission when the provider supports it and retain only small URI/model metadata in `SharedPreferences`; do not create a model database.
- Do not log prompts, outputs, model contents, or full app-private filesystem paths.
- Use conservative smoke config: context 4096, small max token count, bounded device-independent thread count, temperature 0, fixed seed.
- A fake engine verifies lifecycle only. It must never be reported as evidence that native inference works.
- Stop for explicit user interaction after opening the document picker: “Telefonda GGUF dosyasını seç”.
- Never delete, move, or alter the phone's source GGUF.

## File Structure

- Create `app/src/main/java/com/smooth/agent/inference/ModelStorage.kt`: metadata validation, safe naming, storage layout, streaming copy, progress, free-space checks, partial cleanup, and reuse result.
- Create `app/src/main/java/com/smooth/agent/inference/AndroidModelDocumentSource.kt`: `ContentResolver` metadata/input stream adapter and persistable permission helper.
- Create `app/src/main/java/com/smooth/agent/inference/LocalModelRuntime.kt`: single-engine lifecycle, engine factory boundary, switching and generation.
- Create `app/src/main/java/com/smooth/agent/ui/LocalModelViewModel.kt`: state machine, preferences, copy/load/run/grammar/unload actions.
- Create `app/src/main/java/com/smooth/agent/ui/LocalModelTestScreen.kt`: minimal Compose diagnostic UI.
- Modify `app/src/main/java/com/smooth/agent/MainActivity.kt`: create the local-model ViewModel at Activity scope and pass it to the app surface.
- Modify `app/src/main/java/com/smooth/agent/ui/MainScreens.kt`: add a Home entry and `local-model-test` screen route without replacing navigation architecture.
- Create matching JVM tests under `app/src/test/java/com/smooth/agent/inference` and `.../ui`.

## Review Focus

1. A `.gguf` filename with traversal characters or provider path separators must resolve only inside `filesDir/local_models`; pin this in `ModelStorageTest`.
2. A source that fails or is cancelled after writing bytes must leave no final file and no `.part` file; pin both paths in `ModelStorageTest`.
3. Equal filename and known byte size may reuse an existing completed copy, but mismatched or unknown sizes must stream a new copy; pin all three cases in `ModelStorageTest`.
4. Candidate load failure after closing the old engine must release the old engine once, never reactivate it automatically, and leave runtime state without an active model; pin this in `LocalModelRuntimeTest`.
5. Rapid duplicate UI actions must not create overlapping copy/load/generate operations; pin the ViewModel busy-state guard in `LocalModelViewModelTest`.

---

### Task 1: Model document validation and app-private storage

**Files:**
- Create: `app/src/main/java/com/smooth/agent/inference/ModelStorage.kt`
- Test: `app/src/test/java/com/smooth/agent/inference/ModelStorageTest.kt`

**Interfaces:**
- Produces: `ModelDocument(displayName: String, sizeBytes: Long?)`, `ModelDocumentSource`, `ModelCopyProgress`, `StoredModel`, `ModelCopyResult`, `ModelStorage(rootDirectory: File, availableBytes: () -> Long)` and `suspend fun import(source, onProgress): ModelCopyResult`.
- Contract: only case-insensitive `.gguf` filenames are valid; filenames are sanitized; storage is `rootDirectory/local_models`; progress carries copied bytes and optional total bytes.

- [ ] Write failing JVM tests for invalid extension/missing filename, traversal-safe naming/path containment, known-size reuse, size mismatch copy, unknown-size copy, progress, insufficient space, stream failure cleanup, and coroutine cancellation cleanup.
- [ ] Run `gradlew.bat :app:testDebugUnitTest --tests com.smooth.agent.inference.ModelStorageTest` and verify RED because the storage API is absent.
- [ ] Implement the smallest storage API satisfying the tests: an 8 MiB-or-smaller bounded buffer, `.part` sibling file, preflight space check when size is known, `use`-scoped streams, final length verification, atomic move with controlled fallback, and `finally` cleanup.
- [ ] Run the focused test and verify all `ModelStorageTest` cases pass.
- [ ] Run `git status --short` and confirm the original staged entries remain staged and Task 1 files are merely unstaged/untracked.

### Task 2: Android SAF adapter and persisted selection metadata

**Files:**
- Create: `app/src/main/java/com/smooth/agent/inference/AndroidModelDocumentSource.kt`
- Create: `app/src/main/java/com/smooth/agent/inference/SelectedModelPreferences.kt`
- Test: `app/src/test/java/com/smooth/agent/inference/SelectedModelPreferencesTest.kt`

**Interfaces:**
- Consumes: `ModelDocumentSource` from Task 1.
- Produces: `AndroidModelDocumentSource(ContentResolver, Uri)`, `takePersistableModelReadPermission(ContentResolver, Uri): Boolean`, and `SelectedModelPreferences` storing URI string, display name, byte size, and app-private filename only.

- [ ] Write failing JVM tests for preference round-trip, clear, and ignoring incomplete/corrupt records using a small key-value boundary rather than Android framework mocks.
- [ ] Run the focused preferences test and verify RED.
- [ ] Implement the preference boundary and Android adapter using `OpenableColumns.DISPLAY_NAME`/`SIZE` and `openInputStream`; never read `_data`.
- [ ] Make persistable permission best-effort: request `FLAG_GRANT_READ_URI_PERMISSION`; provider refusal returns `false` without aborting a copy that is currently readable.
- [ ] Run the focused tests and verify GREEN.

### Task 3: Single-active-model runtime and transactional switch boundary

**Files:**
- Create: `app/src/main/java/com/smooth/agent/inference/LocalModelRuntime.kt`
- Test: `app/src/test/java/com/smooth/agent/inference/LocalModelRuntimeTest.kt`

**Interfaces:**
- Consumes: existing `LocalInferenceEngine`, `LlamaCppInferenceEngine`, and `LlamaCppConfig`.
- Produces: `LocalInferenceEngineFactory`, `LocalModelRuntime`, `ActiveModel`, `suspend fun switchTo(storedModel): ActiveModel`, `suspend fun generate(prompt, grammar): String`, `fun unload()`.
- Contract: runtime methods serialize on one mutex; switch closes the active engine before creating/loading the candidate; candidate failure closes any partially created engine and leaves no active model; old file is untouched and no automatic reload occurs.

- [ ] Write failing tests for first load, repeated generations reusing one engine, successful switch ordering (`old.close` before `new.initialize`), failed switch leaving no active model, candidate cleanup, unload idempotency, and serialized concurrent calls.
- [ ] Run the focused runtime test and verify RED.
- [ ] Implement the runtime and production factory with smoke config: context `4096`, max tokens `64`, temperature `0.0f`, seed `0`, and thread count `availableProcessors().coerceIn(1, 4)`; preserve existing general `LlamaCppConfig` defaults.
- [ ] Run the focused runtime tests and verify GREEN.

### Task 4: ViewModel state machine

**Files:**
- Create: `app/src/main/java/com/smooth/agent/ui/LocalModelViewModel.kt`
- Test: `app/src/test/java/com/smooth/agent/ui/LocalModelViewModelTest.kt`

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces: `LocalModelUiState`, explicit phases including `NO_ACTIVE_MODEL`, `COPYING`, `LOADING`, `READY`, `GENERATING`, `OUTPUT`, and `ERROR`; actions `selectModel`, `runPrompt`, `runGrammarSmoke`, `unload`, and `clearError`.
- Contract: one in-flight operation; no engine construction in Composables; grammar smoke uses a generic GBNF accepting exactly `{"ok":true}` and validates exact raw output without Scenario components.

- [ ] Write failing tests for initial/restored metadata state, copy progress, copy-reuse messaging, successful select/load, invalid selection, insufficient storage, failed load state, busy duplicate rejection, normal non-empty output, empty output error, exact grammar success/failure, unload, and switch lifecycle through fake boundaries.
- [ ] Run the focused ViewModel test and verify RED.
- [ ] Implement the ViewModel using `viewModelScope` and injected dispatchers/dependencies for deterministic JVM tests; persist URI permission/metadata only after a valid selection and completed copy.
- [ ] Ensure error text uses filename/status only and never embeds prompt/output/full path.
- [ ] Run the focused ViewModel tests and verify GREEN.

### Task 5: Minimal Local Model Test UI and SAF picker

**Files:**
- Create: `app/src/main/java/com/smooth/agent/ui/LocalModelTestScreen.kt`
- Modify: `app/src/main/java/com/smooth/agent/MainActivity.kt`
- Modify: `app/src/main/java/com/smooth/agent/ui/MainScreens.kt`
- Test: `app/src/androidTest/java/com/smooth/agent/ui/LocalModelTestScreenTest.kt`

**Interfaces:**
- Consumes: `LocalModelViewModel` and `LocalModelUiState` from Task 4.
- Produces: Home entry and diagnostic screen with Select GGUF, filename/size, copy/load status and determinate/indeterminate progress, prompt field, Run, Grammar Test, raw output, Unload, and Change Model.

- [ ] Write a Compose test that verifies required controls, busy-state disabling, progress/status rendering, output rendering, and unload/change callbacks.
- [ ] Run `gradlew.bat :app:compileDebugAndroidTestKotlin` and verify RED because the screen is absent.
- [ ] Implement the screen and Activity-scoped ViewModel wiring. Use `ActivityResultContracts.OpenDocument`, request application/octet-stream/application-compatible types, then validate `.gguf` metadata after selection.
- [ ] Keep the existing string-route navigation and add only `local-model-test`; do not introduce a new navigation framework.
- [ ] Run Android-test compilation and the JVM suite; verify GREEN compilation and unit results.

### Task 6: Full local verification before device interaction

**Files:**
- No source changes expected; fix only failures attributable to Tasks 1–5 and preserve TDD coverage.

- [ ] Run `gradlew.bat :app:testDebugUnitTest` and record total tests/failures.
- [ ] Run `gradlew.bat :app:compileDebugAndroidTestKotlin` and record exit status.
- [ ] Run `gradlew.bat :app:externalNativeBuildDebug` and record exit status.
- [ ] Run `gradlew.bat :app:assembleDebug` and record exit status and APK path.
- [ ] Inspect the APK ZIP entries and verify `lib/arm64-v8a/libsmooth_llama.so` exists and no `armeabi-v7a`, `x86`, or `x86_64` `libsmooth_llama.so` exists.
- [ ] Run `git diff --cached --check`, `git diff --check`, and `git status --short`; verify pre-existing staged M05A entries remain intact, new Device Smoke changes are unstaged/untracked, and no commit exists.

### Task 7: Single-device installation and user selection gate

**Files:**
- No source changes expected.

- [ ] Run `adb devices -l`. If zero devices exist, stop and report; if more than one device is in `device` state, stop without choosing one.
- [ ] For the single device, record `ro.product.manufacturer`, `ro.product.model`, and `ro.product.cpu.abilist`; require `arm64-v8a` support.
- [ ] Install the fresh debug APK with `adb install -r app/build/outputs/apk/debug/app-debug.apk` and record success.
- [ ] Launch `com.smooth.agent/.MainActivity`, navigate to Local Model Test, and open Select GGUF without attempting automated document selection.
- [ ] Stop and tell the user exactly: **“Telefonda GGUF dosyasını seç.”** Do not claim device inference success.

### Task 8: Real-device smoke verification after user selection

**Files:**
- No source changes unless a reproducible defect is found; any defect follows a new RED→GREEN test cycle.

- [ ] Wait without a short timeout for copy completion; record selected GGUF display filename and provider byte size from UI/metadata, never its source or private full path.
- [ ] Verify the app-private final file exists with the expected byte count and no corresponding `.part` remains, using `run-as` read-only listing/stat commands where supported.
- [ ] Verify model load reaches `READY`; on failure capture sanitized UI state/logcat evidence without logging model contents or full path.
- [ ] Run the deterministic normal prompt and record only whether raw output is non-empty; include the actual output in the final report only if the user-facing UI already displays it and it contains no sensitive content.
- [ ] Run Grammar Test and verify the raw output equals the permitted minimal JSON exactly.
- [ ] Unload and verify return to `NO_ACTIVE_MODEL` without crash.
- [ ] If a second GGUF is available and the user elects to select it, verify lifecycle-level switch/unload/load state; second-model inference is optional and two engines must never overlap.
- [ ] Re-run the full JVM tests, native build, and assembleDebug after any defect fix; otherwise retain Task 6 evidence and clearly distinguish it from device evidence.
- [ ] Capture final `git status --short` and report all modified/added files, device/ABI, filename/size, load/normal/grammar/unload results, tests/build/APK evidence, open issues, and unchanged no-commit status.

## Plan Self-Review

- Spec coverage: SAF, URI streaming, private persistent storage, reuse heuristic, large-file behavior, lifecycle, UI, real inference, grammar, tests, device gate, ABI/APK checks, logging and hard exclusions are assigned to Tasks 1–8.
- Type consistency: storage output (`StoredModel`) feeds runtime; runtime and storage feed the ViewModel; ViewModel state/actions feed the screen.
- TDD: Tasks 1–5 start with focused failing tests and explicit RED/GREEN commands; device success is never inferred from fakes.
- Review-focus coverage: each listed high-risk input/failure has an explicit test in its owning task.
- User constraints: no hash, no service, no auto-reload after failed switch, no simultaneous native models, no commit, and existing staged changes are explicitly preserved.
