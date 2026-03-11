package cat.complai.openrouter.dto;

import io.micronaut.core.annotation.Introspected;

/**
 * Response body for the {@code 202 Accepted} response returned when a complaint letter
 * generation request has been successfully queued.
 *
 * <p>The {@code pdfUrl} is a pre-signed S3 GET URL (24-hour expiry) pointing to the key
 * where the worker Lambda will upload the generated PDF. The URL is valid immediately
 * but the object does not exist until the worker finishes — clients should poll with
 * a short back-off and handle a 403/404 gracefully while the PDF is being generated.
 */
@Introspected
public record RedactAcceptedDto(
        boolean success,
        String message,
        String pdfUrl,
        int errorCode
) {}

