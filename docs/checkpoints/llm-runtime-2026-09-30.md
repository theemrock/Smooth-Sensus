# SMOOTH LLM Runtime Checkpoint — 2026-09-30

## Experiment 1 closed — native O0 -> O3 only, 2026-10-01

### Exact change and invariant controls

Added one target_compile_options loop at the end of `app/src/main/cpp/CMakeLists.txt`: for `smooth_llama`, `llama`, `llama-common`, `ggml`, `ggml-base`, `ggml-cpu`, add PRIVATE `$<$<CONFIG:Debug>:-O3>` when that target exists. Only native inference target optimization changes. No Release switch, NDEBUG, ARM variants, backend enablement, source/runtime/test/UI/domain edits, threadpool work or tuning. Ancillary vendor/hash/HTTP/build-info targets remain unchanged. This is the explicitly requested bounded experiment configuration, not a production-configuration decision.

Pinned llama.cpp source/SHA unchanged. Baseline arm64/API33 CPU target, features, static linking, assertions/debug symbols, CPU-only/offload settings, context4096/batch512/ubatch512, availableProcessors capped1-4 for both thread counts, sampling, model-owned template, JNI/runtime path all unchanged. Actual CPU residency/thermal state was not controlled between the historical A measurement and this B measurement; one pair is comparative evidence, not a formal benchmark.

### Build/artifact verification

- `:app:testDebugUnitTest :app:assembleDebug`: BUILD SUCCESSFUL, 4m41s; 144 tests, zero failures/errors.
- Actual `app/.cxx/Debug/4j5y6e6i/arm64-v8a/compile_commands.json`: 126 entries; 120 targeted entries have -O3, including llama unity translation units, ggml core/CPU/ARM quant kernels, common/Jinja and JNI. Six ancillary hash/HTTP/build-info entries remain without O3. No -O0, -DNDEBUG, -march or -mcpu in any command. No later optimization flag overrides O3. NDK28.2.13676358/Clang19.0.1 and target aarch64-none-linux-android33 unchanged.
- Actual CMake cache remains Debug; BACKEND_DL/CPU_ALL_VARIANTS/NATIVE/OpenMP/OpenCL/Vulkan OFF. HAVE_FMA=1; DOTPROD/I8MM/FP16 vector-arithmetic probes remain empty. No enhanced ARM feature was introduced.
- APK libsmooth_llama SHA256 equals stripped Debug native artifact: `C7B1E425EBA8C2786F6C5A899717733E02E1CB1F58809AC8EA045E67B6A95A3D`, different from O0 artifact `02BD6DF13ABB9047C3FC0C71A16FF3BA2530EA95B1431F9C9FD11204371CB150`.
- New unstripped ELF build ID `c99dd0ca7a2d8b1913624d07e0c700049ca8c4ce`. Read-only disassembly of ggml_vec_dot_q4_K_q8_K shows 0x50 stack allocation instead of the old 0x630 local allocation, substantially less spilling, NEON instructions and retained assertion branches. Compile commands, not size alone, establish effective O3.
- `adb install -r app/build/outputs/apk/debug/app-debug.apk`: Success. No uninstall/wipe. Device-installed base.apk SHA256 equals local built APK: `39B2421D0A7A9D7CC059259383D019FC7E90020D18B0CFD8DBFA043C02C6B545`.

### One measured B run / unchanged condition

S24 Ultra SM-S928B connected. Existing selected preference was Spark, not Qwen2; selected the requested Qwen2 source via existing file picker. App-private Qwen2 copy already existed and was reused; size986045824 bytes and modification time2026-09-30 23:58 unchanged after run. No GGUF deletion/recopy. Model activation/reload occurred after install; no warm-up inference and no other model inference. Exactly one Run activation for the measured `selam` request.

Verified before Run and in result screenshot: `qwen2-1_5b-instruct-q4_k_m.gguf`, 940.4 MiB, Hazır, prompt exactly `selam`, Thinking OFF, max64. Output: `Merhaba! Size nasıl yardımcı olabilirim?`, termination EOG.

