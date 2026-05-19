package cat.complai.controllers.telegram.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;

/**
 * Root Telegram webhook update object.
 *
 * <p>Every callback from the Telegram Bot API arrives as a JSON-serialised
 * instance of this record. Exactly one of {@code message} or
 * {@code callbackQuery} is typically present.
 *
 * @param updateId     the update's unique identifier
 * @param message      the incoming message, or {@code null} for callback queries
 * @param callbackQuery the callback query, or {@code null} for text messages
 */
@Introspected
public record TelegramUpdate(
        @JsonProperty("update_id") long updateId,
        @Nullable TelegramMessage message,
        @JsonProperty("callback_query") @Nullable TelegramCallbackQuery callbackQuery
) {}
