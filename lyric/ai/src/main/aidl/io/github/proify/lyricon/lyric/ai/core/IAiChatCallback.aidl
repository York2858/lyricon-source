package io.github.proify.lyricon.lyric.ai.core;

oneway interface IAiChatCallback {
    void onResult(String content);
    void onError(String code, String reason, String suggestion, int httpStatus);
}
