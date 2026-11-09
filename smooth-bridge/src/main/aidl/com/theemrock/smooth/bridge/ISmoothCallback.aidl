package com.theemrock.smooth.bridge;

oneway interface ISmoothCallback {
    void onChunk(String requestId, String text);
    void onResult(String requestId, String json);
    void onError(String requestId, String code, String message);
}