| Metric | A: supplied O0 baseline | B: one O3 run | Speedup |
| --- | --- | --- | --- |
| load | 1385ms | 808ms | 1.71x (secondary/setup-dependent) |
| prompt tokens | 21 | 21 | same |
| prompt evaluation | 19.81s / 19810ms | 0.57s / ~570ms | rounded duration |
| prompt tok/s | 1.06 | 37.14 | 35.04x |
| TTFT | 19832ms / 19.832s | 577ms / .577s | 34.37x faster |
| generated tokens | 11 | 11 | same |
| generation | 14.85s / 14850ms | .41s / ~410ms | rounded duration |
| generation tok/s | .74 | 26.67 | 36.04x |
| total inference | 34.68s / 34680ms | .98s / ~980ms | 35.39x faster |

Values captured from the unchanged Local Model Test UI. Prompt/generation/total durations are rounded to .01s; their ms conversions are approximate, not invented high-resolution native timestamps. Load/TTFT displayed to nearest ms; rates to .01tok/s. Speedups use displayed rates and durations, so are approximate. Same raw token counts remove output-length disparity for this pair; supplied baseline output text was not available for exact text comparison. No new instrumentation/logging code was added to obtain extra precision.

Evidence artifacts (ignored local build output): `app/build/experiment1-o3/result.xml` and `result.png`. Screenshot visually checked. Device temporary capture files are named `/sdcard/smooth-experiment1-*`; unrelated device files/apps untouched.

### Interpretation and stop boundary

O0 was a MAJOR contributor to the severe slowdown for this controlled model/condition: both prompt and generation rates increased approximately35-36x while ARM/backend/thread/runtime configuration and token counts stayed fixed. This supports native optimization as a dominant contributor in this comparison, not merely an incidental defect. It does not establish all-model performance, sustained throughput, device thermal reproducibility or that the entire performance problem is solved. Historical A vs one B is not a repeated/reversed-order benchmark.

Remaining suspects are unchanged and NOT investigated: missing advanced ARM kernels, disposable worker pools, effective thread count/core scheduling, sampling/planning overhead and model-specific behavior. No Experiment2, additional models, Chat benchmarks, ARM feature enablement, thread/affinity/pool tuning or architectural cleanup was started. Debug O3 experiment change remains in the unstaged worktree and installed app; no automatic revert or production adoption beyond the requested experiment.

Safety audit: git status/diff check/staged files and llama worktree/SHA inspected; staging empty, llama clean at `9f70b2cecd1a9a3f73ac525c47ca22a6ee9a7b69`, parent gitlinks unchanged. Existing uncommitted work preserved. No stage/commit/push/reset/rebase/amend; `.superpowers/` untouched. This pass changes only the native CMake optimization block and this checkpoint; generated verification/build artifacts are ignored.

## Diagnostic-only follow-up — 2026-10-01

This section supersedes assumptions about the current path, not the historical measurements below. Read AGENTS.md and both runtime/conversation checkpoints; inspected current Kotlin/C++, pinned llama.cpp sources, CMake caches/compile commands and existing APK/ELF artifacts. No build, benchmark, phone access, install, code/test/config/model edit or experiment was performed. Only this checkpoint was edited. Earlier uncommitted work is preserved.

### A. Actual inference architecture/path

