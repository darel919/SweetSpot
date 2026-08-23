package com.darelisme.sweetspot

object Config {
    /** LAN port the embedded control server listens on. */
    const val WEB_PORT = 8080

    /** Hosted relay WebSocket endpoint (sweetspot-web on Vercel). */
    const val RELAY_URL = "wss://sweetspot.darelisme.my.id/api/ws"

    /** Hosted dashboard base URL used inside the pairing QR code. */
    const val DASHBOARD_URL = "https://sweetspot.darelisme.my.id"
}
