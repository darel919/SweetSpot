package com.darelisme.sweetspot

internal fun shouldShowPairingQr(pairCode: String?, connectionState: String): Boolean =
    !pairCode.isNullOrBlank() && connectionState == OverlayController.CONNECTION_WAITING