- `ui/LocalModelTestScreen.kt:43,65`: Compose collects StateFlow; Run calls `LocalModelViewModel.runPrompt`. `LocalModelViewModel.kt:runGeneration` publishes GENERATING once, awaits `runtime.generateWithMaxTokens`, then publishes complete text/metrics once. No streaming callback or per-token Compose update.
- `SmoothApplication.kt`: lazy application-owned `LocalModelRuntime` shared with Chat. Chat gateway maps bounded role/content messages and calls `generateChat(enableThinking=false)`; `ChatViewModel.kt:76,95` uses that gateway. Local Model Test is one USER message, not conversation history.
- `inference/LocalModelRuntime.kt:39,68,81`: switch closes the previous engine, creates/initializes one candidate; requests reuse the loaded model/context under coroutine Mutex. The actual factory config is `smokeConfig`, not the unrestricted default in LlamaCppConfig. Unload/replacement frees native state; ViewModel clearance does not unload it.
- `LlamaCppInferenceEngine.kt`: Dispatchers.IO plus ReentrantLock serializes load/generate/release. One native generation call blocks its IO worker for the whole request; it does not block on UI rendering. Runtime mutex, engine lock and native Engine mutex are request-level locks, not re-acquired per token. Waiting for these locks occurs outside native total timing.
- `smooth_llama_jni.cpp:451,510,550`: nativeLoad creates model/context/templates once; nativeGenerate/nativeGenerateChat convert input once, acquire Engine mutex, call common `generate`, construct one Java result. Native release deletes Engine/context/model. Backend initialization is process-once via call_once.
- `generate:319`: render model-owned Jinja once, two-pass tokenize once, clear existing memory once (no model/context reconstruction), create sampler once, prompt-decode batches, sample/decode tokens, UTF-8 finalization, one final Java result. Full prompt is evaluated once per request; no cross-request prefix reuse, but no full-prompt reevaluation inside generation.

### B. Actual native build configuration and artifact proof

- `app/build.gradle.kts`: only arm64-v8a; CMake 3.22.1. Only benchmark passes `-DSMOOTH_OPTIMIZED_ARM=ON`. Normal Debug does not inherit it. Benchmark is debuggable but native CMake explicitly forces Release; directory name `app/.cxx/Debug` alone does NOT identify native optimization.
- `app/src/main/cpp/CMakeLists.txt:14-39`: normal path statically links llama/ggml/common into libsmooth_llama; CPU variants and backend DL OFF, GGML_NATIVE OFF. Optimized path uses shared libraries, Release, dynamic CPU variants. CPU KleidiAI, llamafile, OpenMP, OpenCL, Vulkan OFF in both.
- Actual normal Debug cache `app/.cxx/Debug/4j5y6e6i/arm64-v8a/CMakeCache.txt:67`: Debug. NDK 28.2.13676358 (r28c), Clang 19.0.1/r530567e; commands target aarch64-none-linux-android33. JNI, ggml core and ARM quant kernels compile with `-g -fno-limit-debug-info`, no `-O` flag (Clang default O0), no NDEBUG, no explicit -march/-mcpu. This is a confirmed unoptimized native build, not merely an unoptimized Kotlin/UI build.
- Debug cache: CPU ON; CPU_REPACK ON; CPU_ALL_VARIANTS/BACKEND_DL/NATIVE/OpenMP/BLAS/CUDA/Metal/SYCL/RPC/OpenCL/Vulkan OFF. No GPU backend library in the Debug APK. CPU remains NEON/FMA-capable baseline AArch64, NOT a proven all-scalar fallback. HAVE_FMA=1; HAVE_DOTPROD, HAVE_MATMUL_INT8 and HAVE_FP16_VECTOR_ARITHMETIC empty (failed compile-feature probes). No DOTPROD/I8MM/FP16 vector-arithmetic enhanced kernels in this configuration. F16 storage/conversion is separate from FP16 vector arithmetic. `ggml-cpu/arch/arm/quants.c` contains baseline NEON plus feature-gated advanced paths.
- Existing optimized benchmark cache `4c2c4r35`: native Release; critical commands `-O3 -DNDEBUG`, CPU variants use armv8-a through dotprod/fp16/i8mm/SVE/SME combinations. These artifacts are a different variant; their presence does not optimize the installed Debug APK. No selection of a particular runtime variant was observed in this pass.
- Existing final `app/build/outputs/apk/debug/app-debug.apk` contains only libsmooth_llama and AndroidX graphics-path .so files. ZIP-stream SHA-256 of libsmooth_llama matches `app/build/intermediates/stripped_native_libs/debug/stripDebugDebugSymbols/out/lib/arm64-v8a/libsmooth_llama.so`: `02BD6DF13ABB9047C3FC0C71A16FF3BA2530EA95B1431F9C9FD11204371CB150`. No extraction/write was required.
- Corresponding unstripped Debug ELF is 129896136 bytes, stripped library 20310608 bytes. Build ID `fb6d3e1e1fae9a97b4207b38c09bf9cba7436c05`; ELF Android note identifies API33/r28c/13676358. Read-only llvm-objdump of `ggml_vec_dot_q4_K_q8_K` shows a 0x630-byte stack frame, extensive argument/local spilling and live assertion branches, consistent with O0 compile commands. Size/debug symbols alone are not the evidence for O0. Device-installed bytes were not read; association with supplied measurements relies on the user's clean Debug-install account.

