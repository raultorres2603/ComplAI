package cat.complai.controllers.telegram.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;

/**
 * Outbound DTO for the Telegram {@code answerCallbackQuery} Bot API method.
 *
 * <p>Used to acknowledge a callback query and optionally show a brief
 * notification to the user.
 *
 * <p>Fields with {@code null} values are omitted from the JSON payload
 * so that Telegram does not receive {@code "text": null} — which some
 * Telegram clients display as the literal string "null" in push
 * notifications.
 *
 * @param callbackQueryId unique identifier of the callback query to answer
 * @param text            optional notification text shown to the user in a
 *                        toast, or {@code null} (omitted) for no notification
 */
@Introspected
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TelegramAnswerCallbackQueryRequest(
        @JsonProperty("callback_query_id") String callbackQueryId,
        @Nullable String text
) {}
