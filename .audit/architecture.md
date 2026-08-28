# Direct transport architecture

## Chosen shape

The TV and browser use one authenticated WebRTC peer connection with two ordered, reliable DataChannels.

- `control` carries protocol envelopes, requests, replies, state, cancellation, and diagnostics.
- `capture` carries bounded binary capture-stream frames.
- The browser and TV share the envelope and capture-stream semantics.
- The Cloudflare Durable Object accepts short-lived signaling WebSockets only.
- The TV keeps a lightweight signaling registration while it has an unpaired rendezvous. It creates the native WebRTC session only after a client offer arrives.
- Pairing has a display code, a random rendezvous ID, and a random pair secret. The pair secret authenticates signaling and does not define an established peer lifetime.
- A transport generation ID fences control messages and capture frames from old peers.
- The TV writes chunks to a bounded partial file, verifies the final count and SHA-256, then hands the finalized capture to `CalibrationEngine`.

## Rejected shapes

HTTP polling for every signaling exchange would add retry state and wake-up traffic to the TV without improving the local-first data path. Short-lived signaling WebSockets keep the bootstrap exchange small and support trickle ICE and ICE restart without relaying application traffic.

A single DataChannel would allow a large capture transfer to delay cancellation. Separate channels make control priority structural.

Keeping the old room mailbox beside WebRTC would leave two production contracts and preserve the coupling that this migration removes. The relay adapter is removed after direct transport callers are migrated.

## Proof obligations

Source tests must prove bounded capture framing, integrity rejection, duplicate idempotency, generation fencing, capability negotiation, and control responsiveness under capture pressure. Real-device tests must prove direct connection, reconnect, browser reload, service restart, Cloudflare loss after DataChannel open, and return to the idle resource baseline.
