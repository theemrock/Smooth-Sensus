# SMOOTH Conversation Architecture — 2026-09-30

## Core ownership

SMOOTH owns conversation history independently of the active local LLM. Conversation archive, durable memory or instructions, and the bounded context for one inference are distinct concepts. A model may be unloaded, replaced, or changed without losing SMOOTH conversation history.

## Chat and voice

Chat and future voice activation are input/output adapters over the same conversation system. The intended voice flow is `wake/STT -> USER message -> shared SMOOTH agent/inference pipeline -> ASSISTANT message -> optional TTS`. Voice must not require opening Chat. Voice interactions should later appear in Chat like ordinary messages, with TEXT or VOICE metadata where useful.

## Message ownership and deletion

The user may delete any individual USER or ASSISTANT message and may delete an entire conversation. Deleting one message must not cascade-delete later messages. Conversation content is user-owned even when deletion makes visible causal history imperfect.

Deleted content must not remain available to future retrieval or context construction. Future durable memory and instructions need source provenance so derived state can be removed or reevaluated when its source message is deleted.

## Reply, edit, fork, and delete

These are separate operations:

- DELETE removes one selected message without removing later messages.
- REPLY continues the current conversation while explicitly referring to an older message. The future message model supports nullable `replyToMessageId`; a future Context Builder must include that target even when it falls outside the recent-message window.
- EDIT is separate from deletion.
- FORK may create an alternate continuation from an earlier point.

Do not conflate these operations. M07-A stores `replyToMessageId` but provides no Reply UI or behavior.

## Instructions

A durable instruction may begin as an ordinary message. When SMOOTH determines that the user intended persistence, it may move that message into a special Instructions conversation or store. A move should not duplicate the instruction in ordinary history.

Instruction detection belongs to SMOOTH, not to a particular GGUF. Uncertain instructions must not be silently persisted; an explicit ambiguity policy is future work. Instructions are durable behavioral state supplied according to scope and policy, not ordinary historical retrieval. M07-A does not implement instruction detection.

## Archive, memory, and context

- ARCHIVE is persistent conversation history.
- MEMORY is future distilled durable information with provenance.
- CONTEXT is the bounded information assembled for one inference.

The entire archive must not be sent on every inference. Retrieval and memory policy remain future work.

## M07-A boundary

M07-A provides one persisted default conversation, stable message and conversation IDs, USER or ASSISTANT role, content, timestamp, TEXT or VOICE input mode, nullable reply linkage, manual USER text entry, and independent message deletion. It does not connect Chat to an LLM or create fake assistant replies.

## Open Local Model Test item

Local Model Test needs a user-adjustable `maxTokens` control in a later task: a numeric input plus slider, a reasonable bounded range such as 16–512, and an initial/default value of 64. This is outside M07-A.

## Future compatibility principle

Chat and voice must use the same persisted conversation and inference pipeline. Model lifecycle, archive lifecycle, durable behavioral state, retrieval, and one-inference context construction remain independently controlled.

## M07-A implementation checkpoint

Implemented in the working tree:

- Home has a real Chat entry and a saveable `CHAT` destination.
- Chat accepts manual USER text messages and never creates fake assistant replies.
- Room schema version 3 adds conversations and independently deletable messages with stable IDs, role, content, timestamp, TEXT/VOICE mode, and nullable `replyToMessageId`.
- Migration `MIGRATION_2_3`, repository, ViewModel, dark Compose screen, and individual deletion are present.
- Focused navigation and Chat ViewModel unit tests pass.
- The complete Debug unit suite and `assembleDebug` pass.
- Android test APK compilation passes.
- Device instrumentation passes for migrations, Room close/reopen persistence with independent deletion, and Home entries: 4 tests, 0 failures.

Device UI smoke status:

- User-completed manual smoke: PASS. Chat opened, a message was sent, it survived leaving/reopening Chat and closing/reopening the app, individual deletion succeeded, and the deleted message remained absent after reopening.
- The earlier automated `PERSISTED=False` observation was not a demonstrated product failure. Manual verification and the real Room close/reopen test confirm persistence.
- No app data was wiped and no GGUF file was altered.

## M07-B architecture and implementation

