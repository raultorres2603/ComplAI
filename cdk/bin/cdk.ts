#!/usr/bin/env node
import 'source-map-support/register';
import * as cdk from 'aws-cdk-lib';
import { DeploymentEnvironment } from '../deployment-environment';
import { StorageStack } from '../storage-stack';
import { QueueStack } from '../queue-stack';
import { LambdaStack } from '../lambda-stack';
import { EdgeStack } from '../edge-stack';

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

  // LambdaStack no longer receives bucket construct objects from StorageStack.
  // Buckets are resolved inside LambdaStack via fromBucketName, which produces
  // literal ARN strings at synthesis time and generates no Fn::ImportValue
  // cross-stack references. This means StorageStack can be updated freely
  // without CloudFormation blocking on "export still in use" errors.
  //
  // The SQS queue is still passed as a construct reference from QueueStack.
  // This keeps a QueueStack → LambdaStack Fn::ImportValue reference, but that
  // is intentional: SqsEventSource derives the EventSourceMapping logical ID
  // from Names.nodeUniqueId(queue.node), which hashes the queue's full construct
  // path. Passing the queue directly preserves that path — and therefore the
  // EventSourceMapping logical ID — avoiding a create-before-delete 409 conflict.
  // If the queue's logical ID in QueueStack ever needs to change, deploy
  // LambdaStack first (with --exclusively) to drop the import before updating
  // QueueStack, the same technique used for the StorageStack migration.
  const lambdaStack = new LambdaStack(app, `ComplAILambdaStack-${environment}`, {
    environment,
    redactQueue: queueStack.redactQueue,
    feedbackQueue: queueStack.feedbackQueue,
    env: awsEnv,
    crossRegionReferences: true,
  });
  lambdaStack.addDependency(storageStack);
  lambdaStack.addDependency(queueStack);

  if (environment === 'production') {
    const edgeStack = new EdgeStack(app, `ComplAIEdgeStack-${environment}`, {
      environment,
      httpApiId: lambdaStack.httpApi.apiId,
      httpApiRegion: awsEnv.region,
      env: { account: awsEnv.account, region: 'us-east-1' },
      crossRegionReferences: true,
    });
    edgeStack.addDependency(lambdaStack);
  }
}

app.synth();
