package cat.complai.services.openrouter;

import cat.complai.helpers.openrouter.CityInfoRagHelperRegistry;
import cat.complai.helpers.openrouter.EventRagHelperRegistry;
import cat.complai.helpers.openrouter.NewsRagHelperRegistry;
import cat.complai.helpers.openrouter.ProcedureRagHelperRegistry;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Detects intent from a user query: determines which RAG context domains
 * (procedure, event, news, city-info) are needed, and detects
 * conversational short-circuits, date-window requirements for events,
 * and Generalitat procedure queries.
 *
 * <p>This class was extracted from {@code ProcedureContextService} during the
 * god-class split. It contains all keyword matching, title matching, and
 * conversational short-circuit logic.</p>
 */
@Singleton
public class IntentDetector {

    private static final Set<String> TITLE_STOP_WORDS = Set.of(
            "a", "an", "and", "d", "de", "del", "el", "els", "en", "for", "i", "la", "las",
            "les", "lo", "los", "of", "the", "y");

    private static final List<String> PROCEDURE_KEYWORDS = List.of(
            "how to", "how do i", "what is the process", "procedure", "tramit", "tràmit",
            "requirement", "requirements", "document", "documents", "apply", "application",
            "form", "forms", "permit", "license", "request", "complaint", "claim",
            "where can i", "how can i", "steps", "step by step", "process",
            "recycling", "waste", "garbage", "trash", "center", "collection");

    private static final List<String> GENERALITAT_KEYWORDS = List.of(
            "generalitat", "generalitat de catalunya", "catalan government",
            "gencat", "gencat procediment", "tramit gencat",
            "govern catala", "catalunya", "catalan");

    private static final List<String> MUNICIPAL_SERVICE_KEYWORDS = List.of(
            "ajuntament", "city hall", "municipal", "council");

    private static final List<String> EVENT_KEYWORDS = List.of(
            "event", "events", "evento", "eventos", "esdeveniment", "esdeveniments",
            "agenda", "activity", "activities", "activitat", "activitats",
            "festival", "concert", "exhibition", "exposició", "theater", "teatre",
            "cinema", "sports", "esports", "culture", "cultura", "celebration", "celebració",
            "what's on", "what's happening", "què passa", "agenda cultural", "program",
            "this weekend", "upcoming",
            "capsa", "artesa", "l'artesa", "la capsa", "teatre artesa");

    private static final List<String> EVENT_INTENT_GUARD_KEYWORDS = List.of(
            "event", "events", "evento", "eventos", "esdeveniment", "esdeveniments",
            "agenda", "activity", "activities", "activitat", "activitats",
            "what's on", "what is on", "what's happening", "que passa", "què passa");

    private static final List<String> DATE_WINDOW_KEYWORDS = List.of(
            "today", "tomorrow", "tonight", "this weekend", "next weekend", "weekend",
            "this week", "next week", "this month", "next month",
            "avui", "dema", "demà", "aquesta setmana", "setmana vinent", "cap de setmana", "aquest mes",
            "mes vinent",
            "hoy", "manana", "mañana", "esta semana", "proxima semana", "próxima semana", "fin de semana",
            "este mes", "proximo mes", "próximo mes");

    private static final List<String> MONTH_KEYWORDS = List.of(
            "january", "february", "march", "april", "may", "june", "july", "august",
            "september", "october", "november", "december",
            "gener", "febrer", "marc", "març", "abril", "maig", "juny", "juliol", "agost",
            "setembre", "octubre", "novembre", "desembre",
            "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto",
            "septiembre", "octubre", "noviembre", "diciembre");

    private static final List<String> RANGE_CONNECTOR_KEYWORDS = List.of(
            "from", "to", "between", "until", "through", "del", "al", "desde", "hasta", "entre");

    private static final Pattern NUMERIC_DATE_PATTERN = Pattern.compile("\\b\\d{1,2}[/-]\\d{1,2}(?:[/-]\\d{2,4})?\\b");
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("\\b\\d{4}-\\d{1,2}-\\d{1,2}\\b");

    private static final List<String> NEWS_KEYWORDS = List.of(
            "news", "latest news", "recent news", "recent happenings", "current affairs",
            "actuality", "actualitat", "actualidad", "noticies", "notícies", "noticias",
            "ultimes", "últimes", "ultimas", "últimas", "latest", "recent");

    private static final List<String> CITY_INFO_KEYWORDS = List.of(
            "city", "municipal", "ajuntament", "city hall", "serveis", "serveis municipals",
            "services", "public service", "public services", "equipaments", "equipamientos",
            "tourism", "turisme", "turismo", "visit", "visitar", "visita", "informacio",
            "informació", "informacion", "información");

