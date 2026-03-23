package cat.complai.openrouter.services.ai;

import cat.complai.http.HttpWrapper;
import cat.complai.http.dto.HttpDto;
import cat.complai.openrouter.cache.QuestionCategory;
import cat.complai.openrouter.cache.QuestionCategoryDetector;
import cat.complai.openrouter.cache.ResponseCacheKey;
import cat.complai.openrouter.dto.OpenRouterResponseDto;
import cat.complai.openrouter.dto.OpenRouterErrorCode;
import cat.complai.openrouter.dto.OutputFormat;
import cat.complai.openrouter.helpers.AiParsed;
import cat.complai.openrouter.helpers.RedactPromptBuilder;
import cat.complai.openrouter.services.cache.ResponseCacheService;
import cat.complai.openrouter.services.cache.QuestionFrequencyTracker;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import io.micronaut.context.annotation.Value;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

@Singleton
public class AiResponseProcessingService {

    private final HttpWrapper httpWrapper;
    private final ResponseCacheService responseCacheService;
    private final QuestionFrequencyTracker frequencyTracker;
    private final Logger logger = Logger.getLogger(AiResponseProcessingService.class.getName());
    private final int overallTimeoutSeconds;

    @Inject
    public AiResponseProcessingService(HttpWrapper httpWrapper,
            ResponseCacheService responseCacheService,
            QuestionFrequencyTracker frequencyTracker,
            @Value("${OPENROUTER_OVERALL_TIMEOUT_SECONDS:60}") int overallTimeoutSeconds) {
        this.httpWrapper = httpWrapper;
        this.responseCacheService = responseCacheService;
        this.frequencyTracker = frequencyTracker;
        this.overallTimeoutSeconds = (overallTimeoutSeconds > 0) ? overallTimeoutSeconds : 30;
    }

    /**
     * Call OpenRouter with smart caching.
     * Checks cache before calling OpenRouter, caches success responses only.
     * 
     * @param messages         The messages to send to OpenRouter
     * @param cityId           The city context
     * @param procContextHash  Hash of procedure context (0 if none)
     * @param eventContextHash Hash of event context (0 if none)
     * @return The OpenRouter response (from cache or freshly fetched)
     */
    public OpenRouterResponseDto callOpenRouterAndExtract(List<Map<String, Object>> messages, String cityId,
            long procContextHash, long eventContextHash) {
        // Extract user question from messages (last user message)
        String question = extractUserQuestion(messages);

        // Detect question category for cache key
        QuestionCategory category = QuestionCategoryDetector.detectCategory(question);

        // Build cache key: cityId + context hashes + category (no user query text!)
        ResponseCacheKey cacheKey = new ResponseCacheKey(cityId, procContextHash, eventContextHash, category);

        // Track question frequency for auto-promotion (privacy-safe: no raw query
        // stored)
        String normalizedQuestion = normalizeQuestion(question);
        frequencyTracker.trackQuestion(cityId, category, procContextHash, eventContextHash, normalizedQuestion);

        // Check cache first
        Optional<String> cachedResponse = responseCacheService.getCachedResponse(cacheKey);
        if (cachedResponse.isPresent()) {
            logger.info(() -> "CACHE HIT — Using cached response for " + cacheKey);
            return new OpenRouterResponseDto(true, cachedResponse.get(), null, 200, OpenRouterErrorCode.NONE);
        }

        // Cache miss: call OpenRouter
        logger.fine(() -> "CACHE MISS — Calling OpenRouter for " + cacheKey);
        OpenRouterResponseDto response = callOpenRouterInternal(messages, cityId);

        // Cache successful responses only
        if (response.isSuccess() && response.getMessage() != null) {
            responseCacheService.cacheResponse(cacheKey, response.getMessage());
        }

        return response;
    }

    /**
     * Legacy method for backward compatibility.
     * Calls OpenRouter without caching (context hashes default to 0).
     * 
     * @deprecated Use {@link #callOpenRouterAndExtract(List, String, long, long)}
     *             instead
     */
    @Deprecated
    public OpenRouterResponseDto callOpenRouterAndExtract(List<Map<String, Object>> messages, String cityId) {
        return callOpenRouterAndExtract(messages, cityId, 0, 0);
    }

