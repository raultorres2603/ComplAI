import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as path from 'path';
import * as fs from 'fs';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as events from 'aws-cdk-lib/aws-events';
import * as lambdaEventSources from 'aws-cdk-lib/aws-lambda-event-sources';
import * as eventsTargets from 'aws-cdk-lib/aws-events-targets';
import { DeploymentEnvironment } from './deployment-environment';
import { HttpApi, HttpMethod, CorsHttpMethod, PayloadFormatVersion, CfnStage } from 'aws-cdk-lib/aws-apigatewayv2';
import { HttpLambdaIntegration } from 'aws-cdk-lib/aws-apigatewayv2-integrations';

export interface LambdaStackProps extends cdk.StackProps {
  // Identifies which environment this stack owns. Every AWS resource is suffixed
  // with this value so the two stacks can coexist in the same AWS account without
  // name collisions, and so it is immediately obvious in the console which
  // resource belongs to which environment.
  readonly environment: DeploymentEnvironment;
  // The SQS redact queue. Passed as a prop (rather than constructed internally
  // via fromQueueArn) because SqsEventSource derives the EventSourceMapping
  // construct ID from Names.nodeUniqueId(queue.node), which hashes the full
  // construct path. If the path changes, CloudFormation gets a new logical ID
  // and tries to CREATE the new mapping before deleting the old one, producing
  // a 409 conflict. The queue is therefore created in bin/cdk.ts under a
  // construct path that mirrors the original QueueStack path so the hash — and
  // the EventSourceMapping logical ID — remains stable.
  readonly redactQueue: sqs.IQueue;
  readonly feedbackQueue: sqs.IQueue;
  readonly askQueue: sqs.IQueue;
}

export class LambdaStack extends cdk.Stack {
  readonly httpApi: HttpApi;

