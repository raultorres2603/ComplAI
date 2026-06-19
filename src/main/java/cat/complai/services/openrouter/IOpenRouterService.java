package cat.complai.services.openrouter;

/**
 * Composite interface that aggregates all three role interfaces.
 *
 * <p>{@link IAskService}, {@link IRedactService}, and {@link IStreamingService}
 * each carry a single responsibility. Clients that need only a subset of these
 * operations should inject the specific role interface instead of this composite.
 *
 * @deprecated Prefer the role-specific interfaces. This composite is kept for
 *             backward compatibility with clients that temporarily need all
 *             three capabilities.
 */
@Deprecated
public interface IOpenRouterService extends IAskService, IRedactService, IStreamingService {
}
