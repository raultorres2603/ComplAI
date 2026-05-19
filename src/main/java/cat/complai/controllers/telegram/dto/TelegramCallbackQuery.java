package cat.complai.controllers.telegram.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;

/**
 * Represents an incoming callback query from an inline keyboard button press.
 *
 * <p>When a user taps an inline button, Telegram sends a callback query to the
 * bot. The bot should respond with
 * {@link TelegramAnswerCallbackQueryRequest} to acknowledge the query.
 *
 * @param id       unique identifier for this callback query
 * @param fromUser the sender of the callback query
 * @param message  the message that contained the inline keyboard, or
 *                 {@code null} if the message was sent via inline mode
 * @param data     the callback data associated with the button, or
 *                 {@code null} if no data was provided
 */
@Introspected
public record TelegramCallbackQuery(
        String id,
        @JsonProperty("from") TelegramUser fromUser,
        @Nullable TelegramMessage message,
        @Nullable String data
) {}