- Conversation selection is backed by stable Room conversation IDs. The left phone drawer lists conversations by recent activity, creates a new conversation, switches selection, highlights the active item, and exposes conversation deletion by long press.
- Titles are deterministic: the first USER message truncated to 40 characters, or `Yeni sohbet` while empty.
- USER and ASSISTANT messages use right/left natural-width bubbles. The composer remains at the bottom and the message list scrolls.
- Message long press exposes `Yanıtla`, `Kopyala`, `Düzenle`, `Fork -> Yeni sohbet`, and `Sil`.
- Single-message deletion never removes later messages. Edit changes the selected row in place. Fork copies rows through and including the selected message into a new conversation with new stable IDs while leaving the original unchanged. Reply persists `replyToMessageId` in the same conversation and shows a composer preview.
- `SmoothApplication` owns the shared `LocalModelRuntime`; clearing a screen/ViewModel no longer unloads the application runtime. Local Model Test and Chat use the same runtime and inference stack.
- Native Chat generation passes the bounded role/content message sequence through the existing model-owned Jinja path. It adds no Qwen/Gemma formatting branch. Normal Chat uses generic THINK OFF and a 256-token output ceiling.
- Local Model Test now exposes a 16-512, step-16 `maxTokens` slider; its default remains 64.

Temporary Chat context policy: current conversation only, the 12 most recent messages, plus an explicitly replied-to target when that target falls outside the recent window. The current USER message is already part of this set. This is intentionally replaceable by the future Context Builder; no archive-wide injection, retrieval, Memory, instruction detection, or embeddings were added.

## M07-B verification

- Focused Chat ViewModel RED/GREEN tests: PASS. They cover multiple conversations, isolation, blank send, successful ASSISTANT persistence, inference failure without fake ASSISTANT content, edit, independent delete, fork, reply, and conversation deletion.
- Complete Debug unit suite: PASS after updating the old ViewModel-clear test to the application-owned runtime contract.
- Debug native build and `assembleDebug`: PASS.
- Android test APK build: PASS.
- S24 focused real-Room tests: PASS, 2 tests. They cover database reopen, independent deletion, multiple conversations, persisted edit/reply, fork boundary, original preservation, and reply-target inclusion outside the recent window.
- S24 UI smoke without wiping data: PASS for Home -> Chat, new conversation, USER bubble, visible no-model error without fake ASSISTANT, five-action long-press menu, Copy action selection, and independent switching between two persisted conversations.

Exact unfinished verification:

- A real ASSISTANT response was not generated during the M07-B device smoke because the freshly installed/restarted Debug process had no model loaded. The app visibly reported `Etkin yerel model yok` and persisted only the USER message, as designed.
- On a later loaded-runtime session, perform one focused Chat send and confirm that the real ASSISTANT row persists after leaving/reopening Chat. Do not interpret the missing device run as a second inference implementation; Chat already calls the shared application runtime.
- Production adoption of the optimized ARM configuration remains the separate decision recorded in the LLM runtime checkpoint. Debug remains development-oriented.

## App-wide system bars and IME follow-up

- Edge-to-edge remains one Activity-level decision. `SmoothApp` is the single `safeDrawing` inset owner for Home, Chat, Learn & Scenario, Local Model Test, and the existing nested screens.
- The nested Learn & Scenario `Scaffold` and `TopAppBar` use zero content/system insets because the app shell already consumed them; this prevents double status-bar padding.
- Chat applies IME padding inside the already-safe app content. The message list takes remaining height, while the composer stays above the navigation area with the keyboard closed and directly above the IME when open.
- Chat now uses a compact trailing send icon inside a 1-5 line composer field.
- Debug unit suite and both Debug APK builds passed. The focused S24 composer test passed.
- S24 visual/UI-tree smoke passed for Home, Chat with keyboard closed and open, and Local Model Test. No app data or GGUF files were removed.

## M07-B long-message and edit hardening

- Long USER bubbles retain natural width for short text and may grow to 88% of the available Chat width for long text. USER/ASSISTANT alignment, long press, wrapping, and `LazyColumn` scrolling remain intact.
- The cramped edit dialog was replaced by a full Chat editing surface. It uses the available safe area, gives the multiline editor the remaining height, keeps long text scrollable, and keeps `İptal`, `Kaydet`, and `Düzenle ve Gönder` accessible above the IME.
- `Kaydet` is edit-only: it updates the selected message content and preserves every earlier and later message. It does not infer, fork, regenerate, or create a conversation.
- `Düzenle ve Gönder` applies only to a USER message. It updates that row, physically deletes every later row in the same conversation, stays on the same `conversationId`, and invokes the existing Chat inference pipeline from the edited tip. Success persists the new ASSISTANT row. Failure leaves the edited USER row as the tip and creates no fabricated response; deleted downstream rows are not restored.
- Room schema version 4 adds a required conversation-local `ordinal` and a unique `(conversationId, ordinal)` index. Migration 3 -> 4 deterministically backfills the old `(timestamp, id)` display order. New messages receive `MAX(ordinal) + 1` inside the DAO transaction.
- Downstream deletion is addressed by both `conversationId` and the selected row's conversation-local `ordinal`. It never uses global message ID order or timestamp alone. `replyToMessageId` remains reply metadata only.
- The temporary normal Chat context policy is unchanged: current conversation, 12-message bound, 256 output tokens, generic THINK OFF.

