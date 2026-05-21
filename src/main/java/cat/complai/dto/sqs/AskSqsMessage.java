package cat.complai.dto.sqs;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.micronaut.core.annotation.Introspected;

/**
 * SQS message payload for the async Telegram ask flow.
 *
 * <p>This record is the contract between the API Lambda (publisher) and the AskWorker Lambda
 * (consumer). Any schema change here must be backwards-compatible or deployed atomically
 * to both Lambdas to avoid deserialization failures on in-flight messages.
 *
 * <p>The API Lambda sends a "processing" message to the user immediately, then enqueues
 * this message. The worker Lambda consumes it, calls the AI, and sends the answer back
 * to the user via the Telegram Bot API — all outside the 60-second Lambda timeout window.
 */
@Introspected
public record AskSqsMessage(
        // Telegram chat ID — the worker uses this to send the answer back.
        long chatId,
        // The user's original question text.
        String question,
        // City identifier used to load the correct procedures context for RAG.
        String cityId,
        // User's preferred language code (CA, ES, EN, FR).
        String lang,
        // Optional conversation ID for multi-turn context.
        String conversationId
) {
    @JsonCreator
    public static AskSqsMessage fromJson(
            @JsonProperty("chatId")         long chatId,
            @JsonProperty("question")       String question,
            @JsonProperty("cityId")         String cityId,
            @JsonProperty("lang")           String lang,
            @JsonProperty("conversationId") String conversationId) {
        return new AskSqsMessage(chatId, question, cityId, lang, conversationId);
    }
}
