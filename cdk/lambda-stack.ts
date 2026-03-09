import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as path from 'path';
import * as fs from 'fs';
import * as s3 from 'aws-cdk-lib/aws-s3';

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
      removalPolicy: cdk.RemovalPolicy.RETAIN, // Retain data by default
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      encryption: s3.BucketEncryption.S3_MANAGED,
      versioned: false,
      lifecycleRules: [{
        expiration: cdk.Duration.days(365), // Clean up old files after 1 year
      }],
    });

    // Grant Lambda read-only access to the procedures bucket
    proceduresBucket.grantRead(lambdaRole);

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
        OPENROUTER_REQUEST_TIMEOUT_SECONDS: process.env.OPENROUTER_REQUEST_TIMEOUT_SECONDS || '20',
        OPENROUTER_OVERALL_TIMEOUT_SECONDS: process.env.OPENROUTER_OVERALL_TIMEOUT_SECONDS || '30',
        PROCEDURES_BUCKET: proceduresBucket.bucketName,
        PROCEDURES_KEY: 'procedures.json',
        PROCEDURES_REGION: this.region,
        OPENROUTER_MODEL: process.env.OPENROUTER_MODEL || 'arcee-ai/trinity-large-preview:free',
        // JWT_SECRET is a Base64-encoded HS256 key (min 32 bytes).
        // Injected from the GitHub Environment Secret of the same name during CDK deploy.
        // Like OPENROUTER_API_KEY, this is visible in the Lambda console — see the security
        // note in this file. Rotate via: update the GitHub secret → redeploy → mint new tokens.
        JWT_SECRET: process.env.JWT_SECRET || '',
      },
      role: lambdaRole,
    });

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
  }
}
