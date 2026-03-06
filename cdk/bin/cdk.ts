#!/usr/bin/env node
import 'source-map-support/register';
import * as cdk from 'aws-cdk-lib';
import { LambdaStack } from '../lambda-stack';

const app = new cdk.App();

const awsEnv = {
  account: '134267836527',
  region: 'eu-west-1',
};

// One stack per environment. Stack names match the pattern the CI uses when
// targeting a deploy: `cdk deploy ComplAILambdaStack-<environment>`.
// CDK synthesises both stacks, but the workflow always deploys only the one
// matching the chosen environment, so the other stack is never touched.
new LambdaStack(app, 'ComplAILambdaStack-development', {
  environment: 'development',
  env: awsEnv,
});

new LambdaStack(app, 'ComplAILambdaStack-production', {
  environment: 'production',
  env: awsEnv,
});