Verification:

- Focused `ChatViewModelTest`: PASS, including edit-only preservation, successful and failed edit-and-send behavior, empty-content rejection, same-conversation continuation, and no fake ASSISTANT on failure.
- Debug unit suite, `assembleDebug`, and Android test APK build: PASS.
- Room migration and persistence instrumentation: PASS, 8 focused device tests total. The mandatory interleaved `A1, B1, A2, B2, A3, B3` regression proves truncating after edited A2 removes only A3; every B row and its content remain unchanged and independently readable after database reopen.
- Existing independent delete, Fork, and Reply coverage remains PASS.
- S24 smoke: PASS for broad long USER bubble sizing (measured UI bounds show approximately 88% available width), opening the long-message edit surface, and IME-open layout with both actions visible above the keyboard. The installed APK migrated and opened existing conversations without wiping app data.
- ADB coordinate automation did not produce a trustworthy content mutation for the final destructive device interaction, so `Kaydet` and `Düzenle ve Gönder` persistence semantics are recorded as automated Room/ViewModel PASS rather than claimed as manual UI PASS. No GGUF or unrelated conversation was deleted.

Unfinished work for this bounded pass: none in implementation. A future optional manual smoke may exercise both edit actions end to end with disposable messages, but it is not a correctness blocker given the focused database, ViewModel, migration, and Compose instrumentation coverage.

## UI polish pass — stopped checkpoint

The user explicitly stopped this pass before build/device verification.

Completed in the working tree:

- Added failing-first Android Compose test contracts for a single Local Model Test Thinking toggle and the About entry/content.
- The RED Android-test compilation was observed for the missing About navigation/screen, as intended.
- Added an About destination and Home entry with SMOOTH identity, version name/code from `BuildConfig`, package ID, a neutral description, and llama.cpp attribution.
- Consolidated Local Model Test model filename, file size, and readiness text into a compact header with compact `Değiştir` and `Kapat` actions.
- Consolidated the duplicate Thinking controls into one `Thinking: OFF/ON` toggle without changing runtime semantics.
- Reduced Local Model Test vertical spacing and prompt minimum height while retaining token controls, output, and metrics.

Explicitly unfinished and unverified:

- No GREEN compile/test was run after the implementation because the user ordered an immediate stop.
- Buildability of the latest UI edits is therefore UNKNOWN and must be checked first next session.
- Chat visual polish was not applied.
- Clean install was not performed.
- Fresh-state S24 smoke was not performed.
- Checkpoint/repository safety audit was not rerun after the latest edits.
- No inference smoke was attempted and no inference diagnosis was started.

Next technical investigation remains: diagnose unexpectedly slow local inference observed even with Qwen2-1.5B-Instruct Q4_K_M (approximately 940 MiB), including very low prompt-evaluation and generation throughput. This investigation was not started during the UI pass.

## Interrupted UI pass — closed, 2026-09-30

### Recovery and scope

- Read `AGENTS.md`, this checkpoint, current repository diffs, and the existing About, Home navigation, Local Model Test, Chat, and Compose test implementations before editing. The namespace migration and all earlier uncommitted domain/native work were preserved.
- Recovered the already implemented Home About destination and compact Local Model Test header/Thinking toggle. Their interrupted GREEN/device verification was completed rather than reimplementing them.
- The user reported that the recovered worktree already assembled and installed with `install -r`; this pass independently rebuilt it successfully before the new Chat work.
- Changes in this pass are UI/navigation, launcher resources, focused UI tests, the icon generation script, and this checkpoint. No inference investigation or milestone expansion was performed.

### Completed UI

