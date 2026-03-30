# ComplAI - Project Instructions & Tech Stack

## Tech Stack
- **Backend Framework**: Java 25 with Micronaut
- **Build Tool**: Gradle
- **Infrastructure as Code (IaC)**: AWS CDK (TypeScript)
- **AWS Services**: SQS (Worker Poller, Complaint Publisher), S3 (PDF Uploader), Lambda
- **Testing**: JUnit 5, Mockito for Unit/Integration tests. Bruno (`.bru` files) for E2E HTTP testing.
- **External Integrations**: OpenRouter AI (for AI Response Processing), Web Scraping tools.
- **Core Libraries**: Lucene (RAG search), Caffeine (conversation cache), PDFBox (PDF generation), JJWT (JWT validation).

## Code Style
- **Java**: Follow standard Java conventions. Use Micronaut's recommended patterns for dependency injection (constructor injection only), configuration, and testing. Adhere to the layered architecture (Controllers, Services, Repositories).
- **CDK**: Use TypeScript with CDK best practices. Define clear constructs for each infrastructure component. Use environment variables for configuration and secrets management.
- **Documentation**: Maintain clear Javadoc comments for all public methods and classes. Use `README.md` files to document module-level information and setup instructions.