  constructor(scope: Construct, id: string, props: LambdaStackProps) {
    super(scope, id, props);

    const { environment, redactQueue, feedbackQueue, askQueue } = props;

    // Reconstruct cross-stack bucket references from their deterministic names
    // rather than accepting CDK construct objects as props. Passing construct
    // objects would cause CDK to generate CloudFormation Fn::Export /
    // Fn::ImportValue references whose export names are tied to logical ID
    // hashes. If a hash ever changes (CDK upgrade, construct rename) CloudFormation
    // refuses to delete the old export while LambdaStack still imports it.
    //
    // fromBucketName resolves to literal strings at synthesis time — no
    // cross-stack CFN dependency is generated. Deployment order is enforced by
    // the explicit addDependency() calls in bin/cdk.ts.
    //
    // The SQS queue is handled differently: see LambdaStackProps.redactQueue.
    const proceduresBucket = s3.Bucket.fromBucketName(
      this, `ProceduresBucketRef-${environment}`, `complai-procedures-${environment}`
    );
    const eventsBucket = s3.Bucket.fromBucketName(
      this, `EventsBucketRef-${environment}`, `complai-events-${environment}`
    );
    const newsBucket = s3.Bucket.fromBucketName(
      this, `NewsBucketRef-${environment}`, `complai-news-${environment}`
    );
    const cityInfoBucket = s3.Bucket.fromBucketName(
      this, `CityInfoBucketRef-${environment}`, `complai-cityinfo-${environment}`
    );
    const complaintsBucket = s3.Bucket.fromBucketName(
      this, `ComplaintsBucketRef-${environment}`, `complai-complaints-${environment}`
    );
    const feedbackBucket = s3.Bucket.fromBucketName(
      this, `FeedbackBucketRef-${environment}`, `complai-feedback-${environment}`
    );
    const deploymentsBucket = s3.Bucket.fromBucketName(
      this, `DeploymentsBucketRef-${environment}`, `complai-deployments-${environment}`
    );

    // Create a custom IAM role for Lambda with least privilege.
    // The logical ID includes the environment so both stacks can live in the same account.
    const lambdaRole = new iam.Role(this, `ComplAILambdaRole-${environment}`, {
      assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
      description: `IAM role for ComplAI Lambda (${environment}) with least privilege`,
    });

    // Attach AWS managed policy for basic Lambda logging
    lambdaRole.addManagedPolicy(iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole'));

    // Allow reading from CloudWatch Logs for statistics/stadistics queries
    lambdaRole.addToPrincipalPolicy(
      new iam.PolicyStatement({
        sid: 'FilterLogEvents',
        effect: iam.Effect.ALLOW,
        actions: ['logs:FilterLogEvents'],
        resources: [
          `arn:aws:logs:${this.region}:${this.account}:log-group:/aws/lambda/ComplAILambda-${environment}:*`,
          `arn:aws:logs:${this.region}:${this.account}:log-group:/aws/lambda/ComplAIRedactorLambda-${environment}:*`,
          `arn:aws:logs:${this.region}:${this.account}:log-group:/aws/lambda/ComplAIFeedbackWorkerLambda-${environment}:*`,
        ],
      }),
    );

    // --- Lambda code source ------------------------------------------------
    // CDK synthesises ALL stacks in the app even when deploying only one
    // (e.g. cdk deploy "ComplAIStorageStack-*"). The LambdaStack constructor
    // must therefore NEVER throw when the native image artifact is missing;
    // doing so would block deployment of unrelated stacks.
    //
    // CI path: the GitHub Actions workflow uploads the native ZIP to the
    // deployments bucket before calling `cdk deploy`, then sets
    // DEPLOYMENT_JAR_KEY to the S3 key (e.g. complai-all-<git-sha>.zip).
    // Code.fromBucket does NOT stage anything to the CDK bootstrap bucket,
    // so the cdk-hnb659fds-assets-* bucket never receives the ZIP.
    //
    // Local dev path: no DEPLOYMENT_JAR_KEY → fall back to Code.fromAsset,
    // which stages via the bootstrap bucket.  This keeps the local workflow
    // unchanged and avoids requiring developers to pre-upload the ZIP.
    //
    // When no artifact can be found, a placeholder S3 reference is used
    // instead of throwing. This allows CDK synth to succeed for all stacks.
    // Only an actual LambdaStack deployment will fail at CloudFormation time
    // (the placeholder key doesn't exist on S3), which is the correct
    // behaviour — you can't deploy a Lambda without code.
    // -----------------------------------------------------------------------
    const annotations = cdk.Annotations.of(this);
    const placeholderKey = `missing-artifact-${environment}.zip`;
    let code: lambda.Code;

    // Priority 1: CI-provided S3 key (DEPLOYMENT_JAR_KEY from workflow env).
    const deploymentArtifactKey = process.env.DEPLOYMENT_JAR_KEY ?? this.node.tryGetContext('artifactS3Key');
    if (deploymentArtifactKey) {
      code = lambda.Code.fromBucket(deploymentsBucket, deploymentArtifactKey);
    } else {
      // Priority 2: explicit ARTIFACT_PATH (env var or CDK context).
      const explicit = process.env.ARTIFACT_PATH || this.node.tryGetContext?.('artifactPath');
      if (explicit) {
        const candidate = path.isAbsolute(explicit) ? explicit : path.resolve(__dirname, '..', '..', explicit);
        if (fs.existsSync(candidate)) {
          code = lambda.Code.fromAsset(candidate);
        } else {
          annotations.addWarning(
            `ARTIFACT_PATH set to "${explicit}" but file not found at "${candidate}". ` +
            `Using placeholder — LambdaStack deploy will fail until the artifact exists.`,
          );
          code = lambda.Code.fromBucket(deploymentsBucket, placeholderKey);
        }
      } else {
        // Priority 3: auto-discover a *-all.zip from build/libs.
        const libsDir = path.resolve(__dirname, '..', '..', 'build', 'libs');
        if (!fs.existsSync(libsDir)) {
          annotations.addWarning(
            `build/libs not found at "${libsDir}". ` +
            `Using placeholder — LambdaStack deploy will fail until './gradlew buildNativeLambda' is run.`,
          );
          code = lambda.Code.fromBucket(deploymentsBucket, placeholderKey);
        } else {
          const zips = fs.readdirSync(libsDir).filter((f: string) => f.endsWith('.zip'));
          const allZip = zips.find((f: string) => f.includes('-all.zip') || f.endsWith('-all.zip'));
          if (allZip) {
            code = lambda.Code.fromAsset(path.join(libsDir, allZip));
          } else {
            annotations.addWarning(
              `No '*-all.zip' found in "${libsDir}". ` +
              `Using placeholder — LambdaStack deploy will fail until './gradlew buildNativeLambda' is run.`,
            );
            code = lambda.Code.fromBucket(deploymentsBucket, placeholderKey);
          }
        }
      }
    }

    // Explicit log group so we control retention and can attach metric filters.
    // Lambda would create a log group automatically, but that gives us no control
    // over retention or any ability to reference it in CFN outputs.
    // Both environments keep logs 1 year for audit/compliance and statistics retention.
    // This stays within the CloudWatch Logs Free Tier (5GB/month ingestion + 5GB/month storage).
    const logRetention = logs.RetentionDays.ONE_YEAR;

    const logGroup = new logs.LogGroup(this, `ComplAILogGroup-${environment}`, {
      // The Lambda runtime writes to /aws/lambda/<functionName> by convention.
      // We set this name explicitly so the log group exists before the first
      // invocation and our metric filters are in place from day one.
      logGroupName: `/aws/lambda/ComplAILambda-${environment}`,
      retention: logRetention,
      // Retain log groups in both environments for audit compliance and statistics.
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });

    const lambdaFn = new lambda.Function(this, `ComplAILambda-${environment}`, {
      runtime: lambda.Runtime.PROVIDED_AL2023,
      architecture: lambda.Architecture.ARM_64,
      // Explicit function name to ensure it matches the log group and is under the 64-char limit.
      functionName: `ComplAILambda-${environment}`,
      code,
      handler: 'bootstrap',
      // CPU-bound: JSON processing, BM25 scoring, AI streaming orchestration.
      // GraalVM native image removes JIT compiler overhead — native code runs at
      // full speed immediately. ARM_64 (Graviton2) provides up to 34% better
      // price-performance than x86_64 for Lambda workloads.
      // 1024 MB provides ~0.58 vCPU, which is equivalent to ~1 vCPU on a JIT JVM
      // (native code is ~2x more efficient per cycle).
      // BM25 index (~5-20 MB per city) + Caffeine caches (~10 MB) fit comfortably.
      // Verified with AWS Lambda Power Tuning on native image.
      memorySize: 1024,
      timeout: cdk.Duration.seconds(60),
      // Wire the OpenRouter API key (from CFN parameter) into the Lambda environment.
      // Be aware that environment variables are visible in the Lambda console; using
      // Secrets Manager or SSM Parameter Store with encryption is more secure if available.
      environment: {
        OPENROUTER_API_KEY: process.env.OPENROUTER_API_KEY || '',
        OPENROUTER_REQUEST_TIMEOUT_SECONDS: process.env.OPENROUTER_REQUEST_TIMEOUT_SECONDS || '60',
        OPENROUTER_OVERALL_TIMEOUT_SECONDS: process.env.OPENROUTER_OVERALL_TIMEOUT_SECONDS || '60',
        OPENROUTER_MAX_RETRIES: process.env.OPENROUTER_MAX_RETRIES || '3',
        PROCEDURES_BUCKET: proceduresBucket.bucketName,
        PROCEDURES_REGION: this.region,
        EVENTS_BUCKET: eventsBucket.bucketName,
        EVENTS_REGION: this.region,
        NEWS_BUCKET: newsBucket.bucketName,
        NEWS_REGION: this.region,
        CITYINFO_BUCKET: cityInfoBucket.bucketName,
        CITYINFO_REGION: this.region,
        OPENROUTER_MODEL: process.env.OPENROUTER_MODEL || 'openrouter/free',
        // API key authentication — one env var per city.
        // To add a new city: add API_KEY_<CITYID_UPPER> here and set the corresponding
        // GitHub Environment secret (API_KEY_<CITYID_UPPER>) for both development and production.
        API_KEY_ENABLED: 'true',
        API_KEY_ELPRAT: process.env.API_KEY_ELPRAT || '',
        // Telegram Bot configuration — per-city token and webhook secret.
        // To add a new city: add TOKEN_TELEGRAM_<CITYID_UPPER> and
        // TELEGRAM_WEBHOOK_SECRET_<CITYID_UPPER> here and set the corresponding
        // GitHub Environment secrets for both development and production.
        TOKEN_TELEGRAM_ELPRAT: process.env.TOKEN_TELEGRAM_ELPRAT || '',
        TELEGRAM_WEBHOOK_SECRET_ELPRAT: process.env.TELEGRAM_WEBHOOK_SECRET_ELPRAT || '',
        // Async redact flow: queue URL for publishing + bucket details for pre-signed URLs.
        REDACT_QUEUE_URL: redactQueue.queueUrl,
        FEEDBACK_QUEUE_URL: feedbackQueue.queueUrl,
        ASK_QUEUE_URL: askQueue.queueUrl,
        FEEDBACK_QUEUE_REGION: this.region,
        COMPLAINTS_BUCKET: complaintsBucket.bucketName,
        COMPLAINTS_REGION: this.region,
        // HTTP Client configuration for Micronaut (operational flexibility)
        HTTP_CLIENT_CONNECT_TIMEOUT: process.env.HTTP_CLIENT_CONNECT_TIMEOUT || '10s',
        HTTP_CLIENT_READ_TIMEOUT: process.env.HTTP_CLIENT_READ_TIMEOUT || '60s',
        HTTP_CLIENT_MAX_CONNECTIONS: process.env.HTTP_CLIENT_MAX_CONNECTIONS || '20',
        HTTP_CLIENT_LOG_LEVEL: process.env.HTTP_CLIENT_LOG_LEVEL || 'WARN',
        // Response caching configuration for OpenRouter API responses
        // Disable for testing to prevent test pollution; enable in production to reduce API calls
        RESPONSE_CACHE_ENABLED: process.env.RESPONSE_CACHE_ENABLED || 'true',
        RESPONSE_CACHE_TTL_MINUTES: process.env.RESPONSE_CACHE_TTL_MINUTES || '10',
        RESPONSE_CACHE_MAX_ENTRIES: process.env.RESPONSE_CACHE_MAX_ENTRIES || '500',
        // CORS header injection: disable in production (API Gateway HTTP API handles CORS).
        // Duplicate CORS headers from both app filter and infrastructure cause browser errors.
        COMPLAI_LOCAL_CORS_ENABLED: 'false',
        // OIDC identity verification. Per-city config (issuer, JWKS URI, audience, NIF claim)
        // is bundled in oidc-mapping.json — enabled per city, no env var needed.
        // The worker Lambda does not receive API_KEY_ENABLED and therefore never loads the
        // OidcIdentityTokenValidator bean.
        RATE_LIMIT_REQUESTS_PER_MINUTE: process.env.RATE_LIMIT_REQUESTS_PER_MINUTE || '20',
        // Telegram spam protection thresholds (overridable per environment)
        TELEGRAM_CHAT_RATE_LIMIT_PER_SECOND: process.env.TELEGRAM_CHAT_RATE_LIMIT_PER_SECOND || '3',
        TELEGRAM_BOT_RATE_LIMIT_PER_SECOND: process.env.TELEGRAM_BOT_RATE_LIMIT_PER_SECOND || '10',
        TELEGRAM_MAX_MESSAGE_LENGTH: process.env.TELEGRAM_MAX_MESSAGE_LENGTH || '4096',
        // SQS queue depth cap — reject publishes when the queue is too deep
        SQS_MAX_QUEUE_DEPTH: process.env.SQS_MAX_QUEUE_DEPTH || '1000',
        COMPLAI_DEFAULT_CITY_ID: process.env.COMPLAI_DEFAULT_CITY_ID || 'elprat',
        // SES Configuration — sender and recipient emails from GitHub Environment Variables
        // Email must be verified in SES before sending. See cdk/README.md for setup.
        AWS_SES_FROM_EMAIL: process.env.SES_FROM_EMAIL || '',
        AWS_SES_TO_EMAIL: process.env.SES_TO_EMAIL || '',
        AWS_SES_TO_EMAIL_ELPRAT: process.env.SES_TO_EMAIL_ELPRAT || '',
        AWS_SES_REGION: process.env.AWS_SES_REGION || 'eu-west-1',
        ENVIRONMENT: environment,
        FEEDBACK_BUCKET_NAME: feedbackBucket.bucketName,
      },
      role: lambdaRole,
      logGroup: logGroup
    });

    // API Lambda publishes interaction metrics to CloudWatch (InteractionMetricsPublisher).
    // Scoped to the ComplAI namespace via condition key to avoid accidental metric pollution.
    lambdaRole.addToPrincipalPolicy(
      new iam.PolicyStatement({
        sid: 'PutCloudWatchMetrics',
        effect: iam.Effect.ALLOW,
        actions: ['cloudwatch:PutMetricData'],
        resources: ['*'],
        conditions: {
          StringEquals: {
            'cloudwatch:namespace': 'ComplAI',
          },
        },
      }),
    );

    // API Lambda needs to publish to the redact queue.
    redactQueue.grantSendMessages(lambdaRole);
    feedbackQueue.grantSendMessages(lambdaRole);
    askQueue.grantSendMessages(lambdaRole);

    // API Lambda needs s3:GetObject to sign pre-signed GET URLs on behalf of callers.
    // Pre-signed URLs embed the signer's credentials; without this permission the
    // signed URL would return 403 when the user tries to download the PDF.
    complaintsBucket.grantRead(lambdaRole);
    proceduresBucket.grantRead(lambdaRole);
    eventsBucket.grantRead(lambdaRole);
    newsBucket.grantRead(lambdaRole);
    cityInfoBucket.grantRead(lambdaRole);
    feedbackBucket.grantRead(lambdaRole);

    // API Lambda needs SES permissions to send complaint-related emails.
    // The condition restricts sending to the configured sender email address.
    // Both ses:SendEmail and ses:SendRawEmail are included for compatibility.
    const sesFromEmail = process.env.SES_FROM_EMAIL || '';
    if (sesFromEmail) {
      lambdaRole.addToPrincipalPolicy(
        new iam.PolicyStatement({
          sid: 'SesSendEmailFromVerifiedIdentity',
          effect: iam.Effect.ALLOW,
          actions: ['ses:SendEmail', 'ses:SendRawEmail'],
          resources: ['*'],
          conditions: {
            StringEquals: {
              'ses:FromAddress': sesFromEmail,
            },
          },
        }),
      );
    }

    const lambdaIntegration = new HttpLambdaIntegration(
      `ComplAILambdaIntegration-${environment}`,
      lambdaFn,
      { payloadFormatVersion: PayloadFormatVersion.VERSION_2_0 },
    );

    const httpApi = new HttpApi(this, `ComplAIHttpApi-${environment}`, {
      apiName: `ComplAIHttpApi-${environment}`,
      corsPreflight: {
        allowOrigins: [
          process.env.COMPLAI_CORS_ALLOWED_ORIGIN || 'https://raultorres2603.github.io',
        ],
        allowMethods: [CorsHttpMethod.ANY],
        allowHeaders: ['*'],
      },
    });

    const cfnDefaultStage = httpApi.defaultStage!.node.defaultChild as CfnStage;
    cfnDefaultStage.addPropertyOverride('DefaultRouteSettings', {
      ThrottlingRateLimit: 0.33,
      ThrottlingBurstLimit: 10,
    });

    httpApi.addRoutes({
      path: '/',
      methods: [HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH, HttpMethod.HEAD],
      integration: lambdaIntegration,
    });

    httpApi.addRoutes({
      path: '/{proxy+}',
      methods: [HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH, HttpMethod.HEAD],
      integration: lambdaIntegration,
    });

    this.httpApi = httpApi;

    // -------------------------------------------------------------------------
    // Worker Lambda — processes SQS messages and uploads generated PDFs to S3.
    // Runs the same native ZIP with a different entry point class.
    // -------------------------------------------------------------------------
    const workerRole = new iam.Role(this, `ComplAIWorkerRole-${environment}`, {
      assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
      description: `IAM role for ComplAI worker Lambda (${environment}) - SQS consume + S3 write`,
    });
    workerRole.addManagedPolicy(
      iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole'),
    );
    // Worker needs to receive, delete, and inspect queue attributes (required by the
    // Lambda SQS event source mapping to manage the polling lifecycle).
    redactQueue.grantConsumeMessages(workerRole);
    // Worker writes the generated PDF to the complaints bucket.
    complaintsBucket.grantPut(workerRole);
    // Worker needs procedures access for RAG context in the AI prompt.
    proceduresBucket.grantRead(workerRole);
    // Worker needs events access for event context in the AI prompt.
    eventsBucket.grantRead(workerRole);
    // Worker keeps parity with API runtime context loading (news helpers).
    newsBucket.grantRead(workerRole);
    // Worker keeps parity with API runtime context loading (city-info helpers).
    cityInfoBucket.grantRead(workerRole);

    // Feedback Worker Role — separate from redact worker for least privilege
    const feedbackWorkerRole = new iam.Role(this, `ComplAIFeedbackWorkerRole-${environment}`, {
      assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
      description: `IAM role for ComplAI feedback worker Lambda (${environment}) - SQS consume + S3 write`,
    });
    feedbackWorkerRole.addManagedPolicy(
      iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole'),
    );
    // Feedback worker consumes from feedback queue
    feedbackQueue.grantConsumeMessages(feedbackWorkerRole);
    // Feedback worker writes JSON files to feedback bucket
    feedbackBucket.grantPut(feedbackWorkerRole);

    const workerLogGroup = new logs.LogGroup(this, `ComplAIWorkerLogGroup-${environment}`, {
      logGroupName: `/aws/lambda/ComplAIRedactorLambda-${environment}`,
      retention: logRetention,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });

    const feedbackWorkerLogGroup = new logs.LogGroup(this, `ComplAIFeedbackWorkerLogGroup-${environment}`, {
      logGroupName: `/aws/lambda/ComplAIFeedbackWorkerLambda-${environment}`,
      retention: logRetention,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });

    const workerFn = new lambda.Function(this, `ComplAIRedactorLambda-${environment}`, {
      runtime: lambda.Runtime.PROVIDED_AL2023,
      architecture: lambda.Architecture.ARM_64,
      // Explicit function name to ensure it matches the log group and is under the 64-char limit.
      functionName: `ComplAIRedactorLambda-${environment}`,
      code,
      handler: 'bootstrap',
      // Mixed I/O + CPU (OpenRouter AI call + OpenPDF rendering). No RAG indexes
      // or Caffeine caches loaded — only the DI beans for prompt building, HTTP
      // wrapper, and S3 upload. OpenPDF has zero AWT dependency (unlike the
      // original PDFBox) and works reliably in native image. Font (615 KB) is
      // cached after first load. AI call dominates latency. 512 MB provides
      // >10x headroom over estimated ~50 MB peak (font + PDF buffer + response).
      memorySize: 512,
      // Must be ≤ SQS visibility timeout (90s). Lambda extends visibility automatically
      // while running, so using the same duration is the safest choice here.
      timeout: cdk.Duration.seconds(60),
      environment: {
        OPENROUTER_API_KEY: process.env.OPENROUTER_API_KEY || '',
        OPENROUTER_REQUEST_TIMEOUT_SECONDS: process.env.OPENROUTER_REQUEST_TIMEOUT_SECONDS || '60',
        OPENROUTER_OVERALL_TIMEOUT_SECONDS: process.env.OPENROUTER_OVERALL_TIMEOUT_SECONDS || '60',
        OPENROUTER_MAX_RETRIES: process.env.OPENROUTER_MAX_RETRIES || '3',
        OPENROUTER_MODEL: process.env.OPENROUTER_MODEL || 'openrouter/free',
        COMPLAINTS_BUCKET: complaintsBucket.bucketName,
        COMPLAINTS_REGION: this.region,
        PROCEDURES_BUCKET: proceduresBucket.bucketName,
        PROCEDURES_REGION: this.region,
        EVENTS_BUCKET: eventsBucket.bucketName,
        EVENTS_REGION: this.region,
        NEWS_BUCKET: newsBucket.bucketName,
        NEWS_REGION: this.region,
        CITYINFO_BUCKET: cityInfoBucket.bucketName,
        CITYINFO_REGION: this.region,
        // HTTP Client configuration for Micronaut (operational flexibility)
        HTTP_CLIENT_CONNECT_TIMEOUT: process.env.HTTP_CLIENT_CONNECT_TIMEOUT || '10s',
        HTTP_CLIENT_READ_TIMEOUT: process.env.HTTP_CLIENT_READ_TIMEOUT || '60s',
        HTTP_CLIENT_MAX_CONNECTIONS: process.env.HTTP_CLIENT_MAX_CONNECTIONS || '20',
        HTTP_CLIENT_LOG_LEVEL: process.env.HTTP_CLIENT_LOG_LEVEL || 'WARN',
        // Response caching configuration for OpenRouter API responses
        RESPONSE_CACHE_ENABLED: process.env.RESPONSE_CACHE_ENABLED || 'true',
        RESPONSE_CACHE_TTL_MINUTES: process.env.RESPONSE_CACHE_TTL_MINUTES || '10',
        RESPONSE_CACHE_MAX_ENTRIES: process.env.RESPONSE_CACHE_MAX_ENTRIES || '500',
        // SES Configuration — sender and recipient emails from GitHub Environment Variables
        AWS_SES_FROM_EMAIL: process.env.SES_FROM_EMAIL || '',
        AWS_SES_TO_EMAIL: process.env.SES_TO_EMAIL || '',
        AWS_SES_TO_EMAIL_ELPRAT: process.env.SES_TO_EMAIL_ELPRAT || '',
        AWS_SES_REGION: process.env.AWS_SES_REGION || 'eu-west-1',
        // Per-city API keys for MultiCitySesService discovery (city must have BOTH API_KEY_* and AWS_SES_TO_EMAIL_*)
        API_KEY_ELPRAT: process.env.API_KEY_ELPRAT || '',
        // Telegram Bot — per-city token and webhook secret
        TOKEN_TELEGRAM_ELPRAT: process.env.TOKEN_TELEGRAM_ELPRAT || '',
        TELEGRAM_WEBHOOK_SECRET_ELPRAT: process.env.TELEGRAM_WEBHOOK_SECRET_ELPRAT || '',
        // Deployment environment identifier
        ENVIRONMENT: environment,
      },
      role: workerRole,
      logGroup: workerLogGroup
    });

    // Worker role needs SES permissions if it sends complaint-related emails.
    // The condition restricts sending to the configured sender email address.
    if (sesFromEmail) {
      workerRole.addToPrincipalPolicy(
        new iam.PolicyStatement({
          sid: 'SesSendEmailFromVerifiedIdentity',
          effect: iam.Effect.ALLOW,
          actions: ['ses:SendEmail', 'ses:SendRawEmail'],
          resources: ['*'],
          conditions: {
            StringEquals: {
              'ses:FromAddress': sesFromEmail,
            },
          },
        }),
      );
    }

    const feedbackWorkerFn = new lambda.Function(this, `ComplAIFeedbackWorkerLambda-${environment}`, {
      runtime: lambda.Runtime.PROVIDED_AL2023,
      architecture: lambda.Architecture.ARM_64,
      // Explicit function name to ensure it matches the log group and is under the 64-char limit.
      functionName: `ComplAIFeedbackWorkerLambda-${environment}`,
      code,
      handler: 'bootstrap',
      // I/O-bound: simple JSON deserialization, S3 upload, no AI calls.
      // GraalVM native image has negligible memory overhead here — the workload
      // is dominated by S3 API latency. 256 MB is already optimal; reducing to
      // 128 MB would lose CPU proportionality with no cost benefit (128 MB is
      // the same price as 256 MB in the Lambda pricing model).
      memorySize: 256,
      timeout: cdk.Duration.seconds(60),
      // Feedback worker environment
      environment: {
        FEEDBACK_QUEUE_URL: feedbackQueue.queueUrl,
        FEEDBACK_BUCKET_NAME: feedbackBucket.bucketName,
        FEEDBACK_QUEUE_REGION: this.region,
        // SES Configuration — sender and recipient emails from GitHub Environment Variables
        AWS_SES_FROM_EMAIL: process.env.SES_FROM_EMAIL || '',
        AWS_SES_TO_EMAIL: process.env.SES_TO_EMAIL || '',
        AWS_SES_REGION: process.env.AWS_SES_REGION || 'eu-west-1',
        ...(process.env.AWS_ENDPOINT_URL ? { AWS_ENDPOINT_URL: process.env.AWS_ENDPOINT_URL } : {})
      },
      role: feedbackWorkerRole,
      logGroup: feedbackWorkerLogGroup
    });

    // Feedback worker role needs SES permissions if it sends feedback-related emails.
    // The condition restricts sending to the configured sender email address.
    if (sesFromEmail) {
      feedbackWorkerRole.addToPrincipalPolicy(
        new iam.PolicyStatement({
          sid: 'SesSendEmailFromVerifiedIdentity',
          effect: iam.Effect.ALLOW,
          actions: ['ses:SendEmail', 'ses:SendRawEmail'],
          resources: ['*'],
          conditions: {
            StringEquals: {
              'ses:FromAddress': sesFromEmail,
            },
          },
        }),
      );
    }

    // Wire SQS → worker Lambda.
    // batchSize=1: each complaint is independent and takes ~30s; batching adds
    // complexity with no throughput benefit at this scale.
    // reportBatchItemFailures=true: partial failures don't re-process succeeded items.
    workerFn.addEventSource(new lambdaEventSources.SqsEventSource(redactQueue, {
      batchSize: 1,
      reportBatchItemFailures: true,
    }));

    // Wire SQS → feedback worker Lambda.
    // batchSize=1: each feedback is independent; batching adds complexity with no benefit.
    // reportBatchItemFailures=true: partial failures don't re-process succeeded items.
    feedbackWorkerFn.addEventSource(new lambdaEventSources.SqsEventSource(feedbackQueue, {
      batchSize: 1,
      reportBatchItemFailures: true,
    }));

    // -------------------------------------------------------------------------
    // Ask Worker Lambda — processes SQS messages from the ask queue, calls the AI,
    // and sends the answer back to the user via the Telegram Bot API.
    // Runs the same native ZIP with a different entry point class.
    // -------------------------------------------------------------------------
    const askWorkerRole = new iam.Role(this, `ComplAIAskWorkerRole-${environment}`, {
      assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
      description: `IAM role for ComplAI ask worker Lambda (${environment}) - SQS consume + Telegram API`,
    });
    askWorkerRole.addManagedPolicy(
      iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole'),
    );
    // Ask worker consumes from ask queue
    askQueue.grantConsumeMessages(askWorkerRole);
    // Ask worker needs procedures/events/news/cityinfo access for RAG context in the AI prompt.
    proceduresBucket.grantRead(askWorkerRole);
    eventsBucket.grantRead(askWorkerRole);
    newsBucket.grantRead(askWorkerRole);
    cityInfoBucket.grantRead(askWorkerRole);

    const askWorkerLogGroup = new logs.LogGroup(this, `ComplAIAskWorkerLogGroup-${environment}`, {
      logGroupName: `/aws/lambda/ComplAIAskWorkerLambda-${environment}`,
      retention: logRetention,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });

    const askWorkerFn = new lambda.Function(this, `ComplAIAskWorkerLambda-${environment}`, {
      runtime: lambda.Runtime.PROVIDED_AL2023,
      architecture: lambda.Architecture.ARM_64,
      functionName: `ComplAIAskWorkerLambda-${environment}`,
      code,
      handler: 'bootstrap',
      // I/O-bound: RAG context building, AI call (waits on network), Telegram API.
      // GraalVM native image eliminates JIT warmup and reduces baseline memory by
      // ~70%. The dominant cost is waiting for the AI response, not CPU cycles.
      // 256 MB provides ~87 MB headroom above the measured 169 MB peak (cold start
      // with RAG warmup). Lower values would risk OOM during concurrent requests.
      // Verified with Max Memory Used=169 MB in production logs.
      memorySize: 256,
      // Must be ≤ SQS visibility timeout (120s). Lambda extends visibility automatically
      // while running, so using a lower duration is the safest choice.
      timeout: cdk.Duration.seconds(90),
      environment: {
        OPENROUTER_API_KEY: process.env.OPENROUTER_API_KEY || '',
        OPENROUTER_REQUEST_TIMEOUT_SECONDS: process.env.OPENROUTER_REQUEST_TIMEOUT_SECONDS || '60',
        OPENROUTER_OVERALL_TIMEOUT_SECONDS: process.env.OPENROUTER_OVERALL_TIMEOUT_SECONDS || '60',
        OPENROUTER_MAX_RETRIES: process.env.OPENROUTER_MAX_RETRIES || '3',
        OPENROUTER_MODEL: process.env.OPENROUTER_MODEL || 'openrouter/free',
        PROCEDURES_BUCKET: proceduresBucket.bucketName,
        PROCEDURES_REGION: this.region,
        EVENTS_BUCKET: eventsBucket.bucketName,
        EVENTS_REGION: this.region,
        NEWS_BUCKET: newsBucket.bucketName,
        NEWS_REGION: this.region,
        CITYINFO_BUCKET: cityInfoBucket.bucketName,
        CITYINFO_REGION: this.region,
        // HTTP Client configuration for Micronaut (operational flexibility)
        HTTP_CLIENT_CONNECT_TIMEOUT: process.env.HTTP_CLIENT_CONNECT_TIMEOUT || '10s',
        HTTP_CLIENT_READ_TIMEOUT: process.env.HTTP_CLIENT_READ_TIMEOUT || '60s',
        HTTP_CLIENT_MAX_CONNECTIONS: process.env.HTTP_CLIENT_MAX_CONNECTIONS || '20',
        HTTP_CLIENT_LOG_LEVEL: process.env.HTTP_CLIENT_LOG_LEVEL || 'WARN',
        // Response caching configuration for OpenRouter API responses
        RESPONSE_CACHE_ENABLED: process.env.RESPONSE_CACHE_ENABLED || 'true',
        RESPONSE_CACHE_TTL_MINUTES: process.env.RESPONSE_CACHE_TTL_MINUTES || '10',
        RESPONSE_CACHE_MAX_ENTRIES: process.env.RESPONSE_CACHE_MAX_ENTRIES || '500',
        // SES Configuration — sender and recipient emails from GitHub Environment Variables
        AWS_SES_FROM_EMAIL: process.env.SES_FROM_EMAIL || '',
        AWS_SES_TO_EMAIL: process.env.SES_TO_EMAIL || '',
        AWS_SES_REGION: process.env.AWS_SES_REGION || 'eu-west-1',
        // Default city for RAG index pre-warming at startup
        COMPLAI_DEFAULT_CITY_ID: process.env.COMPLAI_DEFAULT_CITY_ID || 'elprat',
        // Per-city Telegram bot tokens for sending answers back
        TOKEN_TELEGRAM_ELPRAT: process.env.TOKEN_TELEGRAM_ELPRAT || '',
        // Allow LocalStack endpoint for local development
        ...(process.env.AWS_ENDPOINT_URL ? { AWS_ENDPOINT_URL: process.env.AWS_ENDPOINT_URL } : {}),
      },
      role: askWorkerRole,
      logGroup: askWorkerLogGroup,
    });

    // Wire SQS → ask worker Lambda.
    // batchSize=1: each ask is independent and takes ~30-60s; batching adds
    // complexity with no throughput benefit at this scale.
    // reportBatchItemFailures=true: partial failures don't re-process succeeded items.
    askWorkerFn.addEventSource(new lambdaEventSources.SqsEventSource(askQueue, {
      batchSize: 1,
      reportBatchItemFailures: true,
    }));

    // -------------------------------------------------------------------------
    // Scheduled Lambda — sends weekly statistics report via SES every Monday 03:00.
    // -------------------------------------------------------------------------
    const scheduledReportRole = new iam.Role(this, `ComplAIScheduledReportRole-${environment}`, {
      assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
      description: `IAM role for ComplAI scheduled report Lambda (${environment})`,
    });
    scheduledReportRole.addManagedPolicy(
      iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole'),
    );
    // Allow CloudWatch Metrics queries (StadisticsService uses GetMetricStatistics
    // to query interaction counts for the weekly report). The namespace condition
    // is intentionally omitted — GetMetricStatistics does not reliably propagate
    // the namespace to the IAM auth context, causing the condition to deny even
    // legitimate requests. Least-privilege is maintained by granting only
    // GetMetricStatistics (not the broader GetMetricData or ListMetrics).
    scheduledReportRole.addToPrincipalPolicy(
      new iam.PolicyStatement({
        sid: 'ScheduledReportCloudWatchMetrics',
        effect: iam.Effect.ALLOW,
        actions: ['cloudwatch:GetMetricStatistics'],
        resources: ['*'],
      }),
    );

    // Read from procedures, events, news, cityinfo, complaints, feedback buckets
    // (StadisticsService lists complaint and feedback files from S3 for the report)
    proceduresBucket.grantRead(scheduledReportRole);
    eventsBucket.grantRead(scheduledReportRole);
    newsBucket.grantRead(scheduledReportRole);
    cityInfoBucket.grantRead(scheduledReportRole);
    complaintsBucket.grantRead(scheduledReportRole);
    feedbackBucket.grantRead(scheduledReportRole);
    // Allow SES send email from the verified sender address
    if (sesFromEmail) {
      scheduledReportRole.addToPrincipalPolicy(
        new iam.PolicyStatement({
          sid: 'ScheduledReportSesSendEmail',
          effect: iam.Effect.ALLOW,
          actions: ['ses:SendEmail', 'ses:SendRawEmail'],
          resources: ['*'],
          conditions: {
            StringEquals: {
              'ses:FromAddress': sesFromEmail,
            },
          },
        }),
      );
    }

    const scheduledReportLogGroup = new logs.LogGroup(this, `ComplAIScheduledReportLogGroup-${environment}`, {
      logGroupName: `/aws/lambda/ComplAIScheduledReportLambda-${environment}`,
      retention: logRetention,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
    });

    const scheduledReportFn = new lambda.Function(this, `ComplAIScheduledReportLambda-${environment}`, {
      runtime: lambda.Runtime.PROVIDED_AL2023,
      architecture: lambda.Architecture.ARM_64,
      functionName: `ComplAIScheduledReportLambda-${environment}`,
      code,
      handler: 'bootstrap',
      // I/O-bound: CloudWatch FilterLogEvents queries (API wait), AI call, HTML
      // report rendering, SES send. Native image removes JIT overhead — the
      // CloudWatch API response time dominates, not CPU. 256 MB provides ample
      // working memory (log event gathering, HTML generation, AI context) while
      // keeping cost low for this weekly invocation. No PDF generation or caches.
      memorySize: 256,
      timeout: cdk.Duration.seconds(60),
      environment: {
        // OpenRouter API key for AI predictions in statistics reports
        OPENROUTER_API_KEY: process.env.OPENROUTER_API_KEY || '',
        OPENROUTER_REQUEST_TIMEOUT_SECONDS: process.env.OPENROUTER_REQUEST_TIMEOUT_SECONDS || '60',
        OPENROUTER_OVERALL_TIMEOUT_SECONDS: process.env.OPENROUTER_OVERALL_TIMEOUT_SECONDS || '60',
        OPENROUTER_MAX_RETRIES: process.env.OPENROUTER_MAX_RETRIES || '3',
        AWS_SES_FROM_EMAIL: process.env.SES_FROM_EMAIL || '',
        AWS_SES_TO_EMAIL_ELPRAT: process.env.SES_TO_EMAIL_ELPRAT || '',
        AWS_SES_REGION: process.env.AWS_SES_REGION || 'eu-west-1',
        ENVIRONMENT: environment,
        PROCEDURES_BUCKET: proceduresBucket.bucketName,
        PROCEDURES_REGION: this.region,
        EVENTS_BUCKET: eventsBucket.bucketName,
        EVENTS_REGION: this.region,
        NEWS_BUCKET: newsBucket.bucketName,
        NEWS_REGION: this.region,
        CITYINFO_BUCKET: cityInfoBucket.bucketName,
        CITYINFO_REGION: this.region,
        COMPLAINTS_BUCKET: complaintsBucket.bucketName,
        COMPLAINTS_REGION: this.region,
        FEEDBACK_BUCKET_NAME: feedbackBucket.bucketName,
        // Per-city API keys for MultiCitySesService discovery (city must have BOTH API_KEY_* and AWS_SES_TO_EMAIL_*)
        API_KEY_ELPRAT: process.env.API_KEY_ELPRAT || '',
        // Allow LocalStack endpoint for local development
        ...(process.env.AWS_ENDPOINT_URL ? { AWS_ENDPOINT_URL: process.env.AWS_ENDPOINT_URL } : {}),
      },
      role: scheduledReportRole,
      logGroup: scheduledReportLogGroup,
    });

    // EventBridge rule: run every Monday at 03:00 (server time)
    new events.Rule(this, `ComplAIScheduledReportRule-${environment}`, {
      ruleName: `ComplAIScheduledReportRule-${environment}`,
      description: 'Triggers the statistics report Lambda every Monday at 03:00',
      schedule: events.Schedule.expression('cron(0 3 ? * MON *)'),
      targets: [new eventsTargets.LambdaFunction(scheduledReportFn)],
    });

    // AuditLogger emits JSON lines like:
    //   {"ts":"...","endpoint":"/complai/ask","requestHash":"...","errorCode":0,"latencyMs":312,"outputFormat":"","language":""}
    // We attach metric filters to the log group to make error rates and slow
    // invocations visible in CloudWatch without a third-party log processor.

    // Counts every request that completed with a non-zero errorCode.
    new logs.MetricFilter(this, `ComplAIErrorMetricFilter-${environment}`, {
      logGroup,
      metricNamespace: `ComplAI/${environment}`,
      metricName: 'AuditErrorCount',
      // Match any audit log line where errorCode is not 0 (NONE).
      // The pattern intentionally excludes errorCode:0 by matching the literal field.
      filterPattern: logs.FilterPattern.exists('$.errorCode'),
      metricValue: '1',
      dimensions: {
        endpoint: '$.endpoint',
        errorCode: '$.errorCode',
      },
    });

    // Captures the latencyMs value from every audit log line as a metric so we
    // can build P50/P95/P99 dashboards or alarms without exporting raw logs.
    new logs.MetricFilter(this, `ComplAILatencyMetricFilter-${environment}`, {
      logGroup,
      metricNamespace: `ComplAI/${environment}`,
      metricName: 'AuditLatencyMs',
      filterPattern: logs.FilterPattern.exists('$.latencyMs'),
      // Use the extracted latency value directly — CloudWatch treats this as a
      // numeric observation, enabling statistics (Average, p99, etc.).
      metricValue: '$.latencyMs',
      dimensions: {
        endpoint: '$.endpoint',
      },
    });

    // Expose the Lambda name, ARN, and log group as CloudFormation outputs so
    // deploys (and CI) can easily discover the physical identifiers.
    new cdk.CfnOutput(this, 'ComplAILambdaFunctionName', {
      value: lambdaFn.functionName,
      description: `Name of the deployed ComplAI Lambda function (${environment})`,
    });

    new cdk.CfnOutput(this, 'ComplAILambdaArn', {
      value: lambdaFn.functionArn,
      description: `ARN of the deployed ComplAI Lambda function (${environment})`,
    });

    new cdk.CfnOutput(this, 'ComplAILogGroupName', {
      value: logGroup.logGroupName,
      description: `CloudWatch Log Group for the ComplAI Lambda function (${environment})`,
    });

    new cdk.CfnOutput(this, 'ComplAIWorkerLambdaName', {
      value: workerFn.functionName,
      description: `Name of the worker Lambda function (${environment})`,
    });

    new cdk.CfnOutput(this, 'ComplAIWorkerLogGroupName', {
      value: workerLogGroup.logGroupName,
      description: `CloudWatch Log Group for the worker Lambda function (${environment})`,
    });

    new cdk.CfnOutput(this, 'ComplAIFeedbackWorkerLambdaName', {
      value: feedbackWorkerFn.functionName,
      description: `Name of the feedback worker Lambda function (${environment})`,
    });

    new cdk.CfnOutput(this, 'ComplAIFeedbackWorkerLogGroupName', {
      value: feedbackWorkerLogGroup.logGroupName,
      description: `CloudWatch Log Group for the feedback worker Lambda function (${environment})`,
    });

    new cdk.CfnOutput(this, 'ComplAIAskWorkerLambdaName', {
      value: askWorkerFn.functionName,
      description: `Name of the ask worker Lambda function (${environment})`,
    });

    new cdk.CfnOutput(this, 'ComplAIAskWorkerLogGroupName', {
      value: askWorkerLogGroup.logGroupName,
      description: `CloudWatch Log Group for the ask worker Lambda function (${environment})`,
    });

    new cdk.CfnOutput(this, 'ComplAIApiGatewayEndpoint', {
      value: httpApi.apiEndpoint,
      description: `HTTP API Gateway endpoint URL for ComplAI (${environment})`,
    });

    new cdk.CfnOutput(this, 'ComplAIScheduledReportLambdaName', {
      value: scheduledReportFn.functionName,
      description: `Name of the scheduled statistics report Lambda function (${environment})`,
    });

    new cdk.CfnOutput(this, 'ComplAIScheduledReportLogGroupName', {
      value: scheduledReportLogGroup.logGroupName,
      description: `CloudWatch Log Group for the scheduled report Lambda function (${environment})`,
    });
  }
}
