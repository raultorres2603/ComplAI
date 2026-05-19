package cat.complai.controllers.telegram.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;

/**
 * Represents a Telegram user or bot.
 *
 * @param id           unique identifier for this user
 * @param isBot        whether this user is a bot
 * @param firstName    the user's first name, or {@code null}
 * @param languageCode IETF language tag of the user's language, or
 *                     {@code null}
 */
@Introspected
public record TelegramUser(
        long id,
        @JsonProperty("is_bot") boolean isBot,
        @JsonProperty("first_name") @Nullable String firstName,
        @JsonProperty("language_code") @Nullable String languageCode
) {}
