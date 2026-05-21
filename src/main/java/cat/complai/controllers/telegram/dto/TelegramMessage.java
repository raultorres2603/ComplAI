package cat.complai.controllers.telegram.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;

/**
 * Represents an incoming Telegram message.
 *
 * <p>This record models the subset of fields relevant to the ComplAI
 * assistant. The Telegram Bot API may send additional fields that are
 * simply ignored during deserialisation.
 *
 * @param messageId  unique message identifier within the chat
 * @param fromUser   the sender of the message, or {@code null} for
 *                   messages sent to the bot via inline queries
 * @param chat       the chat the message belongs to
 * @param date       Unix timestamp of when the message was sent
 * @param text       the message text, or {@code null} for non-text
 *                   messages (e.g. photos, stickers)
 */
@Introspected
public record TelegramMessage(
        @JsonProperty("message_id") int messageId,
        @JsonProperty("from") @Nullable TelegramUser fromUser,
        TelegramChat chat,
        long date,
        @Nullable String text
) {}
