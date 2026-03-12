#!/usr/bin/env node
import 'source-map-support/register';
import * as cdk from 'aws-cdk-lib';
import { DeploymentEnvironment } from '../deployment-environment';
import { StorageStack } from '../storage-stack';
import { QueueStack } from '../queue-stack';
import { LambdaStack } from '../lambda-stack';

const app = new cdk.App();

const awsEnv = {
  account: '134267836527',
  region: 'eu-west-1',
};

// One set of stacks per environment. Stack names include the environment suffix
// so CI can target a specific environment with:
//   cdk deploy 'ComplAI*Stack-<environment>'
// CDK synthesises all stacks but the workflow deploys only the chosen environment.
const environments: DeploymentEnvironment[] = ['development', 'production'];

for (const environment of environments) {
  const storageStack = new StorageStack(app, `ComplAIStorageStack-${environment}`, {
    environment,
    env: awsEnv,
  });

  const queueStack = new QueueStack(app, `ComplAIQueueStack-${environment}`, {
    environment,
    env: awsEnv,
  });

  // LambdaStack depends on both storage and queue stacks. CDK tracks cross-stack
  // references automatically and generates CloudFormation Exports/Imports, but
  // the explicit addDependency calls make the deployment order unambiguous.
  const lambdaStack = new LambdaStack(app, `ComplAILambdaStack-${environment}`, {
    environment,
    proceduresBucket: storageStack.proceduresBucket,
    complaintsBucket: storageStack.complaintsBucket,
    redactQueue: queueStack.redactQueue,
    env: awsEnv,
  });
  lambdaStack.addDependency(storageStack);
  lambdaStack.addDependency(queueStack);
}

app.synth();
