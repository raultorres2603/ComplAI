import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as apigateway from 'aws-cdk-lib/aws-apigateway';

export class LambdaStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props?: cdk.StackProps) {
    super(scope, id, props);

    const lambdaFn = new lambda.Function(this, 'ComplAILambda', {
      runtime: lambda.Runtime.JAVA_17,
      handler: 'io.micronaut.function.aws.proxy.MicronautLambdaHandler::handleRequest',
      code: lambda.Code.fromAsset('../../build/libs'),
      memorySize: 1024,
      timeout: cdk.Duration.seconds(30),
      environment: {
        // Add environment variables if needed
      },
    });

    const api = new apigateway.LambdaRestApi(this, 'ComplAIEndpoint', {
      handler: lambdaFn,
      proxy: true,
      restApiName: 'ComplAI Home API',
      description: 'API Gateway for ComplAI HomeController',
    });
  }
}
