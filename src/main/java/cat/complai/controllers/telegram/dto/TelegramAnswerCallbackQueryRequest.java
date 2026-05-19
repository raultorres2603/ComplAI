package cat.complai.controllers.telegram.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;

/**
 * Outbound DTO for the Telegram {@code answerCallbackQuery} Bot API method.
 *
 * <p>Used to acknowledge a callback query and optionally show a brief
 * notification to the user.
 *
 * @param callbackQueryId unique identifier of the callback query to answer
 * @param text            optional notification text shown to the user in a
 *                        toast, or {@code null} for no notification
 */
@Introspected
public record TelegramAnswerCallbackQueryRequest(
        @JsonProperty("callback_query_id") String callbackQueryId,
        @Nullable String text
) {}
