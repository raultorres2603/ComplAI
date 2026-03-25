import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import { DeploymentEnvironment } from './deployment-environment';

export interface QueueStackProps extends cdk.StackProps {
  readonly environment: DeploymentEnvironment;
}

export class QueueStack extends cdk.Stack {
  // Exposed so the LambdaStack can wire IAM grants, inject the URL, and
  // attach the SQS event source to the worker Lambda.
  readonly redactQueue: sqs.Queue;
  readonly redactDlq: sqs.Queue;
  readonly feedbackQueue: sqs.Queue;
  readonly feedbackDlq: sqs.Queue;

  constructor(scope: Construct, id: string, props: QueueStackProps) {
    super(scope, id, props);

    const { environment } = props;

    // Dead-letter queue — receives messages that fail after maxReceiveCount
    // attempts on the main queue.  7-day retention gives enough time to
    // investigate without accumulating stale messages indefinitely.
    this.redactDlq = new sqs.Queue(this, `ComplAIRedactDLQ-${environment}`, {
      queueName: `complai-redact-dlq-${environment}`,
      retentionPeriod: cdk.Duration.days(7),
      removalPolicy: environment === 'production'
        ? cdk.RemovalPolicy.RETAIN
        : cdk.RemovalPolicy.DESTROY,
    });

    // Main redact queue.
    // Visibility timeout (90s) must be ≥ 1.5× the worker Lambda timeout (60s)
    // to prevent the Lambda runtime from receiving the same message twice while
    // it is still processing.  Retention is kept short (4h) because a complaint
    // request that is not processed within hours is stale and useless.
    this.redactQueue = new sqs.Queue(this, `ComplAIRedactQueue-${environment}`, {
      queueName: `complai-redact-${environment}`,
      visibilityTimeout: cdk.Duration.seconds(90),
      retentionPeriod: cdk.Duration.hours(4),
      deadLetterQueue: {
        maxReceiveCount: 3,
        queue: this.redactDlq,
      },
      removalPolicy: environment === 'production'
        ? cdk.RemovalPolicy.RETAIN
        : cdk.RemovalPolicy.DESTROY,
    });

    new cdk.CfnOutput(this, 'ComplAIRedactQueueUrl', {
      value: this.redactQueue.queueUrl,
      description: `SQS queue URL for the async redact flow (${environment})`,
    });

    // Feedback queue DLQ — receives feedback messages that fail after maxReceiveCount attempts
    this.feedbackDlq = new sqs.Queue(this, `ComplAIFeedbackDLQ-${environment}`, {
      queueName: `complai-feedback-dlq-${environment}`,
      retentionPeriod: cdk.Duration.days(7),
      removalPolicy: environment === 'production'
        ? cdk.RemovalPolicy.RETAIN
        : cdk.RemovalPolicy.DESTROY,
    });

    // Main feedback queue
    // Visibility timeout (90s) must be ≥ 1.5× the worker Lambda timeout (60s)
    // Retention is kept short (4 hours) as feedback is typically processed within minutes
    this.feedbackQueue = new sqs.Queue(this, `ComplAIFeedbackQueue-${environment}`, {
      queueName: `complai-feedback-${environment}`,
      visibilityTimeout: cdk.Duration.seconds(90),
      retentionPeriod: cdk.Duration.hours(4),
      deadLetterQueue: {
        maxReceiveCount: 3,
        queue: this.feedbackDlq,
      },
      removalPolicy: environment === 'production'
        ? cdk.RemovalPolicy.RETAIN
        : cdk.RemovalPolicy.DESTROY,
    });

    new cdk.CfnOutput(this, 'ComplAIFeedbackQueueUrl', {
      value: this.feedbackQueue.queueUrl,
      description: `SQS queue URL for the async feedback flow (${environment})`,
    });

    new cdk.CfnOutput(this, 'ComplAIFeedbackDLQUrl', {
      value: this.feedbackDlq.queueUrl,
      description: `SQS dead-letter queue for feedback (${environment})`,
    });
  }
}

