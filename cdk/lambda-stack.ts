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
import { DeploymentEnvironment } from './deployment-environment';

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
}

export class LambdaStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: LambdaStackProps) {
    super(scope, id, props);

    const { environment, redactQueue } = props;

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
    const complaintsBucket = s3.Bucket.fromBucketName(
      this, `ComplaintsBucketRef-${environment}`, `complai-complaints-${environment}`
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

    // --- Lambda code source ------------------------------------------------
    // CI path: the GitHub Actions workflow uploads the fat JAR to the
    // deployments bucket before calling `cdk deploy`, then sets
    // DEPLOYMENT_JAR_KEY to the S3 key (e.g. complai-all-<git-sha>.jar).
    // Code.fromBucket does NOT stage anything to the CDK bootstrap bucket,
    // so the cdk-hnb659fds-assets-* bucket never receives the JAR.
    //
    // Local dev path: no DEPLOYMENT_JAR_KEY → fall back to Code.fromAsset,
    // which stages via the bootstrap bucket.  This keeps the local workflow
    // unchanged and avoids requiring developers to pre-upload the JAR.
    // -----------------------------------------------------------------------
    let code: lambda.Code;
    const deploymentJarKey = process.env.DEPLOYMENT_JAR_KEY ?? this.node.tryGetContext('jarS3Key');
    if (deploymentJarKey) {
      code = lambda.Code.fromBucket(deploymentsBucket, deploymentJarKey);
    } else {
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
      code = lambda.Code.fromAsset(jarPath);
    }

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
      code,
      memorySize: 768,
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
        OPENROUTER_MODEL: process.env.OPENROUTER_MODEL || 'openrouter/free',
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
    proceduresBucket.grantRead(lambdaRole);

    // -------------------------------------------------------------------------
    // Worker Lambda — processes SQS messages and uploads generated PDFs to S3.
    // Runs the same shadow JAR with a different handler class.
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
      code,
      memorySize: 768,
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
    lambdaFn.addFunctionUrl({
      authType: lambda.FunctionUrlAuthType.NONE,
      cors: {
        allowedOrigins: ['*'],
        allowedMethods: [lambda.HttpMethod.ALL],
        allowedHeaders: ['*'],
      },
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
  }
}
