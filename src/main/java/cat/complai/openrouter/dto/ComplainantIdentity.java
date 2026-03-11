package cat.complai.openrouter.dto;

import io.micronaut.core.annotation.Introspected;

/**
 * Identity information for the person submitting a formal complaint.
 * All three fields are required: the Ajuntament will not process anonymous complaints,
 * and the AI needs them to produce a valid, addressed letter.
 *
 * Immutability is intentional — identity data must not be modified after construction.
 */
@Introspected
public record ComplainantIdentity(String name, String surname, String idNumber) {

    /**
     * Returns true only when all three fields are present and non-blank.
     * A partial identity is treated the same as no identity: the AI must ask for the missing fields.
     */
    public boolean isComplete() {
        return isPresent(name) && isPresent(surname) && isPresent(idNumber);
    }

    /**
     * Returns true when at least one field has been provided.
     * Used to distinguish "user provided nothing" from "user provided some fields".
     */
    public boolean isPartiallyProvided() {
        return isPresent(name) || isPresent(surname) || isPresent(idNumber);
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}