- About remains a Home card and is absent from the Chat drawer. It shows SMOOTH, a neutral description, `0.0.6 (6)` from `BuildConfig`, `com.theemrock.smooth`, and the accurate llama.cpp attribution. It contains no invented licenses or live diagnostics. Its content scrolls on smaller layouts.
- Fresh-state visual QA caught missing default text contrast in the app shell. A Material `Surface` now supplies the background/content colors while retaining the existing single `safeDrawing` owner and Activity edge-to-edge handling.
- Local Model Test retains the compact filename/size/readiness header; long filenames have a two-line ellipsis bound. Available models display `Hazır`, with compact `Değiştir` and `Kapat` actions. The optional stored-copy action remains available after unload.
- Local Model Test has exactly one `Thinking: OFF/ON` toggle. Prompt, 16–512/step-16 slider, Run, Grammar Test, raw output, and every existing token/timing/rate metric remain present. Runtime/model-loading behavior is unchanged.
- Chat uses a compact 56dp-minimum header with Material menu/back icons and readable ellipsized conversation title. Existing message alignment, natural-width short USER bubbles, 88% maximum long USER bubbles, wrapping, long press, full-screen Edit, and composer/insets were preserved. Composer vertical spacing is tighter.
- Actual Send invokes the existing send/persistence callback, clears the draft, forcibly clears focus, and hides the software keyboard. Inserting a newline does not invoke Send or hide the keyboard. Controlled ASSISTANT arrival/generation-state tests confirm focus and IME do not reopen.
- A conversation-keyed `LazyListState` initially positions at the latest message, including asynchronous Room message delivery and drawer switches. New USER sends return to the tip. New responses follow only when the reader was at/near the bottom; scrolling up preserves the reading position. Viewport tracking uses laid-out visible-item positions, avoiding a stale-layout bottom calculation. Initial positioning uses an immediate scroll and briefly withholds the unpositioned list to avoid flashing oldest messages.
- The drawer has independently collapsible SOHBETLER, PROJELER, TALİMATLAR, and ARAÇLAR sections, with Material expand/collapse icons and separators only between sections. SOHBETLER defaults expanded; collapse state is transient Compose state.
- Existing new/select/delete conversation actions remain functional. `Gizli Sohbetler`, `Yeni proje`, `Genel`, and `Proje talimatları` are disabled `Yakında` placeholders. No hidden/project/instruction state or persistence was added.
- ARAÇLAR opens the real shared-runtime Local Model Test screen as `Yerel Model`, with Back returning to Chat. About is not a drawer tool.

### Launcher

- Used only the approved `C:\Users\<USER>\Downloads\smooth.png`. Source SHA-256: `78B0333F4E1DDDB10C6B6A716B77039A97A1081049EF355C9F4276391A32D81F`.
- `scripts/generate-launcher-icons.ps1` mechanically scales/pads the unchanged square artwork. The adaptive foreground places a 46dp square inside the 66dp safe circle on a neutral dark background, preserving the complete artwork under common masks.
- Replaced the adaptive/round XML references and all five-density legacy placeholder assets with the SMOOTH artwork. Removed unused green-head vector resources. The generated legacy PNG sizes are 48, 72, 96, 144, and 192px.
- S24 launcher screenshot confirms the SMOOTH artwork survives the installed launcher mask and opens the correct app. A separately installed old `SMOOTH-Learn-Scenario` app still has its own old icon; that unrelated package was left alone.
- After the final clean installation, the user also completed the manual launcher appearance check and confirmed `düzgün` (correct). Final launcher smoke: PASS.
- Themed/monochrome artwork is explicitly unfinished. The old placeholder monochrome reference was removed; no thresholded or invented replacement was supplied.

### GREEN and device smoke

