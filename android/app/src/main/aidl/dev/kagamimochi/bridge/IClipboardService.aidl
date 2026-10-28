package dev.kagamimochi.bridge;

interface IClipboardService {
    // Transaction id required by Shizuku
    void destroy() = 16777114;

    String getText() = 1;
    void setText(String text) = 2;
    void enableNotificationListener(String component) = 3;
}