### C. Actual runtime/context/thread configuration

| Setting | Actual source/configuration |
| --- | --- |
| n_ctx | Explicit 4096, LocalModelRuntime.smokeConfig / nativeLoad |
| n_batch / n_ubatch | Explicit min(4096,512)=512 / 512 |
| n_threads / n_threads_batch | Same explicit availableProcessors().coerceIn(1,4), captured at engine creation |
| GPU | Explicit model n_gpu_layers=0; context offload_kqv=false, op_offload=false; CPU-only build |
| Load/check | check_tensors=true; pinned default load_mode=AUTO, lazy_mode=AUTO |
| mmap / mlock | No old use_mmap/use_mlock fields here: loader AUTO initially enables mmap (subject to compatibility/backend fallback); AUTO is not MLOCK. No explicit mlock |
| KV | Pinned defaults type_k/type_v=F16, n_seq_max=1, kv_unified=false, swa_full=true; architecture-specific memory implementations still apply |
| Flash attention | Pinned default AUTO; context probes backend graph support. Effective model-specific result not exposed/observed |
| Embeddings / logits | embeddings=false; no logits_all field in this revision; null batch.logits defaults to final input token output only |
| Perf / callbacks | no_perf=true (llama internal perf collection off; custom chrono still works); cb_eval and abort callbacks null |
| Sampling | temperature=0, topK=40, topP=.95, seed=0; top-k/top-p/temp/greedy chain; grammar absent in normal Run |
| Request options | Local Test default64, user range16-512; supplied tests64/ThinkingOFF; Chat256/ThinkingOFF unchanged |

Pinned defaults verified in `third_party/llama.cpp/src/llama-context.cpp:3696` and `llama-model.cpp:2854`, not current upstream docs. Pinned context defaults before overrides are n_ctx512/n_batch2048/n_ubatch512 and GGML_DEFAULT_N_THREADS for both thread fields.

`llama-context.cpp:2566 graph_compute` selects batch threads for ubatch.n_tokens>1, generation threads otherwise, and forwards count to backend each graph. No app call changes counts during inference, no custom affinity/priority/NUMA scheduling. Four threads are intended if availableProcessors>=4; actual Android return/count/core placement cannot be proven offline. Dispatchers.IO is one coordinating worker, not evidence of single-threaded matrix math.

Important lifecycle issue: app never calls llama_attach_threadpool. Context passes null pool to CPU backend (`llama-context.cpp:2570`; `ggml-cpu.cpp:173`). `ggml-cpu.c:3409-3470` creates a disposable pool per CPU graph compute and frees/joins it afterwards; `3322` creates n_threads-1 pthread workers. Thus model/context and work buffers persist, but worker pool does not. Default pool params (`ggml.c:8146`) normal/inherited priority, poll50, no strict placement, all-zero/inherited CPU mask. This is confirmed repeated overhead, not proof of seconds/token impact. Scheduler splits can make it more than once per decode. Internal graph reuse exists (`llama-context.cpp:1405`); it is not forcibly disabled by app.

### D. Prompt evaluation

`smooth_llama_jni.cpp:343-354` obtains llama_n_batch and advances offset by 512, calls `llama_batch_get_one(prompt_tokens.data()+offset,count)`, then one llama_decode per chunk. Every supplied 11-21 token prompt fits ONE app-level decode call. Token-by-token prompt ingestion hypothesis is ruled out at the bridge level; model-specific internal ubatch partitioning is distinct and not measured.

Batch helper (`llama-batch.cpp:966`) creates a non-owning descriptor, not a heap allocation/copy of tokens. Null positions/sequence/logits are intentional compatibility defaults: sequence0, next position from memory maximum+1 (`1258-1291`), only last-token logits (`1310`). Memory was cleared once, so prompt starts at0 and generation continues at tip. No per-token template rendering/tokenization/context reset. Full bounded prompt is reprocessed on a later request by design, irrelevant to first one-word run anomaly.

