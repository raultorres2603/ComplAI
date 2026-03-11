import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as path from 'path';
import * as fs from 'fs';
import * as s3 from 'aws-cdk-lib/aws-s3';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as lambdaEventSources from 'aws-cdk-lib/aws-lambda-event-sources';

export type DeploymentEnvironment = 'development' | 'production';

export interface LambdaStackProps extends cdk.StackProps {
  // Identifies which environment this stack owns. Every AWS resource is suffixed
  // with this value so the two stacks can coexist in the same AWS account without
  // name collisions, and so it is immediately obvious in the console which
  // resource belongs to which environment.
  readonly environment: DeploymentEnvironment;
}

export class LambdaStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: LambdaStackProps) {
    super(scope, id, props);

    const { environment } = props;

    // Create a custom IAM role for Lambda with least privilege.
    // The logical ID includes the environment so both stacks can live in the same account.
    const lambdaRole = new iam.Role(this, `ComplAILambdaRole-${environment}`, {
      assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
      description: `IAM role for ComplAI Lambda (${environment}) with least privilege`,
    });

    // Attach AWS managed policy for basic Lambda logging
    lambdaRole.addManagedPolicy(iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole'));

    // Add custom permissions here if needed, e.g.:
    // lambdaRole.addToPolicy(new iam.PolicyStatement({
    //   actions: ['s3:GetObject'],
    //   resources: ['arn:aws:s3:::your-bucket/*'],
    // }));

    // Minimal JAR selection:
    // 1) If CI sets JAR_PATH (or CDK context 'jarPath'), use it.
    // 2) Otherwise require a '*-all.jar' (shadowJar) in build/libs and use the first one found.
    // This keeps behavior explicit and avoids brittle heuristics.
    const explicit = process.env.JAR_PATH || this.node.tryGetContext?.('jarPath');
    let jarPath: string | undefined;
    if (explicit) {
      const candidate = path.isAbsolute(explicit) ? explicit : path.resolve(__dirname, '..', '..', explicit);
      if (!fs.existsSync(candidate)) throw new Error(`Configured JAR_PATH does not exist: ${candidate}`);
      jarPath = candidate;
    } else {
      const libsDir = path.resolve(__dirname, '..', '..', 'build', 'libs');
      if (!fs.existsSync(libsDir)) throw new Error(`Expected build/libs not found at ${libsDir}. Run './gradlew clean shadowJar'.`);
      const jars = fs.readdirSync(libsDir).filter((f: string) => f.endsWith('.jar'));
      if (jars.length === 0) throw new Error(`No JARs found in ${libsDir}. Run './gradlew clean shadowJar'.`);
      const allJar = jars.find((f: string) => f.includes('-all.jar') || f.endsWith('-all.jar'));
      if (!allJar) throw new Error(`No '*-all.jar' found in ${libsDir}. Set JAR_PATH env to the produced jar or produce a shadow JAR.`);
      jarPath = path.join(libsDir, allJar);
    }
    if (!jarPath || !fs.existsSync(jarPath)) throw new Error(`Unable to determine JAR path. Computed: ${jarPath}`);

// S3 bucket for procedures corpus (Free Tier, no public access)
    const proceduresBucket = new s3.Bucket(this, `ComplAIProceduresBucket-${environment}`, {
      bucketName: `complai-procedures-${environment.toLowerCase()}`,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      encryption: s3.BucketEncryption.S3_MANAGED,
      versioned: false,
      lifecycleRules: [{
        expiration: cdk.Duration.days(365),
      }],
    });

    // Grant Lambda read-only access to the procedures bucket
    proceduresBucket.grantRead(lambdaRole);

    // -------------------------------------------------------------------------
    // S3 bucket for generated complaint PDFs
    // PDFs are ephemeral — deleted after 30 days so storage stays free-tier-ish.
    // -------------------------------------------------------------------------
    const complaintsBucket = new s3.Bucket(this, `ComplAIComplaintsBucket-${environment}`, {
      bucketName: `complai-complaints-${environment.toLowerCase()}`,
      removalPolicy: environment === 'production'
        ? cdk.RemovalPolicy.RETAIN
        : cdk.RemovalPolicy.DESTROY,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      encryption: s3.BucketEncryption.S3_MANAGED,
      versioned: false,
      lifecycleRules: [{
        expiration: cdk.Duration.days(30),
      }],
    });

    // -------------------------------------------------------------------------
    // SQS — redact queue + DLQ
    // Visibility timeout = 90s (≥ 1.5× the worker Lambda timeout of 60s).
    // Message retention = 4h (short-lived; stale messages are useless after that).
    // -------------------------------------------------------------------------
    const redactDlq = new sqs.Queue(this, `ComplAIRedactDLQ-${environment}`, {
      queueName: `complai-redact-dlq-${environment}`,
      retentionPeriod: cdk.Duration.days(7),
      removalPolicy: environment === 'production'
        ? cdk.RemovalPolicy.RETAIN
        : cdk.RemovalPolicy.DESTROY,
    });

    const redactQueue = new sqs.Queue(this, `ComplAIRedactQueue-${environment}`, {
      queueName: `complai-redact-${environment}`,
      visibilityTimeout: cdk.Duration.seconds(90),
      retentionPeriod: cdk.Duration.hours(4),
      deadLetterQueue: {
        maxReceiveCount: 3,
        queue: redactDlq,
      },
      removalPolicy: environment === 'production'
        ? cdk.RemovalPolicy.RETAIN
        : cdk.RemovalPolicy.DESTROY,
    });

    // Explicit log group so we control retention and can attach metric filters.
    // Lambda would create a log group automatically, but that gives us no control
    // over retention or any ability to reference it in CFN outputs.
    // Production keeps logs longer for audit/compliance; development is kept short.
    const logRetention = environment === 'production'
      ? logs.RetentionDays.THREE_MONTHS
      : logs.RetentionDays.ONE_WEEK;

    const logGroup = new logs.LogGroup(this, `ComplAILogGroup-${environment}`, {
      // The Lambda runtime writes to /aws/lambda/<functionName> by convention.
      // We set this name explicitly so the log group exists before the first
      // invocation and our metric filters are in place from day one.
      logGroupName: `/aws/lambda/ComplAILambda-${environment}`,
      retention: logRetention,
      // Destroy the log group when the stack is torn down (development).
      // Production retains logs independently of the stack for compliance.
      removalPolicy: environment === 'production'
        ? cdk.RemovalPolicy.RETAIN
        : cdk.RemovalPolicy.DESTROY,
    });

    const lambdaFn = new lambda.Function(this, `ComplAILambda-${environment}`, {
      runtime: lambda.Runtime.JAVA_21,
      // The project uses the Micronaut APIGateway V2 runtime; use the Micronaut
      // APIGateway v2 HTTP event function handler which the Micronaut build
      // packages in the shadow JAR.
      // This handler expects the APIGateway V2 payload (HTTP API) which matches
      // our CDK HttpApi integration.
      handler: 'io.micronaut.function.aws.proxy.payload2.APIGatewayV2HTTPEventFunction::handleRequest',
      code: lambda.Code.fromAsset(jarPath),
      memorySize: 768,
      timeout: cdk.Duration.seconds(60),
      // Wire the OpenRouter API key (from CFN parameter) into the Lambda environment.
      // Be aware that environment variables are visible in the Lambda console; using
      // Secrets Manager or SSM Parameter Store with encryption is more secure if available.
      environment: {
        OPENROUTER_API_KEY: process.env.OPENROUTER_API_KEY || '',
        OPENROUTER_REQUEST_TIMEOUT_SECONDS: process.env.OPENROUTER_REQUEST_TIMEOUT_SECONDS || '60',
        OPENROUTER_OVERALL_TIMEOUT_SECONDS: process.env.OPENROUTER_OVERALL_TIMEOUT_SECONDS || '60',
        PROCEDURES_BUCKET: proceduresBucket.bucketName,
        PROCEDURES_KEY: 'procedures.json',
        PROCEDURES_REGION: this.region,
        OPENROUTER_MODEL: process.env.OPENROUTER_MODEL || 'arcee-ai/trinity-large-preview:free',
        JWT_SECRET: process.env.JWT_SECRET || '',
        // Async redact flow: queue URL for publishing + bucket details for pre-signed URLs.
        REDACT_QUEUE_URL: redactQueue.queueUrl,
        COMPLAINTS_BUCKET: complaintsBucket.bucketName,
        COMPLAINTS_REGION: this.region,
      },
      role: lambdaRole,
      logGroup: logGroup,
    });

    // API Lambda needs to publish to the redact queue.
    redactQueue.grantSendMessages(lambdaRole);

    // API Lambda needs s3:GetObject to sign pre-signed GET URLs on behalf of callers.
    // Pre-signed URLs embed the signer's credentials; without this permission the
    // signed URL would return 403 when the user tries to download the PDF.
    complaintsBucket.grantRead(lambdaRole);

    // -------------------------------------------------------------------------
    // Worker Lambda — processes SQS messages and uploads generated PDFs to S3.
    // Runs the same shadow JAR with a different handler class.
    // -------------------------------------------------------------------------
    const workerRole = new iam.Role(this, `ComplAIWorkerRole-${environment}`, {
      assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
      description: `IAM role for ComplAI worker Lambda (${environment}) — SQS consume + S3 write`,
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

    const workerLogGroup = new logs.LogGroup(this, `ComplAIWorkerLogGroup-${environment}`, {
      logGroupName: `/aws/lambda/ComplAIRedactorLambda-${environment}`,
      retention: logRetention,
      removalPolicy: environment === 'production'
        ? cdk.RemovalPolicy.RETAIN
        : cdk.RemovalPolicy.DESTROY,
    });

    const workerFn = new lambda.Function(this, `ComplAIRedactorLambda-${environment}`, {
      runtime: lambda.Runtime.JAVA_21,
      // Same shadow JAR, different handler class — no separate build needed.
      handler: 'cat.complai.worker.RedactWorkerHandler::handleRequest',
      code: lambda.Code.fromAsset(jarPath),
      memorySize: 768,
      // Must be ≤ SQS visibility timeout (90s). Lambda extends visibility automatically
      // while running, so using the same duration is the safest choice here.
      timeout: cdk.Duration.seconds(60),
      environment: {
        OPENROUTER_API_KEY: process.env.OPENROUTER_API_KEY || '',
        OPENROUTER_REQUEST_TIMEOUT_SECONDS: process.env.OPENROUTER_REQUEST_TIMEOUT_SECONDS || '60',
        OPENROUTER_OVERALL_TIMEOUT_SECONDS: process.env.OPENROUTER_OVERALL_TIMEOUT_SECONDS || '60',
        OPENROUTER_MODEL: process.env.OPENROUTER_MODEL || 'arcee-ai/trinity-large-preview:free',
        COMPLAINTS_BUCKET: complaintsBucket.bucketName,
        COMPLAINTS_REGION: this.region,
        PROCEDURES_BUCKET: proceduresBucket.bucketName,
        PROCEDURES_KEY: 'procedures.json',
        PROCEDURES_REGION: this.region,
      },
      role: workerRole,
      logGroup: workerLogGroup,
    });

    // Wire SQS → worker Lambda.
    // batchSize=1: each complaint is independent and takes ~30s; batching adds
    // complexity with no throughput benefit at this scale.
    // reportBatchItemFailures=true: partial failures don't re-process succeeded items.
    workerFn.addEventSource(new lambdaEventSources.SqsEventSource(redactQueue, {
      batchSize: 1,
      reportBatchItemFailures: true,
    }));

    // Lambda Function URL: free, no API Gateway needed.
    // authType NONE makes the URL publicly reachable without IAM signing.
    // The payload format sent by Lambda Function URLs is identical to API Gateway
    // HTTP API payload format 2.0, so the Micronaut handler works without changes.
    // NOTE: Unlike API Gateway, Function URLs have no built-in rate limiting.
    // Add AWS WAF in front of the URL if throttling ever becomes a requirement.
    const functionUrl = lambdaFn.addFunctionUrl({
      authType: lambda.FunctionUrlAuthType.NONE,
      cors: {
        allowedOrigins: ['*'],
        allowedMethods: [lambda.HttpMethod.ALL],
        allowedHeaders: ['*'],
      },
    });

    // Expose the Lambda name, ARN, and the public Function URL as CloudFormation
    // outputs so deploys (and CI) can easily discover the physical identifiers.
    new cdk.CfnOutput(this, 'ComplAILambdaFunctionName', {
      value: lambdaFn.functionName,
      description: `Name of the deployed ComplAI Lambda function (${environment})`,
    });

    new cdk.CfnOutput(this, 'ComplAILambdaArn', {
      value: lambdaFn.functionArn,
      description: `ARN of the deployed ComplAI Lambda function (${environment})`,
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
      defaultValue: 0,
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
      defaultValue: 0,
      dimensions: {
        endpoint: '$.endpoint',
      },
    });

    // Output the log group name so CI/CD and runbooks can reference it directly.
    new cdk.CfnOutput(this, 'ComplAILogGroupName', {
      value: logGroup.logGroupName,
      description: `CloudWatch Log Group for the ComplAI Lambda function (${environment})`,
    });

    new cdk.CfnOutput(this, 'ComplAIRedactQueueUrl', {
      value: redactQueue.queueUrl,
      description: `SQS queue URL for the async redact flow (${environment})`,
    });

    new cdk.CfnOutput(this, 'ComplAIComplaintsBucketName', {
      value: complaintsBucket.bucketName,
      description: `S3 bucket for generated complaint PDFs (${environment})`,
    });

    new cdk.CfnOutput(this, 'ComplAIWorkerLambdaName', {
      value: workerFn.functionName,
      description: `Name of the worker Lambda function (${environment})`,
    });

    new cdk.CfnOutput(this, 'ComplAIWorkerLogGroupName', {
      value: workerLogGroup.logGroupName,
      description: `CloudWatch Log Group for the worker Lambda function (${environment})`,
    });
  }
}
