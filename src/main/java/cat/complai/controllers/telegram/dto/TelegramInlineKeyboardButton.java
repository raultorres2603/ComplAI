package cat.complai.controllers.telegram.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;

/**
 * Represents a single button in an inline keyboard.
 *
 * <p>When pressed, Telegram sends a {@link TelegramCallbackQuery} to the
 * bot with the {@code callbackData} payload.
 *
 * @param text         label text displayed on the button
 * @param callbackData data to be sent back in the callback query, or
 *                     {@code null} if not set
 */
@Introspected
public record TelegramInlineKeyboardButton(
        String text,
        @JsonProperty("callback_data") @Nullable String callbackData
) {}
