package moe.shizuku.manager.utils

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.application
import rikka.shizuku.Shizuku

/**
 * Centralized state machine for the Shizuku server lifecycle.
 *
 * Replaces scattered pingBinder() checks with a single source of truth.
 * State transitions are driven by binder received/dead listeners and explicit
 * set() calls. UI can observe via [asFlow] for reactive state updates.
 *
 * States:
 * - STARTING: Server is being launched (root, ADB, or Dhizuku)
 * - RUNNING: Binder is alive (pingBinder() returns true)
 * - STOPPING: Stop has been requested, waiting for binder to die
 * - STOPPED: Binder is dead, stop was expected (or initial state)
 * - CRASHED: Binder died unexpectedly (not from an explicit stop)
 */
object ShizukuStateMachine {

    enum class State { STARTING, RUNNING, STOPPING, STOPPED, CRASHED }

    private val TAG = AppConstants.TAG

    private val state = AtomicReference<State>(State.STOPPED)
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    init {
        Shizuku.addBinderReceivedListenerSticky(
            Shizuku.OnBinderReceivedListener { set(State.RUNNING) }
        )
        Shizuku.addBinderDeadListener(
            Shizuku.OnBinderDeadListener { setDead() }
        )
    }

    fun get(): State = state.get()

    private fun transitionAtomic(transform: (State) -> State) {
        var oldState: State
        var newState: State
        do {
            oldState = state.get()
            newState = transform(oldState)
            if (oldState == newState) return
        } while (!state.compareAndSet(oldState, newState))

        Log.d(TAG, "ShizukuStateMachine: $oldState -> $newState")
        java.util.ArrayList<(State) -> Unit>().apply {
            listeners.forEach { add(it) }
        }.forEach { it(newState) }
    }

    fun set(newState: State) = transitionAtomic { newState }

    /**
     * Called when the binder dies. If the server was RUNNING, it crashed.
     * If it was STOPPING (explicit stop), optionally auto-disable USB debugging
     * and transition to STOPPED.
     */
    fun setDead() = transitionAtomic {
        when (it) {
            State.RUNNING -> State.CRASHED
            State.STOPPING -> {
                try {
                    val appContext = application.applicationContext
                    val permissionGranted = appContext
                        .checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
                    val shouldDisableUsbDebugging = permissionGranted &&
                        ShizukuSettings.getAutoDisableUsbDebugging()
                    if (shouldDisableUsbDebugging) {
                        Settings.Global.putInt(
                            appContext.contentResolver,
                            Settings.Global.ADB_ENABLED, 0
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to auto-disable USB debugging on stop", e)
                }
                State.STOPPED
            }
            else -> it
        }
    }

    /**
     * Force a state refresh by pinging the binder.
     * Returns the current state after refresh.
     */
    fun update(): State {
        val currentState = if (Shizuku.pingBinder()) State.RUNNING else State.STOPPED
        set(currentState)
        return currentState
    }

    fun isRunning(): Boolean = get() == State.RUNNING

    fun isDead(): Boolean = get() == State.STOPPED || get() == State.CRASHED

    fun addListener(listener: (State) -> Unit) {
        listeners.add(listener)
        listener(state.get())
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Reactive state observation for Compose UI.
     * Emits the current state immediately on collection, then on every transition.
     */
    fun asFlow(): Flow<State> = callbackFlow {
        val listener: (State) -> Unit = { trySend(it).isSuccess }
        addListener(listener)
        awaitClose { removeListener(listener) }
    }
}
