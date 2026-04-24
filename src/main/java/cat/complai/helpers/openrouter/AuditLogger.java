package cat.complai.helpers.openrouter;

import java.time.Instant;
import java.util.logging.Logger;

/**
 * AuditLogger writes structured, privacy-preserving audit logs for every /ask
 * and /redact request.
 * Only metadata is logged (never full text or AI response).
 */
public class AuditLogger {
    private static final Logger logger = Logger.getLogger("AuditLogger");

    public static void log(String endpoint, String requestHash, int errorCode, long latencyMs, String outputFormat,
            String language) {
        // Compose a structured log line (JSON-like, but not using a JSON lib for
        // simplicity)
        String logLine = String.format(
                "{\"ts\":\"%s\",\"endpoint\":\"%s\",\"requestHash\":\"%s\",\"errorCode\":%d,\"latencyMs\":%d,\"outputFormat\":\"%s\",\"language\":\"%s\"}",
                Instant.now().toString(), endpoint, requestHash, errorCode, latencyMs,
                outputFormat == null ? "" : outputFormat, language == null ? "" : language);
        logger.info(logLine);
    }

    /**
     * Hashes the request text for privacy. Use a simple hash (not cryptographic) to
     * avoid logging PII.
     */
    public static String hashText(String text) {
        if (text == null)
            return "null";
        return Integer.toHexString(text.hashCode());
    }
}
