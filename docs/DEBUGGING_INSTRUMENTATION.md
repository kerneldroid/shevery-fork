# Debugging instrumentation: surfacing worker failures on-device

## What this is

When the wireless-ADB auto-start worker (`AdbStartWorker`) fails, the app shows
"Waiting to retry" — with no way to learn **why**, unless you have a PC to
run `adb logcat`. This note documents the pattern used to diagnose the
stale-port / `ForegroundServiceStartNotAllowedException` bug (PR #159(: a short-lived,
**temporary** channel that appends the exception class + message to the retry
notification, so a tester can read the real failure straight off the phone.

It was removed before shipping because raw exception text is not user-facing
content — it leaks internals into a notification every user sees. Re-add it
**only while debugging**, then strip it again before merging.

## The pattern (three touchpoints(

### 1. `ShizukuReceiverStarter.updateNotification` — accept optional detail

`manager/src/main/java/moe/shizuku/manager/receiver/ShizukuReceiverStarter.kt`:

```kotlin
fun updateNotification(context: Context, state: WorkerState, detail: String? = null) {
    ...
    val base = if (msgId != null) context.getString(msgId) else null
    val msg = when {
        detail.isNullOrBlank() -> base
        base == null -> detail
        else -> "$base — $detail"
    }
    nm.notify(NOTIFICATION_ID, buildNotification(context, msg))
}
```

### 2. `AdbStartWorker` — pass the exception at the retry site

`manager/src/main/java/moe/shizuku/manager/worker/AdbStartWorker.kt`, in the
`catch (e: Exception)` block (currently just a pointer comment(:

```kotlin
ShizukuReceiverStarter.updateNotification(
    applicationContext,
    ShizukuReceiverStarter.WorkerState.AWAITING_RETRY,
    (e::class.java.simpleName + ": " + (e.message ?: "no message")).take(90)
)
```

`.take(90)` caps the notification text so it never overflows the banner.

###3. Remove before merging

Both edits are user-facing: revert step 1's signature and message merge,and
step 2's third argument, before the next release. Keep the one-line pointer
comment at the retry site so the next debugger knows the pattern exists.



## Why notifications, not Log

- The tester may not have a PC (`adb logcat` unavailable( — the notification is the
  only channel. Still add a `Log.w(TAG, ...)` at the catch site for devs who
  do have logcat.

- This is an **observer**, not a fix: it tells you what's failing,andometimes
  *why* (e.g. `ForegroundServiceStartNotAllowedException` pointed straight at
   the background-start ban(. It never changes behavior on its own.