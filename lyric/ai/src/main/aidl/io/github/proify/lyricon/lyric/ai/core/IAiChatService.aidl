package io.github.proify.lyricon.lyric.ai.core;

import io.github.proify.lyricon.lyric.ai.core.AiConfig;
import io.github.proify.lyricon.lyric.ai.core.IAiChatCallback;

oneway interface IAiChatService {
    void complete(String requestId, in AiConfig config, String systemPrompt,
                  String userPrompt, boolean jsonObject, IAiChatCallback callback);
    void cancel(String requestId);
    void translateGoogle(String requestId, String targetLanguage, String baseUrl,
                         String textsJson, IAiChatCallback callback);
}
