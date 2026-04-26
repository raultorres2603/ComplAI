#!/usr/bin/env node
import 'source-map-support/register';
import * as cdk from 'aws-cdk-lib';
import { DeploymentEnvironment } from '../deployment-environment';
import { StorageStack } from '../storage-stack';
import { QueueStack } from '../queue-stack';
import { LambdaStack } from '../lambda-stack';
import { EdgeStack } from '../edge-stack';

const app = new cdk.App();

function toStackPattern(selector: string): RegExp {
  const escaped = selector.replace(/[.+?^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*');
  return new RegExp(`^${escaped}$`);
}

function parseCliSelection() {
  const argv = process.argv.slice(2);
  const command = argv.find((arg) => !arg.startsWith('-'))?.toLowerCase();
  const commandIndex = command ? argv.findIndex((arg) => arg.toLowerCase() === command) : -1;
  const stackSelectors = commandIndex >= 0
    ? argv
        .slice(commandIndex + 1)
        .filter((arg) => !arg.startsWith('-'))
        .filter((arg) => arg.includes('Stack') || arg.includes('*'))
    : [];

  return {
    command,
    exclusively: argv.includes('--exclusively'),
    selectors: stackSelectors,
  };
}

function isStackRequested(stackName: string, selectors: string[]): boolean {
  if (selectors.length === 0) {
    return true;
  }
  return selectors.some((selector) => toStackPattern(selector).test(stackName));
}

const cliSelection = parseCliSelection();
const destroyWithSelectors = cliSelection.command === 'destroy' && cliSelection.selectors.length > 0;
const instantiateOnlyRequestedStacks =
  (cliSelection.exclusively && cliSelection.selectors.length > 0) || destroyWithSelectors;

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
  const storageStackName = `ComplAIStorageStack-${environment}`;
  const queueStackName = `ComplAIQueueStack-${environment}`;
  const lambdaStackName = `ComplAILambdaStack-${environment}`;
  const edgeStackName = `ComplAIEdgeStack-${environment}`;

  const requestedStorage = isStackRequested(storageStackName, cliSelection.selectors);
  const requestedQueue = isStackRequested(queueStackName, cliSelection.selectors);
  const requestedLambda = isStackRequested(lambdaStackName, cliSelection.selectors);
  const requestedEdge = isStackRequested(edgeStackName, cliSelection.selectors);
  const detachedEdgeDestroy =
    cliSelection.command === 'destroy' && instantiateOnlyRequestedStacks && requestedEdge && !requestedLambda;

  const wantsEdge = !instantiateOnlyRequestedStacks || requestedEdge;
  const wantsLambda = !instantiateOnlyRequestedStacks || requestedLambda || (requestedEdge && !detachedEdgeDestroy);
  const wantsStorage = !instantiateOnlyRequestedStacks || requestedStorage || wantsLambda;
  const wantsQueue = !instantiateOnlyRequestedStacks || requestedQueue || wantsLambda;

  const storageStack = wantsStorage
    ? new StorageStack(app, storageStackName, {
        environment,
        env: awsEnv,
      })
    : undefined;

  const queueStack = wantsQueue
    ? new QueueStack(app, queueStackName, {
        environment,
        env: awsEnv,
      })
    : undefined;

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
  let lambdaStack: LambdaStack | undefined;
  if (wantsLambda) {
    lambdaStack = new LambdaStack(app, lambdaStackName, {
      environment,
      redactQueue: queueStack!.redactQueue,
      feedbackQueue: queueStack!.feedbackQueue,
      allowMissingLocalArtifactsForDestroy: cliSelection.command === 'destroy',
      env: awsEnv,
      crossRegionReferences: true,
    });
    lambdaStack.addDependency(storageStack!);
    lambdaStack.addDependency(queueStack!);
  }

  if (environment === 'production' && wantsEdge) {
    const detachedHttpApiId = process.env.EDGE_HTTP_API_ID
      || app.node.tryGetContext('edgeHttpApiId')
      || `edge-detached-${environment}`;

    if (!lambdaStack && !detachedEdgeDestroy) {
      throw new Error(
        `Cannot instantiate ${edgeStackName} without ${lambdaStackName}. ` +
        `For edge-only destroy with --exclusively, this is handled automatically.`,
      );
    }

    const edgeStack = new EdgeStack(app, edgeStackName, {
      environment,
      httpApiId: lambdaStack ? lambdaStack.httpApi.apiId : detachedHttpApiId,
      httpApiRegion: awsEnv.region,
      env: { account: awsEnv.account, region: 'us-east-1' },
      crossRegionReferences: true,
    });
    if (lambdaStack) {
      edgeStack.addDependency(lambdaStack);
    }
  }
}

app.synth();
