package com.theemrock.smooth.bridge;

import com.theemrock.smooth.bridge.ISmoothCallback;

/** Payloads are always JSON strings; this signature must stay stable. */
interface ISmoothBridge {
    /** Capabilities as JSON; contains "bridgeVersion". */
    String describe();

    /** Starts a request and returns its requestId. Results and errors arrive on [cb]. */
    String request(String json, ISmoothCallback cb);

    /** Cancels a queued or running request. */
    boolean cancel(String requestId);
}