### E. Every generated token (classification)

| Operation | Classification / evidence |
| --- | --- |
| Sample final logits, test EOG | EXPECTED; generate:363; llama-sampler.cpp:895 reads synchronized logits, fills/reuses chain candidate buffer across vocab, accepts sampled token |
| top-k/top-p/temp then greedy at temp0 | SUSPICIOUS unnecessary work candidate at greedy setting; chain once/request, candidate processing each token; unmeasured, not minute-scale proof |
| token_piece + output append | EXPECTED small allocations/copies; fresh128-byte vector, possible resize/retry, native std::string append; no Java callback |
| one-token llama_batch_get_one | EXPECTED stack descriptor referencing token, no batch_init heap allocation |
| llama_decode once per emitted non-EOG token | EXPECTED and LIKELY EXPENSIVE actual model compute; current native O0/limited ARM features make this top concern |
| Disposable pthread pool per CPU graph | SUSPICIOUS repeated allocation/create/wait/join/free; pinned CPU implementation, no attached pool |
| ggml graph inputs/planning/synchronization | EXPECTED native execution/barriers; graph reuse when topology compatible, work buffer reused/grown as needed |
| JNI/Java/UI/locks/template/tokenization/load | ABSENT per token; one JNI request/result, locks held whole request |

No intentional sleep/yield/delay in bridge loop, no per-token log sink I/O (silent native log callback), no repeated model/context setup/full prompt evaluation. Native worker barriers/poll/condition waits are computational coordination, not hard-coded seconds delays. At MAX_TOKENS/context limit, final emitted token is decoded even though its next logits are unused: one avoidable decode, not N repeated prompts. For EOG runs, last visible token decode is needed to discover termination.

### F. JNI hot path

One nativeLoad per model activation, ONE nativeGenerate (or nativeGenerateChat) per entire request, one nativeRelease on unload/replace. Zero JNI crossings inside prompt/decode/token loops. Inputs copied UTF-16 -> native UTF-8 once (Chat role/content arrays once/request). Result uses a Java byte array UTF-8 String plus Metrics/Result construction once. Native result conversion/class lookup and UI delivery occur after total timestamp. No repeated large arrays/strings crossing JNI per token.

### G. Timing validity and limitations

- All native phase timers use steady_clock milliseconds; Kotlin rates count*1000/ms; UI seconds ms/1000 (`LocalInferenceEngine.kt:InferenceMetrics`, `LocalModelTestScreen.kt:158`). No unit conversion or double-count defect found.
- load: nativeLoad timer begins after backend initialization/input path conversion; includes GGUF load/tensor validation, chat-template init, context init. Excludes import/copy, lock queues, library/backend initialization; reused load value appears on subsequent requests, NOT included in inference total.
- prompt: only chunk decode loop. Excludes template/tokenization/memory clear/sampler creation, includes native graph work/pool overhead. Current CPU backend compute is synchronous (`ggml-cpu.cpp:graph_compute`, no asynchronous/synchronize callbacks), so current prompt time is not merely queued GPU work. Future asynchronous backends would require reconsidering this boundary.
- TTFT: total_start through first sampled token, before EOG test and token-to-piece/JNI/UI. Native first sampled token latency, not first visible UI text; screen gets complete output only. If first token is EOG, TTFT still set with zero visible tokens.
- generation: first sample through final loop exit, including all sample/piece/decode overhead and terminal EOG sampling. Count excludes EOG and counts model tokens, not words/characters. Includes one decode for each emitted token, including unnecessary final decode on MAX_TOKENS. Not a pure kernel tok/s; no UI delay included.
- total: template/tokenize/reset/sampler + prompt + generation; excludes lock wait, input JNI conversion, final UTF-8 validation/trimming, Java result creation/UI delivery. Small UTF-8 incomplete-tail trim may remove final text bytes without decrementing generated-token count. templateApplied always true even fallback-to-raw branch: semantic metadata caveat, not throughput defect.
- Supplied total minus prompt minus generation is approximately0-30ms (rounded values); TTFT minus prompt about20-100ms. This places minute-scale delays inside native phase intervals, not template/UI fixed overhead. Timing measures are credible comparative evidence, not device kernel profiling.

