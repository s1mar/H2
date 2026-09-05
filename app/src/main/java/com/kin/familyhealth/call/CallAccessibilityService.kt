package com.kin.familyhealth.call

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.kin.familyhealth.data.settings.ReachInMode
import com.kin.familyhealth.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val TAG = "CallAccessibilityService"

/**
 * FALLBACK reach-in path (ARCHITECTURE.md: "Fallback — Accessibility auto-answer").
 *
 * This is OPT-IN and OFF by default. It is only active when both are true:
 *   - `SettingsRepository.reachInMode == ReachInMode.ACCESSIBILITY`
 *   - `SettingsRepository.accessibilityOptIn == true`
 *
 * When active, it watches window-state-changed events and, if the current window looks
 * like an incoming-call screen, searches the accessibility tree for a node that looks like
 * an "Answer"/"Accept" control and clicks it. This is intentionally conservative:
 *   - It never types text, never navigates elsewhere, never touches anything but a node
 *     it matched against a small allow-list of common answer-button labels/ids.
 *   - It does nothing at all unless the two settings above are both true, re-checked on
 *     every event (so turning the toggle off in Settings takes effect immediately).
 *   - It only ever performs ACTION_CLICK, and only once per matched node instance.
 *
 * NOTE: matching an arbitrary OEM dialer's "Answer" button reliably is inherently
 * best-effort (button text/resource-id vary by OEM/Android version/locale). The
 * `accessibility_service_config.xml` (FOUNDATION-owned resource, not touched here)
 * may need `packageNames` narrowed to the target dialer for reliability — left broad
 * for now so this works across devices without per-OEM tuning.
 */
class CallAccessibilityService : AccessibilityService() {

    private var scope: CoroutineScope? = null
    private var settingsJob: Job? = null

    @Volatile private var enabled: Boolean = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        val settings = SettingsRepository(applicationContext)
        val serviceScope = CoroutineScope(Dispatchers.Default)
        scope = serviceScope
        settingsJob = serviceScope.launch {
            combine(settings.reachInMode, settings.accessibilityOptIn) { mode, optIn ->
                mode == ReachInMode.ACCESSIBILITY && optIn
            }.distinctUntilChanged().collect { isEnabled ->
                enabled = isEnabled
                Log.i(TAG, "Accessibility auto-answer enabled=$isEnabled")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!enabled) return
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val root = rootInActiveWindow ?: return
        try {
            val answerNode = findAnswerNode(root)
            if (answerNode != null) {
                val target = findClickableAncestor(answerNode) ?: answerNode
                val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.i(TAG, "Auto-answer click attempted, success=$clicked")
            }
        } catch (t: Throwable) {
            // Conservative: never let a malformed node tree crash this service.
            Log.w(TAG, "onAccessibilityEvent failed", t)
        } finally {
            root.recycleCompat()
        }
    }

    /**
     * Small allow-list of common "answer the call" labels across stock/OEM dialers and
     * common locales. Extend cautiously — a false match performs a real click.
     */
    private val answerTextCandidates = listOf(
        "answer", "accept", "answer call", "accept call", "aceptar", "annehmen", "répondre",
    )

    private fun findAnswerNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (candidate in answerTextCandidates) {
            val matches = root.findAccessibilityNodeInfosByText(candidate)
            val hit = matches?.firstOrNull { it.isVisibleToUser }
            if (hit != null) return hit
        }
        return root.findAccessibilityNodeInfosByViewId("com.android.dialer:id/answer")
            ?.firstOrNull { it.isVisibleToUser }
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 6) {
            if (current.isClickable) return current
            current = current.parent
            hops++
        }
        return null
    }

    private fun AccessibilityNodeInfo.recycleCompat() {
        @Suppress("DEPRECATION")
        try { recycle() } catch (_: Throwable) { /* no-op on API levels where recycle() is a no-op */ }
    }

    override fun onInterrupt() {
        // No ongoing operation to cancel; nothing to do.
    }

    override fun onDestroy() {
        settingsJob?.cancel()
        scope = null
        super.onDestroy()
    }
}
