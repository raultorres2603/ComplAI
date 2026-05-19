package cat.complai.controllers.telegram.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;

/**
 * Outbound DTO for the Telegram {@code sendMessage} Bot API method.
 *
 * <p>Serialised to JSON and sent as the request body when the bot needs
 * to send a text message to a chat.
 *
 * @param chatId              unique identifier for the target chat
 * @param text                the message text
 * @param parseMode           formatting mode ({@code "HTML"} or
 *                            {@code "MarkdownV2"}), or {@code null} for
 *                            plain text
 * @param replyMarkup         an inline keyboard to attach to the message,
 *                            or {@code null} for no keyboard
 */
@Introspected
public record TelegramSendMessageRequest(
        @JsonProperty("chat_id") long chatId,
        String text,
        @JsonProperty("parse_mode") @Nullable String parseMode,
        @JsonProperty("reply_markup") @Nullable TelegramInlineKeyboardMarkup replyMarkup
) {}