### H. Ranked findings and differential reasoning

**CONFIRMED ROOT CAUSE (scope-qualified):** normal Debug pipeline produces and packages O0 native model/ggml kernels. Confirmed build-performance defect by Debug compile commands + APK hash match + ELF disassembly. It does NOT by itself prove the fraction of supplied device latency or exclusive cause; no new controlled device A/B was run. Historical optimized benchmark in the older checkpoint changed BOTH optimization and ARM variants, so its large improvement cannot isolate these variables.

**HIGH-CONFIDENCE SUSPECT:** missing enhanced ARM DOTPROD/I8MM/FP16-vector paths in normal Debug, versus available compiled optimized variants. Confirmed configuration difference; device capability/which hot kernels/size of contribution not observed. NEON/FMA remains present, so do not describe Debug as entirely scalar. Evidence: CMake flags, HAVE probes, arch/arm/quants.c feature guards.

**MEDIUM-CONFIDENCE SUSPECTS:** disposable worker-pool churn proven in pinned source (impact unknown); 1-4 thread cap and unpinned Android placement may limit throughput (actual4 vs fewer/core residency unknown); temp0 top-k/top-p work and graph/planning/assert overhead possible contributors. mmap page faults, thermal/power/scheduling constraints and architecture-specific AUTO flash/recurrent behavior remain unmeasured device/runtime questions, not repository-established causes. check_tensors may cost load but is outside repeated generation; short loads do not exclude later mapped-page faults.

**RULED OUT / LOW PRIORITY:** bridge tokenwise prompt ingestion; per-token JNI/Java/UI synchronous callbacks; per-token model reload/context rebuild/full prompt tokenization; explicit sleeps; count/time unit error; accidental default single-thread config. GPU absence is intentional CPU-only design, not alone evidence of pathological throughput. Context4096/F16 KV alone has no proven relation to short-prompt anomaly.

Model matrix: Qwen2 1.5B faster than Qwen4B, Qwen3.5 Q4 prompt63.02s vs Q6 74.04s (Q4 prompt rate ~1.18x), and Gemma E2B faster despite similar file sizes are consistent with costly real architecture/quant-dependent compute under O0/limited kernels. They are inconsistent with a dominant model-independent fixed sleep/JNI/UI delay. Generation Q4/Q6 .18/.16 is weak one-run evidence, sensitive to token counts/rounding. Gemma Q4_0 vs Q4_K_M reverses prompt/generation ranking; cannot claim universal quant ordering. Load1.4-5.2s separate from native prompt19.8-74s and generation14.9-105.5s favors compute-path pathology over repeated loading. Sizes alone do not establish active parameter count or architecture internals; supplied model labels are not independently GGUF-verified. Both slow phases fit a shared native kernel/build issue; differential measurements do not uniquely distinguish O0, feature loss, pool overhead or device scheduling.

### Smallest next experiments — proposed only, NOT run

1. Optimization-only A/B: same pinned code/model/prompt/threads/CPU architecture and static backend, vary ONLY native O0 vs O3/NDEBUG build profile (record exact flags/artifact ID). Measure synchronized native prompt decode and one-token decode durations plus unchanged end-to-end metrics. Large repeatable improvement supports unoptimized-native dominance; little improvement elevates feature/runtime suspects. Optimization and assertions belong to the declared profile variable; to isolate optimization alone keep assertions identical. Do not compare current benchmark vs Debug as a single-variable test.
2. Feature-only A/B, conditional on experiment1: hold optimized profile/model/thread count fixed; vary only baseline CPU target vs compatible ARM feature/backend selection, log selected backend/effective CPU features. Repeat same short workload. Improvement establishes feature-path contribution; no improvement deprioritizes missing advanced paths for that model. No need to enable GPU or test new models.
3. Conditional pool/thread investigation, only if residual performance remains abnormal: first measure effective n_threads/n_threads_batch and timing around decode vs sampling/pool creation, with build unchanged. If pool share material, A/B only attached reusable pool vs disposable pool with identical count. If compute dominates with poor CPU placement, separately vary only thread count (not pool/affinity simultaneously), measure per-phase rate/core residency. A positive result attributes that variable; negative result redirects to scheduling/page-fault/backend profiling. No prompt-batching A/B justified: bridge already batches these prompts.