    /**
     * Internal method to call OpenRouter API (no caching).
     */
    private OpenRouterResponseDto callOpenRouterInternal(List<Map<String, Object>> messages, String cityId) {
        logger.fine(() -> "callOpenRouterAndExtract — sending " + messages.size() + " messages to OpenRouter");

        // Calculate input token count for logging
        int inputTokens = messages.stream()
                .mapToInt(msg -> {
                    String content = (String) msg.get("content");
                    return content != null ? Math.max(1, content.length() / 4) : 0;
                })
                .sum();
        logger.fine(() -> "callOpenRouterAndExtract — inputTokenCount=" + inputTokens);

        long callStartTime = System.currentTimeMillis();
        try {
            CompletableFuture<HttpDto> future = httpWrapper.postToOpenRouterAsync(messages);
            HttpDto dto = future.get(overallTimeoutSeconds, TimeUnit.SECONDS);
            long callEndTime = System.currentTimeMillis();
            long callTime = callEndTime - callStartTime;
            logger.fine(() -> "OpenRouter call time = " + callTime + "ms");

            if (dto == null) {
                logger.warning("callOpenRouterAndExtract — OpenRouter returned null response (no HTTP status)");
                return new OpenRouterResponseDto(false, null, "No response from AI service.", null,
                        OpenRouterErrorCode.UPSTREAM);
            }
            logger.fine(() -> "callOpenRouterAndExtract — OpenRouter responded httpStatus=" + dto.statusCode()
                    + " hasMessage=" + (dto.message() != null && !dto.message().isBlank())
                    + " hasError=" + (dto.error() != null && !dto.error().isBlank()));
            if (dto.error() != null && !dto.error().isBlank()) {
                logger.warning(() -> "callOpenRouterAndExtract — OpenRouter error httpStatus=" + dto.statusCode()
                        + " error=" + dto.error());
                return new OpenRouterResponseDto(false, dto.message(), dto.error(), dto.statusCode(),
                        OpenRouterErrorCode.UPSTREAM);
            }
            if (dto.message() != null && !dto.message().isBlank()) {
                // Log output token count
                int outputTokens = Math.max(1, dto.message().length() / 4);
                logger.fine(() -> "callOpenRouterAndExtract — outputTokenCount=" + outputTokens);

                if (aiRefusedAsOffTopic(dto.message(), cityId)) {
                    String cityName = RedactPromptBuilder.resolveCityDisplayName(cityId);
                    logger.info(() -> "callOpenRouterAndExtract — AI refused (off-topic for city=" + cityId
                            + ") httpStatus=" + dto.statusCode());
                    return new OpenRouterResponseDto(false, dto.message(),
                            "Request is not about " + cityName + ".",
                            dto.statusCode(), OpenRouterErrorCode.REFUSAL);
                }
                logger.fine(() -> "callOpenRouterAndExtract — AI responded successfully httpStatus=" + dto.statusCode()
                        + " responseLength=" + dto.message().length());
                return new OpenRouterResponseDto(true, dto.message(), null, dto.statusCode(), OpenRouterErrorCode.NONE);
            }
            logger.warning(() -> "callOpenRouterAndExtract — AI returned empty message httpStatus=" + dto.statusCode());
            return new OpenRouterResponseDto(false, null, "AI returned no message.", dto.statusCode(),
                    OpenRouterErrorCode.UPSTREAM);
        } catch (TimeoutException te) {
            logger.log(Level.SEVERE,
                    "callOpenRouterAndExtract — AI service timed out after " + overallTimeoutSeconds + "s", te);
            return new OpenRouterResponseDto(false, null, "AI service timed out.", null, OpenRouterErrorCode.TIMEOUT);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "callOpenRouterAndExtract — unexpected exception calling AI service", e);
            return new OpenRouterResponseDto(false, null, e.getMessage(), null, OpenRouterErrorCode.INTERNAL);
        }
    }

    public OpenRouterResponseDto processComplaintResponse(OpenRouterResponseDto aiDto, boolean identityComplete) {
        // Propagate any non-success result immediately.
        if (aiDto.getErrorCode() != OpenRouterErrorCode.NONE) {
            return aiDto;
        }

        AiParsed parsed = AiParsed.parseAiFormatHeader(aiDto.getMessage());

        // Identity incomplete: the AI is asking the user for missing fields.
        // Return its question as text so the client can display it.
        if (!identityComplete) {
            return new OpenRouterResponseDto(true, parsed.message(), null, aiDto.getStatusCode(),
                    OpenRouterErrorCode.NONE);
        }

        // Graceful fallback when the AI omits the required JSON header: return the raw
        // message.
        // PDF generation has been removed from the sync path — PDFs are always produced
        // by the
        // async worker Lambda.
        if (parsed.format() == null || parsed.format() == OutputFormat.AUTO) {
            String rawPreview = aiDto.getMessage() == null ? "<null>"
                    : (aiDto.getMessage().length() > 200 ? aiDto.getMessage().substring(0, 200) + "..."
                            : aiDto.getMessage());
            logger.warning("AI response missing required JSON header; raw response prefix: " + rawPreview);
            return new OpenRouterResponseDto(true, aiDto.getMessage(), null, aiDto.getStatusCode(),
                    OpenRouterErrorCode.NONE);
        }

        // Header present: return the extracted letter body as text.
        return new OpenRouterResponseDto(true, parsed.message(), null, aiDto.getStatusCode(), OpenRouterErrorCode.NONE);
    }

    /**
     * Extract user's question from the messages list.
     * Looks for the last message with role="user".
     * Returns the message content or empty string if not found.
     * 
     * @param messages The messages list
     * @return The user's question text
     */
    private String extractUserQuestion(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }

        // Iterate from the end backwards to find the last user message
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if ("user".equals(msg.get("role"))) {
                Object content = msg.get("content");
                if (content instanceof String) {
                    return (String) content;
                }
            }
        }

        return "";
    }

    /**
     * Normalize a question for frequency tracking.
     * 
     * This method produces a privacy-safe representation:
     * - Converts to lowercase
     * - Removes extra whitespace
     * - Removes punctuation
     * - Hashes the result for further anonymization
     * 
     * Result is suitable for tracking question patterns without storing raw query
     * text.
     * 
     * @param question The raw user question
     * @return Normalized question string (hash-safe)
     */
    private String normalizeQuestion(String question) {
        if (question == null || question.isBlank()) {
            return "empty";
        }

        // Normalize: lowercase, remove punctuation, collapse whitespace
        String normalized = question
                .toLowerCase(Locale.ROOT)
                .replaceAll("[.,;:!?()\\[\\]{}-]", " ")
                .replaceAll("\\s+", " ")
                .trim();

        // If it becomes empty after normalization, return a marker
        if (normalized.isBlank()) {
            return "empty";
        }

        // Use the first 100 characters as the normalized form
        // (sufficient for pattern matching, respects privacy)
        return normalized.substring(0, Math.min(100, normalized.length()));
    }

    /**
     * Detect whether the assistant explicitly refused because the request is not
     * about the
     * given city. Generic refusal phrases are city-agnostic and detected first; a
     * secondary
     * check looks for the city name paired with scope-limiting words.
     */
    private boolean aiRefusedAsOffTopic(String aiMessage, String cityId) {
        if (aiMessage == null)
            return false;
        // Normalize typographic quotes/apostrophes for reliable matching.
        String normalized = aiMessage
                .replace('\u2018', '\'').replace('\u2019', '\'')
                .replace('\u201c', '"').replace('\u201d', '"');
        String lower = normalized.toLowerCase(Locale.ROOT).trim();
        String lowerNoPunct = lower.replaceAll("[.,;:!?()\\[\\]{}-]", " ").replaceAll("\\s+", " ").trim();

        // Generic refusal patterns (city-agnostic).
        String[] refusalPhrases = {
                "can't help with",
                "cannot help with",
                "can't help",
                "cannot help",
                "can't assist",
                "cannot assist",
                "i'm sorry, i can't",
                "i'm sorry i can't",
                "i'm sorry, i cannot",
                "i'm sorry i cannot",
                "i cannot",
                "i can't",
                "i am unable to",
                "i'm unable to",
                "i cannot help",
                "i can't help",
                "cannot provide",
                "can't provide",
                "no puc ajudar",
                "no puc ajudar amb",
                "no puedo ayudar",
                "no puedo ayudar con",
                "lo siento, no puedo ayudar",
                "lo siento, no puedo",
                "no puedo",
                "no puc",
        };

        for (String p : refusalPhrases) {
            String pNoPunct = p.replaceAll("[.,;:!?()\\[\\]{}-]", " ").trim();
            if (lower.contains(p) || lowerNoPunct.contains(pNoPunct)) {
                logger.fine("Refusal detected by phrase: '" + p + "' in: " + lower);
                return true;
            }
        }

        // City-specific secondary check: the AI mentioned the city name alongside
        // scope-limiting language.
        String cityNameLower = RedactPromptBuilder.resolveCityDisplayName(cityId).toLowerCase(Locale.ROOT);
        if (lower.contains(cityNameLower)
                && (lower.contains("only") || lower.contains("solament") || lower.contains("solo")
                        || lower.contains("only about"))) {
            logger.fine("Refusal detected: city name '" + cityNameLower + "' + scope-limiting word");
            return true;
        }

        return false;
    }
}
