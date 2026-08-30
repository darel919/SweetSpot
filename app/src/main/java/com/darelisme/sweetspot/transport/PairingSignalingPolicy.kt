package com.darelisme.sweetspot.transport

/**
 * Signaling is needed for visible pairing or an in-progress recovery, but not
 * by an idle service or an already authenticated direct peer.
 */
internal fun shouldKeepSignaling(
    serviceRunning: Boolean,
    pairingVisible: Boolean,
    directPeer: Boolean,
    recoveryPending: Boolean,
): Boolean = serviceRunning && !directPeer && (pairingVisible || recoveryPending)
