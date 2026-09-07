# Auto-Update Check Implementation

This document describes how to implement automatic app-update checking in Shevery using a **multi-model consensus** process. The goal is to wire the existing update checker so it runs automatically when the app starts or when the device boots, without requiring manual user action.

## Current state

- `SheveryUpdateChecker` already exists and can check for updates against GitHub Releases.
- `SheveryAppUpdateResult` is returned by the checker and already contains all metadata needed to display update state.
- `ModuleSettings` already has the storage keys and getters/setters for:
  - `isAppUpdateAutoCheckEnabled()`
  - `getAppUpdateFrequency()`
  - `getAppUpdateChannel()`
- The UI is already in place in `AppUpdateSettingsGroup.kt` (top-level Settings group).
- The module-only UI has already been stripped from `UpdateSettingsScreen.kt`.

## Design constraints

- Do not add credentials or secrets.
- Preserve the existing preference keys and defaults.
- Keep the update check lightweight and non-blocking.
- Use the existing `RateLimitTracker` to avoid hammering GitHub's API.
- Do not break the module update flow or the About-screen shortcut.

## Implementation plan (multi-model approved)

1. **Wire auto-check into the app lifecycle**
   - Run the update check when the app starts (in `ShizukuApplication.onCreate()`) if auto-check is enabled and the last check was outside the configured frequency window.
   - Run the update check when the device boots (in `BootCompleteReceiver`) if auto-check is enabled and the last check was outside the configured frequency window.

2. **Use the existing frequency settings**
   - `MANUAL` → never auto-check.
   - `DAILY` → check once per day.
   - `WEEKLY` → check once per week.

3. **Trigger the check from the right places**
   - App start: `ShizukuApplication.onCreate()`
   - Boot: `BootCompleteReceiver.onReceive()` after the existing permission checks

4. **Store the result for the UI**
   - Save `last_check_time` and `last_result` using `ModuleSettings`.
   - Surface the result in `AppUpdateSettingsGroup.kt` as a row showing the latest update status.

5. **Respect rate limits and network availability**
   - Use `SheveryUpdateChecker`'s existing `RateLimitTracker` to prevent excessive API calls.
   - If the device is offline, skip the check without breaking the app.

## What the models agreed on

This implementation plan was reviewed and approved by all reachable models:
- **Kimi K3** (NVIDIA direct) — **APPROVE**
- **MiniMax M3** (NVIDIA direct) — **APPROVE**
- **Gemini Flash** — **APPROVE**
- **Gemini Pro** — **APPROVE**
- **GPT-5.6 Luna** — **APPROVE**

All models agreed on the same structural approach:
- Keep the update checker separate from module settings.
- Run it at app start and boot.
- Use the existing frequency and manual settings to control timing.

## Where the code lives

- Update checker: `manager/src/main/java/moe/shizuku/manager/module/update/SheveryUpdateChecker.kt`
- Settings UI: `manager/src/main/java/moe/shizuku/manager/module/update/AppUpdateSettingsGroup.kt`
- App startup hook: `manager/src/main/java/moe/shizuku/manager/ShizukuApplication.kt`
- Boot hook: `manager/src/main/java/moe/shizuku/manager/receiver/BootCompleteReceiver.kt`

## What remains after this step

- The actual code changes for auto-check wiring are implemented in the next step (separate PR or follow-up), once the models have approved the plan and the user has had a chance to test the current restructure.
