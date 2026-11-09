package com.theemrock.smooth.bridge;

/** Shared constants for the SMOOTH bridge. Plain Java so the module copies into any repo unchanged. */
public final class SmoothBridge {
    private SmoothBridge() {}

    public static final String PERMISSION = "com.theemrock.smooth.permission.BRIDGE";
    public static final int BRIDGE_VERSION = 1;

    public static final String CAPABILITY_STRUCTURED = "llm.structured";
    public static final String CAPABILITY_GENERATE = "llm.generate";

    public static final class Error {
        private Error() {}

        public static final String MODEL_NOT_LOADED = "MODEL_NOT_LOADED";
        public static final String BUSY = "BUSY";
        public static final String CANCELLED = "CANCELLED";
        public static final String TRUNCATED = "TRUNCATED";
        public static final String INVALID_REQUEST = "INVALID_REQUEST";
        public static final String UNSUPPORTED_CAPABILITY = "UNSUPPORTED_CAPABILITY";
        public static final String INTERNAL = "INTERNAL";
    }
}
