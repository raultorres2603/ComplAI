package cat.complai.auth;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * CLI utility that generates a cryptographically strong, URL-safe API key for a given city.
 *
 * <p>Usage: {@code java -cp complai-all.jar cat.complai.auth.ApiKeyGenerator <cityId>}
 *
 * <p>The generated key is printed to stdout so it can be captured by {@code $(...)} in shell
 * scripts. A human-readable note with the corresponding environment variable name is printed
 * to stderr.
 */
public class ApiKeyGenerator {

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: ApiKeyGenerator <cityId>");
            System.err.println("  cityId — lowercase city identifier (e.g. elprat)");
            System.exit(1);
        }

        String cityId = args[0];
        if (cityId == null || cityId.isBlank()) {
            System.err.println("Error: cityId must not be blank.");
            System.exit(1);
        }

        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        System.out.println(key);

        assert cityId != null : "cityId should not be null at this point";
        String cityIdUpper = cityId.toUpperCase();
        System.err.println("Generated API key for city '" + cityId + "'.");
        System.err.println("Set environment variable: API_KEY_" + cityIdUpper + "=" + key);
    }
}
