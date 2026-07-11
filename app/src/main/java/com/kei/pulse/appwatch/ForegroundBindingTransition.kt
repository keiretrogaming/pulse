package com.kei.pulse.appwatch

import com.kei.pulse.model.PerAppConfig
import com.kei.pulse.model.PerAppRestoreState

/** Pure plan for one confirmed foreground-package transition. Device I/O is executed by the service. */
internal data class ForegroundBindingTransition(
    val kind: Kind,
    val flushOutgoingDraw: Boolean = false,
    val restoreFanMode: Int? = null,
    val restoreRefreshRateHz: Int? = null,
    val restartSessionClock: Boolean = false,
    val resetOverlayAverages: Boolean = false,
) {
    enum class Kind { HOLD, REBIND, RELEASE }
}

internal fun planForegroundBindingTransition(
    currentPackage: String?,
    nextPackage: String,
    nextConfig: PerAppConfig?,
    originalState: PerAppRestoreState?,
    engages: Boolean,
    force: Boolean,
): ForegroundBindingTransition {
    val rebind = engages && (currentPackage != nextPackage || force)
    val release = !engages && currentPackage != null
    val packageChanged = currentPackage != nextPackage
    val incomingAutoTdp = nextConfig == null || PerAppConfig.isAuto(nextConfig.profileBinding)
    return when {
        rebind -> ForegroundBindingTransition(
            kind = ForegroundBindingTransition.Kind.REBIND,
            // Flush before boundConfig is replaced, including a forced same-app rebind from Quick Access.
            flushOutgoingDraw = currentPackage != null,
            // AutoTDP owns both controls. Other bindings inherit the pre-chain baseline for any control they
            // leave unspecified, preventing the previous app's override from leaking across a direct switch.
            restoreFanMode = if (currentPackage != null && !incomingAutoTdp && nextConfig?.fanMode == null) {
                originalState?.fanMode
            } else {
                null
            },
            restoreRefreshRateHz =
                if (currentPackage != null && !incomingAutoTdp && nextConfig?.refreshRateHz == null) {
                    originalState?.refreshRateHz
                } else {
                    null
                },
            // A force-rebind of the same package changes its settings, not its session identity.
            restartSessionClock = packageChanged,
            resetOverlayAverages = packageChanged,
        )
        release -> ForegroundBindingTransition(
            kind = ForegroundBindingTransition.Kind.RELEASE,
            flushOutgoingDraw = true,
        )
        else -> ForegroundBindingTransition(ForegroundBindingTransition.Kind.HOLD)
    }
}
