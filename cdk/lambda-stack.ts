import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as logs from 'aws-cdk-lib/aws-logs';
import * as path from 'path';
import * as fs from 'fs';
import * as apigwv2 from 'aws-cdk-lib/aws-apigatewayv2';
import * as integrations from 'aws-cdk-lib/aws-apigatewayv2-integrations';

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

    // Create a CloudFormation parameter for the OpenRouter API key.
    // We set noEcho: true so CloudFormation does not display the value in the console output.
    // Note: passing secrets as CFN parameters means the secret will be present in the
    // CloudFormation deployment context. This is a tradeoff when you can't use Secrets Manager.
    const openRouterApiKey = new cdk.CfnParameter(this, 'OpenRouterApiKey', {
      type: 'String',
      noEcho: true,
      description: 'OpenRouter API key (passed from CI during cdk deploy)',
      // Ensure the parameter is not empty: CloudFormation will validate and fail the deploy
      // if the provided value is an empty string. This prevents deploying a function with
      // a missing API key. Using minLength is safer and fails early.
      minLength: 1,
      constraintDescription: 'OpenRouterApiKey must be provided and not be empty.',
    });

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
      timeout: cdk.Duration.seconds(30),
      // Wire the OpenRouter API key (from CFN parameter) into the Lambda environment.
      // Be aware that environment variables are visible in the Lambda console; using
      // Secrets Manager or SSM Parameter Store with encryption is more secure if available.
      environment: {
        OPENROUTER_API_KEY: openRouterApiKey.valueAsString,
      },
      role: lambdaRole,
    });

    // Create an HTTP API (API Gateway v2) and connect it to the Lambda via a proxy integration.
    // HTTP APIs are cheaper and recommended for most Lambda-backed HTTP workloads.
    // HttpLambdaIntegration requires an id as the first argument in aws-cdk-lib v2.
    const lambdaIntegration = new integrations.HttpLambdaIntegration(`ComplAILambdaIntegration-${environment}`, lambdaFn);
    const httpApi = new apigwv2.HttpApi(this, `ComplAIHttpApi-${environment}`, {
      defaultIntegration: lambdaIntegration,
    });

    // CloudWatch log group for HTTP API access logs.
    // 1 week retention keeps costs minimal while giving enough history to debug issues.
    // The log group path is namespaced by environment so the two stacks write to
    // separate log groups and do not share retention/deletion lifecycle.
    const accessLogGroup = new logs.LogGroup(this, `ComplAIHttpApiAccessLogs-${environment}`, {
      logGroupName: `/aws/apigateway/complai/${environment}/${this.stackName}`,
      retention: logs.RetentionDays.ONE_WEEK,
      // Delete the log group when the stack is destroyed to avoid orphaned resources.
      removalPolicy: cdk.RemovalPolicy.DESTROY,
    });

    // API Gateway (delivery.logs.amazonaws.com) must be allowed to create log streams
    // and write log events to the log group. Without this the stage will fail to deliver
    // access logs and the setting silently has no effect.
    accessLogGroup.addToResourcePolicy(new iam.PolicyStatement({
      principals: [new iam.ServicePrincipal('delivery.logs.amazonaws.com')],
      actions: ['logs:CreateLogStream', 'logs:PutLogEvents'],
      resources: [`${accessLogGroup.logGroupArn}:*`],
    }));

    // The L2 HttpApi does not expose accessLogSettings on the default stage directly.
    // Use the CfnStage escape hatch to enable access logging on the $default stage.
    // Format: structured JSON per request — easy to query in CloudWatch Logs Insights.
    if (!httpApi.defaultStage) {
      throw new Error('HttpApi.defaultStage is undefined — cannot configure access logging.');
    }
    const cfnDefaultStage = httpApi.defaultStage.node.defaultChild as apigwv2.CfnStage;
    cfnDefaultStage.accessLogSettings = {
      destinationArn: accessLogGroup.logGroupArn,
      format: JSON.stringify({
        requestId:          '$context.requestId',
        ip:                 '$context.identity.sourceIp',
        httpMethod:         '$context.httpMethod',
        routeKey:           '$context.routeKey',
        status:             '$context.status',
        integrationError:   '$context.integrationErrorMessage',
        errorMessage:       '$context.error.message',
        responseLatency:    '$context.responseLatency',
        integrationLatency: '$context.integrationLatency',
      }),
    };

    // Expose the HTTP API endpoint as a CloudFormation output so deploys and CI can discover it easily.
    new cdk.CfnOutput(this, 'ComplAIHttpApiEndpoint', {
      value: httpApi.apiEndpoint,
      description: `URL of the deployed ComplAI HTTP API (${environment})`,
    });

    // Expose the Lambda name and ARN as CloudFormation outputs so deploys (and CI)
    // can easily discover the physical identifiers. These are safe to emit and
    // helpful for debugging and automation. The function name includes the CDK
    // generated suffix, so using the output avoids guessing the hash.
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