No experiments/fixes approved or performed. Exact unfinished work: device A/B attribution and effective runtime feature/thread/flash/core observations; optimized production adoption decision remains open. Stop here; no inference fix, M08 or benchmarking pass started.

### Repository safety

Pinned llama.cpp worktree clean at `9f70b2cecd1a9a3f73ac525c47ca22a6ee9a7b69`; parent HEAD/index gitlinks match. Staging empty. `git diff --check` has no whitespace errors (existing CRLF notices only). No stage/commit/push/reset/rebase/amend. `.superpowers/` untouched. Production/test/build files unchanged by this diagnostic pass; final read-only audit follows this checkpoint edit.

## Current Git state

- Parent HEAD: `671ab89431837305d1156f94dd2497fc149f8800`.
- Nothing from M06 or the runtime experiments is staged or committed.
- Existing M06 working-tree changes remain present.
- llama.cpp is clean at `9f70b2cecd1a9a3f73ac525c47ca22a6ee9a7b69`.
- The parent HEAD and index gitlinks both match that llama.cpp SHA.
- `.superpowers/` was untouched.

## Milestone state

Completed:

- M01 Scenario Resolver
- M02 Resolver status and ambiguity rules
- M03 Request Normalization
- M04 Strict Scenario JSON Parser
- M05 Local llama.cpp Runtime and Device Smoke
- M06 SMOOTH Identity, Home Shell, Theme, and Turkish UI

The M06 and runtime working-tree changes have not yet been committed.

## M06 product state

- App and project identity: SMOOTH
- UI title: S.M.O.O.T.H.
- Package, namespace, and application ID: `com.theemrock.smooth`
- `versionCode`: 6
- `versionName`: 0.0.6
- The Home to Learn & Scenario workflow is operational.

Final workflow order:

1. Uygulamaları Tara
2. Uygulama Envanterini Aç
3. Tam JSON’u Kaydet
4. ChatGPT TXT’sini Kaydet
5. Yapay Zekâ JSON’unu İçe Aktar
6. Yapay Zekâ Sonucunu Yapıştır
7. Yetenek Kaydını Aç
8. Yerel Model Testini Aç

Old-package app-private data is intentionally not migrated. Historical documentation mentioning older package names should not be opportunistically rewritten.

## LLM investigation root causes

1. The old SMOOTH adapter obtained the real GGUF chat template but passed it through legacy `llama_chat_apply_template()`.
2. That API uses heuristic template detection rather than arbitrary Jinja evaluation.
3. Gemma 4 therefore failed before inference with `Model chat template could not be applied`.
4. Qwen was more deceptive: it could fall through to generic ChatML-like formatting, so inference ran while the real Qwen template and `enable_thinking` control were bypassed.
5. The Debug native configuration was also catastrophically slow because it was an unoptimized, generic Android ARM build.
6. Template behavior and native performance were separate problems.

## Implemented runtime changes

The experiment work added:

- Structured `InferenceResult` and `InferenceMetrics`
- Model load, prompt evaluation, TTFT, decode, and total timing
- Prompt and generated token counts
- Prompt and decode token rates
- Termination reasons: `EOG`, `MAX_TOKENS`, `CONTEXT_FULL`, and `ERROR`
- Native monotonic timing
- Full model-owned Jinja application through the llama.cpp common chat layer
- Generic native `enable_thinking` control
- Local Model Test THINK OFF and THINK ON controls
- Compact completed-run metrics
- A separate benchmark build
- Release native `-O3 -DNDEBUG`
- Android ARM runtime dispatch variants
- Benchmark-only extracted JNI packaging required for dynamic backend discovery

## Controlled results

