package com.kin.familyhealth.call

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

// AGENT-CALL fills this
class CallAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }
}