    private static final List<String> CONVERSATIONAL_SHORT_CIRCUITS = List.of(
            "hello", "hi", "hey", "good morning", "good afternoon", "good evening",
            "hola", "bon dia", "bona tarda", "how are you", "what is the weather", "weather",
            "tell me a joke", "who are you", "what's your name", "thank you", "thanks");

    private final ProcedureRagHelperRegistry ragRegistry;
    private final EventRagHelperRegistry eventRagRegistry;
    private final NewsRagHelperRegistry newsRagRegistry;
    private final CityInfoRagHelperRegistry cityInfoRagRegistry;
    private final Logger logger = Logger.getLogger(IntentDetector.class.getName());
    private final ConcurrentHashMap<String, CityDetectionIndex> detectionIndexByCity = new ConcurrentHashMap<>();

    @Inject
    public IntentDetector(ProcedureRagHelperRegistry ragRegistry,
                          EventRagHelperRegistry eventRagRegistry,
                          NewsRagHelperRegistry newsRagRegistry,
                          CityInfoRagHelperRegistry cityInfoRagRegistry) {
        this.ragRegistry = ragRegistry;
        this.eventRagRegistry = eventRagRegistry;
        this.newsRagRegistry = newsRagRegistry;
        this.cityInfoRagRegistry = cityInfoRagRegistry;
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Determines if a question likely needs procedure/municipal information.
     */
    public boolean questionNeedsProcedureContext(String question, String cityId) {
        return detectContextRequirements(question, cityId).needsProcedureContext();
    }

    public boolean questionNeedsEventContext(String question, String cityId) {
        return detectContextRequirements(question, cityId).needsEventContext();
    }

    public boolean questionNeedsNewsContext(String question, String cityId) {
        return detectContextRequirements(question, cityId).needsNewsContext();
    }

    public boolean questionNeedsCityInfoContext(String question, String cityId) {
        return detectContextRequirements(question, cityId).needsCityInfoContext();
    }

    /**
     * Checks if the query is asking about Generalitat procedures.
     */
    public boolean isAskingGencatProcedures(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String normalized = question.toLowerCase(Locale.ROOT);
        return containsAny(normalized, GENERALITAT_KEYWORDS)
                || normalized.contains("generalitat")
                || normalized.contains("gencat")
                || normalized.contains("catalan government");
    }

    /**
     * Determines if a question expresses event intent without a date window.
     * When true, the caller should prompt for a date range before doing RAG.
     */
    public boolean requiresEventDateWindowClarification(String question, String cityId) {
        if (question == null || question.isBlank()) {
            return false;
        }
        ContextRequirements requirements = detectContextRequirements(question, cityId);
        if (!requirements.needsEventContext() || requirements.needsNewsContext()) {
            return false;
        }
        String normalizedQuestion = normalize(question);
        if (!containsAny(normalizedQuestion, EVENT_INTENT_GUARD_KEYWORDS)) {
            return false;
        }
        return !hasDateWindow(normalizedQuestion, question);
    }

    /**
     * Main intent detection: keyword matching + title matching + conversational
     * short-circuit.
     * Returns which context domains are needed for the given query.
     */
    public ContextRequirements detectContextRequirements(String question, String cityId) {
        if (question == null || question.isBlank()) {
            return ContextRequirements.none();
        }

        String normalizedQuestion = normalize(question);
        long startNanos = System.nanoTime();

        boolean needsProcedure = containsAny(normalizedQuestion, PROCEDURE_KEYWORDS)
                || containsAny(normalizedQuestion, MUNICIPAL_SERVICE_KEYWORDS);
        boolean needsEvent = containsAny(normalizedQuestion, EVENT_KEYWORDS);
        boolean needsNews = containsAny(normalizedQuestion, NEWS_KEYWORDS);

        if (needsNews) {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            logger.fine(() -> "Context detection completed from news keyword path - city=" + cityId
                    + " procedure=false event=false news=true durationMs=" + durationMs);
            return new ContextRequirements(false, false, true, false);
        }

        if (!needsProcedure && !needsEvent && isClearlyConversational(normalizedQuestion)) {
            logger.fine(() -> "Context detection short-circuited conversational query — city=" + cityId);
            return ContextRequirements.none();
        }

        if (needsProcedure ^ needsEvent) {
            boolean finalNeedsProcedure = needsProcedure;
            boolean finalNeedsEvent = needsEvent;
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            logger.fine(() -> "Context detection short-circuited keyword query — city=" + cityId
                    + " procedure=" + finalNeedsProcedure
                    + " event=" + finalNeedsEvent
                    + " news=false"
                    + " durationMs=" + durationMs);
            return new ContextRequirements(finalNeedsProcedure, finalNeedsEvent, false, false);
        }

        if (needsProcedure && needsEvent) {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            logger.fine(() -> "Context detection completed from keyword path — city=" + cityId
                    + " procedure=true event=true news=false durationMs=" + durationMs);
            return new ContextRequirements(true, true, false, false);
        }

        CityDetectionIndex detectionIndex = getOrCreateDetectionIndex(cityId);
        needsProcedure = detectionIndex.procedureMatcher().matches(normalizedQuestion);
        needsEvent = detectionIndex.eventMatcher().matches(normalizedQuestion);
        needsNews = detectionIndex.newsMatcher().matches(normalizedQuestion);

        if (needsNews) {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            logger.fine(() -> "Context detection completed from title path - city=" + cityId
                    + " procedure=false event=false news=true durationMs=" + durationMs
                    + " newsTitles=" + detectionIndex.newsTitleCount());
            return new ContextRequirements(false, false, true, false);
        }

        boolean needsCityInfo = false;
        if (!needsProcedure && !needsEvent && !needsNews) {
            needsCityInfo = containsAny(normalizedQuestion, CITY_INFO_KEYWORDS)
                    || detectionIndex.cityInfoMatcher().matches(normalizedQuestion);
        }

        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        boolean finalNeedsProcedure = needsProcedure;
        boolean finalNeedsEvent = needsEvent;
        boolean finalNeedsCityInfo = needsCityInfo;
        logger.fine(() -> "Context detection completed — city=" + cityId
                + " procedure=" + finalNeedsProcedure
                + " event=" + finalNeedsEvent
                + " news=false"
                + " cityInfo=" + finalNeedsCityInfo
                + " durationMs=" + durationMs
                + " procedureTitles=" + detectionIndex.procedureTitleCount()
                + " eventTitles=" + detectionIndex.eventTitleCount()
                + " cityInfoTitles=" + detectionIndex.cityInfoTitleCount());
        return new ContextRequirements(finalNeedsProcedure, finalNeedsEvent, false, finalNeedsCityInfo);
    }

    // -----------------------------------------------------------------------
    // Package-private helpers reused by ClarificationService
    // -----------------------------------------------------------------------

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.strip().toLowerCase(Locale.ROOT);
    }