- New Chat presentation contracts were run RED against the recovered implementation: missing drawer sections, initial/end scroll behavior, and Send focus clearing failed as expected.
- `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`: PASS. Complete Debug unit suite: **144 tests, 0 failures, 0 errors**. Debug app and Android test APK builds succeeded.
- S24 focused instrumentation: **18 tests, 0 failures** across Chat presentation/composer, Home/About, Local Model Test, real Room conversation persistence, and database migrations. Log: `app/build/ui-smoke/focused-tests-final.log`.
- A final four-test Chat presentation rerun after explicitly making the focus/IME arrival fixture an ASSISTANT also passed: **4 tests, 0 failures**. Log: `app/build/ui-smoke/assistant-ime-final.log`.
- Coverage preserves Room reopen, ordinal migration/interleaved A/B truncation isolation, Edit + Save, successful/failed Edit + Send, Reply, Fork, independent Delete, current-conversation context policy, and application-owned shared LocalModelRuntime contracts.
- Device tests caught and resolved the new FlowRow runtime signature incompatibility by retaining the established Row layout. They also caught and resolved the stale viewport bottom calculation. No dependency or runtime behavior change was used to fix either issue.
- After GREEN, intentionally uninstalled only `com.theemrock.smooth` and freshly installed `app/build/outputs/apk/debug/app-debug.apk`. The final product APK was clean-installed again after the visual contrast fix passed verification. Both uninstall/install operations reported `Success`. Old SMOOTH app-private data was intentionally removed; external source files were not deleted.
- Fresh Home/About smoke: PASS for launch, system-bar safe areas, visible Home About card, opening/back, correct identity/version/package/attribution, and readable layout.
- Fresh Chat smoke: PASS for drawer/open/collapse/expand, disabled coming-soon scaffolding, no About entry, multiple disposable conversations, switch/reopen at `A_LATEST`, natural short and 88%-width long USER messages, long press/full-screen Edit, and keyboard/insets. The long-message editor's Save/Edit-and-Send controls remained above the open IME; the device smoke canceled the edit rather than changing domain data.
- Real Samsung keyboard check: Enter inserted a newline with `mInputShown=true`; actual Send persisted the USER content, cleared the field/focus, and produced `mInputShown=false`. The no-model Chat smoke used the existing explicit error without a fake ASSISTANT. ASSISTANT arrival without focus/IME theft was verified with controlled Compose state, not a native generation run.
- Local Model Test smoke: PASS for empty state and file picker, subsequently imported/ready `gemma-4-E2B_q4_0-it.gguf` with `3.12 GiB · Hazır`, compact actions, one Thinking toggle (OFF → ON → OFF), slider changing 64 → 432 in step-16 values and returning to 64, prompt IME/insets, Change picker/cancel, Unload retaining the stored copy, and stored-copy reload reaching Hazır. No Run/Grammar inference or benchmark was initiated.
- Screenshots/UI trees are local, ignored verification artifacts under `app/build/ui-smoke/`, including `fresh-about-final`, `drawer-expanded`, `switched-a-tip`, `local-model-ime-final`, `saved-copy-ready`, and `launcher`. The `launcher` screenshot is the verified installed-icon evidence; a later automatic Home-to-drawer gesture did not create a trustworthy launcher view, so `launcher-final` is not used as icon evidence.
- `/sdcard/Download/Local Diff/qwen2-1_5b-instruct-q4_k_m.gguf` remained present at **986045824 bytes** before and after clean installation. Other external GGUF sources and unrelated apps/files were not modified.

### Repository safety and exact remaining work

- Final `git status --short`, `git diff --check`, staged-file inspection, llama.cpp worktree/HEAD, and parent gitlink inspection were performed. Staging is empty; diff checking reports no whitespace errors (only existing LF/CRLF notices).
- Hash comparison against the recovery snapshot confirms `.superpowers/`, `app/src/main/cpp/`, inference/runtime files, Room/data files, conversation files, and ChatViewModel are byte-for-byte unchanged in this pass.
- llama.cpp worktree remains clean at `9f70b2cecd1a9a3f73ac525c47ca22a6ee9a7b69`; the parent gitlink matches that SHA.
- No stage, commit, push, reset, rebase, or amend was performed. Existing uncommitted work remains intact.
- This bounded UI-completion pass is closed. Only themed/monochrome launcher artwork is explicitly unfinished. A real native ASSISTANT generation was deliberately not performed; controlled UI arrival behavior and existing inference-gateway/domain contracts were tested.
- No Projects, Knowledge Base/RAG, Instructions, Hidden Chat, slash commands, web search, Memory, Voice, Action Execution, M08, M34 benchmarking, or multi-turn model testing was started.

### Deferred next technical task — do not start within this pass

Diagnose the severe local-inference performance anomaly in a separate bounded task. The user's prior controlled Qwen2-1.5B-Instruct Q4_K_M (~940 MiB) S24 observation was approximately: prompt 21 tokens; prompt evaluation 33.85s / 0.62 tok/s; TTFT 33.87s; generation 11 tokens / 57.56s / 0.19 tok/s; total 91s. The lighter model did not improve the observed performance. These are prior observations, not measurements from this UI pass. No diagnosis, native/llama.cpp change, model-loading change, or performance experiment was undertaken here.

## Chat UI polish Pass A — 2026-10-01

### Implemented presentation and UI state