| Run | Load | Prompt | Prompt rate | TTFT | Generated | Decode rate | Total | End |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Legacy Debug baseline | 4.83 s | 14 tokens / 47.78 s | 0.293 tok/s | 47.79 s | 64 | 0.172 tok/s | 419.80 s | MAX_TOKENS |
| Jinja THINK OFF, Debug | 3.95 s | 18 tokens / 82.88 s | 0.217 tok/s | 82.90 s | 1 | 0.173 tok/s | 88.68 s | EOG |
| Jinja THINK ON, Debug | 3.71 s | 16 tokens / 71.54 s | 0.224 tok/s | 71.55 s | 64 | 0.160 tok/s | 472.48 s | MAX_TOKENS |
| Jinja THINK OFF, optimized ARM | 6.54 s | 18 tokens / 1.09 s | 16.58 tok/s | 1.10 s | 1 | 8.96 tok/s | 1.20 s | EOG |

Outputs:

- Legacy: visible `<think>` reasoning, truncated at 64 tokens
- Jinja THINK OFF: `Okay`
- Jinja THINK ON: extended reasoning, truncated at 64 tokens
- Optimized THINK OFF: `Okay`

Interpretation:

- THINK OFF fixed behavior, not raw throughput.
- Optimized ARM fixed raw throughput.
- Prompt throughput improved roughly 76x.
- Decode throughput improved roughly 52x.
- Warm short-command inference is approximately 1.20 seconds.
- The measured cold path is approximately 7.75 seconds, including 6.54 seconds of model loading.
- Model loading need not occur for every command; the runtime lifecycle allows the loaded model to persist.

## Gemma validation

Exact file: `gemma-4-E2B-it-Q4_K_M.gguf`  
Size: `3,106,738,272 bytes`

- Template application: PASS
- Tokenization: 15 tokens
- Prompt evaluation: 35.99 seconds in Debug
- Inference started: YES
- One-token output: `Yes`
- Previous template error: gone

## Qwen test file

Exact file: `Qwen3.8-4B-Q4_K_M.gguf`  
Size: `2,783,446,304 bytes`

## Benchmark and Android detail

- A debuggable benchmark variant initially caused AGP and CMake to use Debug semantics.
- The benchmark configuration was corrected so native CMake uses Release.
- Android ARM CPU dispatch builds multiple backend `.so` libraries.
- llama.cpp dynamic backend discovery requires those libraries in a real filesystem directory.
- Android initially kept them in the APK path, so backend discovery failed before model loading.
- Benchmark JNI packaging was changed to legacy, extracted native-library packaging.
- After extraction, dynamic backend discovery worked and the optimized test succeeded.
- Do not copy this experimental setup into production without an intentional production build decision.

## Verification

- Complete Debug unit suite: PASS
- Debug native build: PASS
- `assembleDebug`: PASS
- Android instrumentation source compilation: PASS
- Optimized `assembleBenchmark`: PASS
- Qwen legacy baseline: PASS
- Qwen Jinja OFF and ON: PASS
- Gemma template validation: PASS
- Optimized device comparison: PASS
- `git diff --check`: PASS, with line-ending warnings only

## Known remaining issue

- Production integration of the optimized Android ARM configuration is not yet decided.
- Normal development Debug should remain development-oriented.
- Native failures still use the existing exception channel.
- `ERROR` exists in the termination model, but completed metrics are unavailable when JNI throws before returning.
- These issues do not block experiment closeout.

## Next session

Do not start this work as part of this checkpoint:

1. Read `AGENTS.md`.
2. Read this checkpoint.
3. Inspect the current working tree before editing.
4. Decide how optimized ARM inference should become the real SMOOTH production/runtime configuration without damaging normal Debug development.
5. Perform only the focused hardening needed for generic future GGUF compatibility.
6. Close, stage, or commit M06 and runtime work only with explicit user approval.
7. Only after that, begin Artemis architecture study and M07 planning.

Future compatibility principle: a newly introduced GGUF model should ideally follow `load -> inspect model/template/capabilities -> apply model-owned template -> controlled command inference -> termination -> unload` without adding a model-name-specific formatting branch.
