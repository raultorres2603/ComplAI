import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as apigateway from 'aws-cdk-lib/aws-apigateway';
import * as iam from 'aws-cdk-lib/aws-iam';

export class LambdaStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

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

    const lambdaFn = new lambda.Function(this, 'ComplAILambda', {
      runtime: lambda.Runtime.JAVA_17,
      handler: 'io.micronaut.function.aws.proxy.MicronautLambdaHandler::handleRequest',
      code: lambda.Code.fromAsset('../../build/libs'),
      memorySize: 1024,
      timeout: cdk.Duration.seconds(30),
      environment: {
        // Add other environment variables as needed
      },
      role: lambdaRole,
    });

    const api = new apigateway.LambdaRestApi(this, 'ComplAIEndpoint', {
      handler: lambdaFn,
      proxy: true,
      restApiName: 'ComplAI Home API',
      description: 'API Gateway for ComplAI HomeController',
    });
  }
}
