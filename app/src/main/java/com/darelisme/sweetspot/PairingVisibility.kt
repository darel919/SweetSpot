package com.darelisme.sweetspot

internal fun shouldShowPairingQr(pairCode: String?, relayState: String): Boolean =
    !pairCode.isNullOrBlank() && relayState == OverlayController.RELAY_WAITING