    static boolean containsAny(String normalizedQuestion, List<String> keywords) {
        for (String keyword : keywords) {
            if (normalizedQuestion.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    static Set<String> tokenize(String normalizedValue) {
        if (normalizedValue == null || normalizedValue.isBlank()) {
            return Set.of();
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        String[] rawTokens = normalizedValue.replaceAll("[^\\p{L}\\p{Nd}]+", " ").trim().split("\\s+");
        for (String token : rawTokens) {
            if (token.length() < 3 || TITLE_STOP_WORDS.contains(token)) {
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private static boolean isClearlyConversational(String normalizedQuestion) {
        return containsAny(normalizedQuestion, CONVERSATIONAL_SHORT_CIRCUITS);
    }

    private static boolean hasDateWindow(String normalizedQuestion, String originalQuestion) {
        if (containsAny(normalizedQuestion, DATE_WINDOW_KEYWORDS)) {
            return true;
        }
        if (containsAny(normalizedQuestion, MONTH_KEYWORDS)) {
            return true;
        }
        if (NUMERIC_DATE_PATTERN.matcher(originalQuestion).find()) {
            return true;
        }
        if (ISO_DATE_PATTERN.matcher(originalQuestion).find()) {
            return true;
        }
        return hasRangeExpressionWithDateTokens(normalizedQuestion, originalQuestion);
    }

    private static boolean hasRangeExpressionWithDateTokens(String normalizedQuestion, String originalQuestion) {
        if (!containsAny(normalizedQuestion, RANGE_CONNECTOR_KEYWORDS)) {
            return false;
        }
        return NUMERIC_DATE_PATTERN.matcher(originalQuestion).find()
                || ISO_DATE_PATTERN.matcher(originalQuestion).find()
                || containsAny(normalizedQuestion, MONTH_KEYWORDS)
                || containsAny(normalizedQuestion, DATE_WINDOW_KEYWORDS);
    }

    private CityDetectionIndex getOrCreateDetectionIndex(String cityId) {
        return detectionIndexByCity.computeIfAbsent(cityId, this::buildDetectionIndex);
    }

    private CityDetectionIndex buildDetectionIndex(String cityId) {
        List<String> normalizedProcedureTitles = loadNormalizedProcedureTitles(cityId);
        List<String> normalizedEventTitles = loadNormalizedEventTitles(cityId);
        List<String> normalizedNewsTitles = loadNormalizedNewsTitles(cityId);
        List<String> normalizedCityInfoTitles = loadNormalizedCityInfoTitles(cityId);

        logger.info(() -> "Context detection index initialized — city=" + cityId
                + " procedureTitles=" + normalizedProcedureTitles.size()
                + " eventTitles=" + normalizedEventTitles.size()
                + " newsTitles=" + normalizedNewsTitles.size()
                + " cityInfoTitles=" + normalizedCityInfoTitles.size());

        return new CityDetectionIndex(
                TitleMatcher.fromTitles(normalizedProcedureTitles),
                TitleMatcher.fromTitles(normalizedEventTitles),
                TitleMatcher.fromTitles(normalizedNewsTitles),
                TitleMatcher.fromTitles(normalizedCityInfoTitles),
                normalizedProcedureTitles.size(),
                normalizedEventTitles.size(),
                normalizedNewsTitles.size(),
                normalizedCityInfoTitles.size());
    }

    private List<String> loadNormalizedProcedureTitles(String cityId) {
        try {
            return ragRegistry.getForCity(cityId).getAll().stream()
                    .map(procedure -> normalize(procedure.title()))
                    .filter(title -> !title.isBlank())
                    .distinct()
                    .toList();
        } catch (Exception e) {
            logger.fine(() -> "Failed to initialize procedure detection titles for city=" + cityId
                    + " error=" + e.getMessage());
            return List.of();
        }
    }

    private List<String> loadNormalizedEventTitles(String cityId) {
        try {
            return eventRagRegistry.getForCity(cityId).getAll().stream()
                    .map(event -> normalize(event.title()))
                    .filter(title -> !title.isBlank())
                    .distinct()
                    .toList();
        } catch (Exception e) {
            logger.fine(() -> "Failed to initialize event detection titles for city=" + cityId
                    + " error=" + e.getMessage());
            return List.of();
        }
    }

    private List<String> loadNormalizedNewsTitles(String cityId) {
        try {
            return newsRagRegistry.getForCity(cityId).getAll().stream()
                    .map(item -> normalize(item.title()))
                    .filter(title -> !title.isBlank())
                    .distinct()
                    .toList();
        } catch (Exception e) {
            logger.fine(() -> "Failed to initialize news detection titles for city=" + cityId
                    + " error=" + e.getMessage());
            return List.of();
        }
    }

    private List<String> loadNormalizedCityInfoTitles(String cityId) {
        try {
            return cityInfoRagRegistry.getForCity(cityId).getAll().stream()
                    .map(item -> normalize(item.title()))
                    .filter(title -> !title.isBlank())
                    .distinct()
                    .toList();
        } catch (Exception e) {
            logger.fine(() -> "Failed to initialize city-info detection titles for city=" + cityId
                    + " error=" + e.getMessage());
            return List.of();
        }
    }

    // -----------------------------------------------------------------------
    // Inner types
    // -----------------------------------------------------------------------

    private record CityDetectionIndex(TitleMatcher procedureMatcher, TitleMatcher eventMatcher,
                                      TitleMatcher newsMatcher, TitleMatcher cityInfoMatcher,
                                      int procedureTitleCount, int eventTitleCount, int newsTitleCount,
                                      int cityInfoTitleCount) {
    }

    private record TitleMatcher(Map<String, List<String>> titlesByToken, List<String> fallbackTitles) {

        private static TitleMatcher fromTitles(List<String> normalizedTitles) {
                Map<String, LinkedHashSet<String>> byToken = new LinkedHashMap<>();
                List<String> fallbackTitles = new ArrayList<>();

                for (String normalizedTitle : normalizedTitles) {
                    Set<String> tokens = tokenize(normalizedTitle);
                    if (tokens.isEmpty()) {
                        fallbackTitles.add(normalizedTitle);
                        continue;
                    }
                    for (String token : tokens) {
                        byToken.computeIfAbsent(token, ignored -> new LinkedHashSet<>()).add(normalizedTitle);
                    }
                }

                Map<String, List<String>> immutableIndex = new LinkedHashMap<>();
                byToken.forEach((token, titles) -> immutableIndex.put(token, List.copyOf(titles)));
                return new TitleMatcher(Map.copyOf(immutableIndex), List.copyOf(fallbackTitles));
            }

            private boolean matches(String normalizedQuestion) {
                if (normalizedQuestion == null || normalizedQuestion.isBlank()) {
                    return false;
                }
                LinkedHashSet<String> candidates = new LinkedHashSet<>();
                for (String token : tokenize(normalizedQuestion)) {
                    List<String> titles = titlesByToken.get(token);
                    if (titles != null) {
                        candidates.addAll(titles);
                    }
                }
                if (candidates.isEmpty()) {
                    candidates.addAll(fallbackTitles);
                }
                for (String candidate : candidates) {
                    if (normalizedQuestion.contains(candidate)) {
                        return true;
                    }
                }
                return false;
            }
        }
}
