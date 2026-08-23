package com.darelisme.sweetspot

object Config {
    /** LAN port the embedded control server listens on. */
    const val WEB_PORT = 8080

    /** Mailbox API base (Cloudflare Worker). */
    const val MAILBOX_URL = "https://sweetspot.darelisme.my.id"

    /** Hosted dashboard base URL shown in QR fallback text. */
    const val DASHBOARD_URL = "https://sweetspot.darelisme.my.id"
}