- Messages now receive locale-aware calendar-day separators and `HH:mm` metadata from their existing persisted timestamps. USER and ASSISTANT timestamps are not fabricated or copied into new domain fields.
- Replaced `Yanıt hazırlanıyor...` with a compact live elapsed timer. It begins synchronously when Send/Edit-and-Send accepts a request, uses a monotonic clock, includes persistence/context/TTFT/generation as perceived by the user, and stops on success, failure, or cancellation.
- Successful ASSISTANT messages show the measured raw duration alongside their time (for example `08:42 · 2,9 sn`). Raw milliseconds are held only in `ChatViewModel` UI state and keyed by the created ASSISTANT message ID. No formatted duration string is stored.
- Duration persistence is intentionally deferred: durations survive normal navigation while the same ViewModel lives, but disappear after process/ViewModel recreation. Adding durable response duration would require an explicitly approved Room schema/domain migration, which Pass A forbids.
- Pending Reply now has a compact sender/content preview immediately above the composer and an independent cancel action. Sent reply bubbles resolve `replyToMessageId` against the loaded conversation and display a compact quoted preview. Missing targets are ignored safely. Optional tap-to-scroll-to-original was deferred because it would add list-index/navigation complexity without changing required Reply visibility.
- ASSISTANT content uses a Compose-native, model-independent Markdown renderer for headings, bold, italic, bullet lists, numbered lists, inline code, and fenced code blocks. USER rendering remains plain text. No WebView or new inference/model special case was added; tables remain outside Pass A.
- The app shell remains the sole `safeDrawing` owner. Chat no longer applies IME padding to the whole screen; only the composer consumes IME padding, keeping the top bar stable while the list resizes. A deliberate fast upward fling while IME is visible clears focus/hides the keyboard; small scrolls do not.
- The message list and composer remain separate Column children. The list's effective height accounts for the composer, pending Reply preview, error row, live response timer, and IME, with bottom content padding so the final message can be fully exposed.
- Each conversation keeps an in-memory `LazyListState`. Switching A → B → A restores A's prior viewport. First-open conversations still position at the tip, explicit sends expose the new USER row, and ASSISTANT arrivals follow only when the reader was already at/near the bottom.
- Chat title presentation is constrained to one ellipsized line with weighted width. Send is visibly disabled while the shared runtime is busy; no queue, parallel generation, lifecycle state machine, or scheduler was introduced.

### Semantics and boundaries preserved

- Reply persistence/context injection, 12-message policy, Edit, Edit-and-Send, Delete, Fork, conversation-local ordinal, Room persistence, normal Chat 256-token output, THINK OFF, and the application-scoped `LocalModelRuntime` are unchanged.
- No Room schema/migration/entity change was made. No native, JNI, llama.cpp, model, runtime, prompt/template, context-policy, Home/About, drawer scaffolding, or Local Model Test behavior was changed.
- The existing native O3 experiment state predates this pass and was neither altered nor expanded. Pass A did not start Pass B, inference tuning, M08, M34 work, Projects, RAG, Hidden Chat, web search, Voice, Memory, or Action Execution.

### Verification

- Failing-first focused contracts were added for day grouping/time and duration formatting, Markdown parsing, timer start/freeze, Reply cancellation, sent Reply quote, active-generation Send state, long-content/composer exposure, IME fast-fling dismissal, and A → B → A viewport restoration.
- Complete Debug unit suite: **149 tests, 0 failures, 0 errors**.
- `assembleDebug` and `assembleDebugAndroidTest`: PASS.
- S24 focused Chat Compose suite: **11 tests, 0 failures** after correcting two ambiguous test selectors that legitimately matched both the original and quoted message.
- S24 complete connected Debug suite: **27 tests, 0 failures, 0 errors, 3 skipped**. The three skips are the existing real-model inference tests without their optional model-path argument; Chat UI, Home, Local Model Test, Room persistence, ordinal/migration, Reply/Fork/Delete/Edit, and available regressions passed.
- No clean install or data wipe was performed. Gradle's connected-test deployment preserved the bounded Pass A intent; no GGUF file was copied, deleted, or benchmarked.

### Deferred manual checks

- Perform a brief human visual pass on S24 with the Samsung keyboard: confirm no perceptible top-bar jump, inspect Markdown/code-block typography, verify reply-preview ellipsis, and observe a real generation timer through ASSISTANT completion. Automated Compose coverage verifies the state/layout contracts but cannot judge animation smoothness or typography quality.
- M34 installation/validation was requested as a later activity and was not started inside this bounded S24 Pass A.
