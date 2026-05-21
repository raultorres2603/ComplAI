package cat.complai.controllers.telegram.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Introspected;

/**
 * Outbound DTO for the Telegram {@code sendChatAction} Bot API method.
 *
 * <p>Tells the user that the bot is performing an action (e.g. "typing",
 * "uploading document"). The action is shown for a few seconds.
 *
 * @param chatId unique identifier for the target chat
 * @param action type of action to broadcast, e.g. {@code "typing"},
 *               {@code "upload_document"}
 */
@Introspected
public record TelegramSendChatActionRequest(
        @JsonProperty("chat_id") long chatId,
        String action
) {}
