package cat.complai.controllers.ses;

import cat.complai.config.SesRecipientProvider;
import cat.complai.config.SesSenderConfig;
import cat.complai.services.ses.IEmailService;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

import static org.mockito.Mockito.*;

/**
 * Test factory for SesController tests.
 * Provides mock/stub beans to avoid circular dependencies and AWS calls.
 */
@Factory
public class SesControllerTestFactory {

    private static final String RECIPIENT_EMAIL = "admin@test.com";

    @Primary
    @Singleton
    @Replaces(IEmailService.class)
    public IEmailService emailService() {
        // Return a simple mock that does nothing (success case)
        return mock(IEmailService.class);
    }

    @Primary
    @Singleton
    @Replaces(SesRecipientProvider.class)
    public SesRecipientProvider sesRecipientProvider() {
        SesRecipientProvider provider = mock(SesRecipientProvider.class);
        when(provider.getRecipientEmail()).thenReturn(RECIPIENT_EMAIL);
        return provider;
    }

    @Primary
    @Singleton
    @Replaces(SesSenderConfig.class)
    public SesSenderConfig sesSenderConfig() {
        SesSenderConfig config = mock(SesSenderConfig.class);
        when(config.getFromEmail()).thenReturn("noreply@test.com");
        when(config.getRegion()).thenReturn("eu-west-1");
        return config;
    }
}