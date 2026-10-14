# Smooth Dialogue Working Rules

- Product name: Smooth Dialogue. The package, namespace, and application ID `com.theemrock.smooth.dialogue` are the primary app identity; the repository folder is `SmoothDialogue`.
- Smooth Dialogue is a member of the broader SMOOTH product family. Learn & Scenario remains a module inside this app, not a separate product.
- Preserve milestone boundaries. Do not create empty future modules, screens, or abstractions, and do not rewrite working code merely for architectural cleanliness.
- AI-suggested capabilities are never automatically `TECHNICALLY_VERIFIED`.
- Normalization does not select providers. The resolver does not interpret natural language.
- The parser is the structural trust boundary. The resolver is the provider and execution trust boundary.
- Keep the LLM runtime independent of Activity and Compose lifecycles. The model file may persist while the native runtime remains disposable and reloadable. One active native model is the current baseline.
- Prefer model-owned GGUF chat templates and generic, capability-driven integration. Do not add model-name conditionals such as Qwen or Gemma-specific formatting.
- Unsupported templates or capabilities must fail explicitly instead of silently falling back.
- Keep reasoning control generic. Short command inference should normally use THINK OFF.
- Keep the development Debug configuration usable for development. Do not blindly turn Debug into the optimized production inference configuration.
- On Windows, prefer one persistent PowerShell session, use `Clear-Host` before major phases, and avoid unnecessary terminal windows.
- Work autonomously on safe, reversible diagnostics. Do not repeatedly ask for permission, and do not treat silence as approval.
- Protect existing uncommitted work. Never stage, commit, reset, rebase, amend, or push without explicit instruction.
- Never include `.superpowers/` in product commits. Do not update the llama.cpp submodule opportunistically.
- Distinguish what is technically possible from what is practically worthwhile.
- At the end of every Codex task, after all work and verification is complete and immediately before the written final response, execute these exact PowerShell commands in the existing terminal session:

  ```powershell
  Write-Host ""
  Write-Host "========== REPORT ===========" -ForegroundColor Green
  Write-Host ""
  ```

  The terminal must emit this banner as the final terminal output. Reproducing it only in the written response does not satisfy the rule.
