import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as apigateway from 'aws-cdk-lib/aws-apigateway';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as path from 'path';

export class LambdaStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

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

    // Create a custom IAM role for Lambda with least privilege
    const lambdaRole = new iam.Role(this, 'ComplAILambdaRole', {
      assumedBy: new iam.ServicePrincipal('lambda.amazonaws.com'),
      description: 'IAM role for ComplAI Lambda with least privilege',
    });

    // Attach AWS managed policy for basic Lambda logging
    lambdaRole.addManagedPolicy(iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AWSLambdaBasicExecutionRole'));

    // Add custom permissions here if needed, e.g.:
    // lambdaRole.addToPolicy(new iam.PolicyStatement({
    //   actions: ['s3:GetObject'],
    //   resources: ['arn:aws:s3:::your-bucket/*'],
    // }));

    // Resolve the path to the built fat JAR produced by Gradle's shadowJar. Using an
    // explicit path to the jar keeps the asset upload deterministic and small. Update
    // the jar filename if your build produces a different name.
    const jarPath = path.resolve(__dirname, '..', '..', 'build', 'libs', 'complai-0.1-all.jar');

    const lambdaFn = new lambda.Function(this, 'ComplAILambda', {
      runtime: lambda.Runtime.JAVA_17,
      handler: 'io.micronaut.function.aws.proxy.MicronautLambdaHandler::handleRequest',
      code: lambda.Code.fromAsset(jarPath),
      memorySize: 1024,
      timeout: cdk.Duration.seconds(30),
      // Wire the OpenRouter API key (from CFN parameter) into the Lambda environment.
      // Be aware that environment variables are visible in the Lambda console; using
      // Secrets Manager or SSM Parameter Store with encryption is more secure if available.
      environment: {
        OPENROUTER_API_KEY: openRouterApiKey.valueAsString,
      },
      role: lambdaRole,
    });

    // Create API Gateway pointing to the Lambda. Not assigned because it's not used later.
    new apigateway.LambdaRestApi(this, 'ComplAIEndpoint', {
      handler: lambdaFn,
      proxy: true,
      restApiName: 'ComplAI Home API',
      description: 'API Gateway for ComplAI HomeController',
    });
  }
}
