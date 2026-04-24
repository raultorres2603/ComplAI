package cat.complai.services.openrouter.ai;

import cat.complai.utilities.http.HttpWrapper;
import cat.complai.dto.http.HttpDto;
import cat.complai.utilities.cache.QuestionCategory;
import cat.complai.utilities.cache.QuestionCategoryDetector;
import cat.complai.utilities.cache.ResponseCacheKey;
import cat.complai.dto.openrouter.OpenRouterResponseDto;
import cat.complai.dto.openrouter.OpenRouterErrorCode;
import cat.complai.dto.openrouter.OutputFormat;
import cat.complai.helpers.openrouter.AiParsed;
import cat.complai.helpers.openrouter.HtmlFormatter;
import cat.complai.helpers.openrouter.MarkdownToHtmlConverter;
import cat.complai.helpers.openrouter.RedactPromptBuilder;
import cat.complai.services.openrouter.cache.ResponseCacheService;
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

/**
 * Handles the AI call to OpenRouter, including response caching and error
 * mapping.
 *
 * <p>
 * Checks the Caffeine response cache before forwarding the request to
 * OpenRouter
 * and stores successful responses so that semantically equivalent queries can
 * be served
 * without a new AI call.
 */
@Singleton
public class AiResponseProcessingService {

    private final HttpWrapper httpWrapper;
    private final ResponseCacheService responseCacheService;
    private final Logger logger = Logger.getLogger(AiResponseProcessingService.class.getName());
    private final int overallTimeoutSeconds;

    /**
     * Constructs the service with configurable timeout and cache dependencies.
     *
     * @param httpWrapper           HTTP wrapper for OpenRouter calls
     * @param responseCacheService  Caffeine response cache
     * @param overallTimeoutSeconds maximum seconds to wait for the AI response;
     *                              defaults to 60
     */
    @Inject
    public AiResponseProcessingService(HttpWrapper httpWrapper,
            ResponseCacheService responseCacheService,
            @Value("${OPENROUTER_OVERALL_TIMEOUT_SECONDS:60}") int overallTimeoutSeconds) {
        this.httpWrapper = httpWrapper;
        this.responseCacheService = responseCacheService;
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
        // Extract the actual user question from messages for category detection
        String userQuestion = extractUserQuestion(messages);
        QuestionCategory category = QuestionCategoryDetector.detectCategory(userQuestion);
        int questionHash = hashQuestion(userQuestion);

        // Build cache key: cityId + context hashes + category + question hash (no raw
        // user query text!)
        ResponseCacheKey cacheKey = new ResponseCacheKey(cityId, procContextHash, eventContextHash, category,
                questionHash);

        // Check cache first
        Optional<String> cachedResponse = responseCacheService.getCachedResponse(cacheKey);
        if (cachedResponse.isPresent()) {
            logCacheObservation("CACHE HIT", cacheKey);
            return new OpenRouterResponseDto(true, cachedResponse.get(), null, 200, OpenRouterErrorCode.NONE);
        }

        // Cache miss: call OpenRouter
        logCacheObservation("CACHE MISS", cacheKey);
        OpenRouterResponseDto response = callOpenRouterInternal(messages, cityId);

        // Cache all successful responses — the questionHash field in the cache key
        // ensures
        // that different questions with identical city/hashes/category are not
        // confused.
        if (response.isSuccess() && response.getMessage() != null) {
            responseCacheService.cacheResponse(cacheKey, response.getMessage());
            logCacheObservation("CACHE STORE", cacheKey);
        }

        return response;
    }

    /**
     * Normalizes and hashes a question for use in the cache key.
     * One-way hash: the raw question text is never stored in the cache key.
     */
    private static int hashQuestion(String question) {
        if (question == null || question.isBlank())
            return 0;
        return question.strip().toLowerCase(java.util.Locale.ROOT).hashCode();
    }

    /**
     * Extract the last user message from the message list.
     * Essential for proper question categorization during caching decisions.
     */
    private String extractUserQuestion(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        // Find the last message from the user
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if (msg != null && "user".equals(msg.get("role"))) {
                Object content = msg.get("content");
                return content != null ? content.toString() : "";
            }
        }
        return "";
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
                // Apply HTML formatting: convert Markdown to HTML, then clean excessive
                // formatting
                String cleanedMessage = ensureHtmlFormat(dto.message());
                return new OpenRouterResponseDto(true, cleanedMessage, null, dto.statusCode(),
                        OpenRouterErrorCode.NONE);
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
            String cleanedMessage = ensureHtmlFormat(parsed.message());
            return new OpenRouterResponseDto(true, cleanedMessage, null, aiDto.getStatusCode(),
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
            String cleanedMessage = ensureHtmlFormat(aiDto.getMessage());
            return new OpenRouterResponseDto(true, cleanedMessage, null, aiDto.getStatusCode(),
                    OpenRouterErrorCode.NONE);
        }

        // Header present: return the extracted letter body as text.
        String cleanedMessage = ensureHtmlFormat(parsed.message());
        return new OpenRouterResponseDto(true, cleanedMessage, null, aiDto.getStatusCode(), OpenRouterErrorCode.NONE);
    }

    /**
     * Ensures a message is HTML-formatted by converting Markdown syntax to HTML
     * and applying HTML cleaning.
     * 
     * <p>
     * Process:
     * </p>
     * <ul>
     * <li>If message is null or empty, returns as-is</li>
     * <li>Converts Markdown formatting to HTML using
     * {@link MarkdownToHtmlConverter#convertMarkdownToHtml(String)}</li>
     * <li>Applies HTML cleaning using {@link HtmlFormatter#cleanHtml(String)} to
     * remove excessive formatting</li>
     * </ul>
     * 
     * @param message The message text to format (may be null or empty)
     * @return The HTML-formatted message, or null/empty if input was null/empty
     */
    private String ensureHtmlFormat(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        String htmlFormatted = MarkdownToHtmlConverter.convertMarkdownToHtml(message);
        return HtmlFormatter.cleanHtml(htmlFormatted);
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

    private void logCacheObservation(String event, ResponseCacheKey cacheKey) {
        ResponseCacheService.CacheStatsSnapshot stats = responseCacheService.getStats();
        logger.fine(() -> event + " — key=" + cacheKey
                + " enabled=" + stats.enabled()
                + " hits=" + stats.hitCount()
                + " misses=" + stats.missCount()
                + " puts=" + stats.putCount()
                + " evictions=" + stats.evictionCount()
                + " invalidations=" + stats.invalidationCount()
                + " size=" + stats.estimatedSize());
    }
}
