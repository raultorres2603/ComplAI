import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as s3 from 'aws-cdk-lib/aws-s3';
import { DeploymentEnvironment } from './deployment-environment';

export interface StorageStackProps extends cdk.StackProps {
  readonly environment: DeploymentEnvironment;
}

export class StorageStack extends cdk.Stack {
  // Exposed so the LambdaStack can wire IAM grants and inject bucket names.
  readonly proceduresBucket: s3.Bucket;
  readonly eventsBucket: s3.Bucket;
  readonly newsBucket: s3.Bucket;
  readonly cityInfoBucket: s3.Bucket;
  readonly complaintsBucket: s3.Bucket;
  readonly feedbackBucket: s3.Bucket;
  // Stores the compiled fat JARs used by both Lambda functions.
  // Keeping deployment artifacts here lets CI upload the JAR once and reference
  // it with Code.fromBucket(), which means the CDK bootstrap bucket never
  // receives the JAR and does not accumulate large, stale objects.
  readonly deploymentsBucket: s3.Bucket;

  constructor(scope: Construct, id: string, props: StorageStackProps) {
    super(scope, id, props);

    const { environment } = props;

    // Procedures corpus — read by Lambda at startup for RAG context.
    // RETAIN in all environments: the file is uploaded out-of-band and must
    // survive stack updates or accidental teardowns.
    this.proceduresBucket = new s3.Bucket(this, `ComplAIProceduresBucket-${environment}`, {
      bucketName: `complai-procedures-${environment}`,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      encryption: s3.BucketEncryption.S3_MANAGED,
      versioned: false,
      lifecycleRules: [{ expiration: cdk.Duration.days(365) }],
    });

    // Events corpus — read by Lambda at startup for event context.
    // RETAIN in all environments: the file is uploaded out-of-band and must
    // survive stack updates or accidental teardowns.
    this.eventsBucket = new s3.Bucket(this, `ComplAIEventsBucket-${environment}`, {
      bucketName: `complai-events-${environment}`,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      encryption: s3.BucketEncryption.S3_MANAGED,
      versioned: false,
      lifecycleRules: [{ expiration: cdk.Duration.days(365) }],
    });

    // News corpus — read by Lambda at startup for news context.
    // RETAIN in all environments: the file is uploaded out-of-band and must
    // survive stack updates or accidental teardowns.
    this.newsBucket = new s3.Bucket(this, `ComplAINewsBucket-${environment}`, {
      bucketName: `complai-news-${environment}`,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      encryption: s3.BucketEncryption.S3_MANAGED,
      versioned: false,
      lifecycleRules: [{ expiration: cdk.Duration.days(365) }],
    });

    // City-info corpus - read by Lambda at startup for city information fallback context.
    this.cityInfoBucket = new s3.Bucket(this, `ComplAICityInfoBucket-${environment}`, {
      bucketName: `complai-cityinfo-${environment}`,
      removalPolicy: cdk.RemovalPolicy.RETAIN,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      encryption: s3.BucketEncryption.S3_MANAGED,
      versioned: false,
      lifecycleRules: [{ expiration: cdk.Duration.days(365) }],
    });

    // Generated complaint PDFs — short-lived, deleted automatically after 30 days.
    // In development the bucket is destroyed with the stack to keep the account clean.
    this.complaintsBucket = new s3.Bucket(this, `ComplAIComplaintsBucket-${environment}`, {
      bucketName: `complai-complaints-${environment}`,
      removalPolicy: environment === 'production'
        ? cdk.RemovalPolicy.RETAIN
        : cdk.RemovalPolicy.DESTROY,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      encryption: s3.BucketEncryption.S3_MANAGED,
      versioned: false,
      lifecycleRules: [{ expiration: cdk.Duration.days(30) }],
    });

    // User feedback JSON files — short-lived, deleted automatically after 30 days (dev) or 90 days (prod).
    // Feedback is processed quickly; retention is short-lived for privacy.
    this.feedbackBucket = new s3.Bucket(this, `ComplAIFeedbackBucket-${environment}`, {
      bucketName: `complai-feedback-${environment}`,
      removalPolicy: environment === 'production'
        ? cdk.RemovalPolicy.RETAIN
        : cdk.RemovalPolicy.DESTROY,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      encryption: s3.BucketEncryption.S3_MANAGED,
      versioned: false,
      lifecycleRules: [{ 
        expiration: cdk.Duration.days(environment === 'production' ? 90 : 30) 
      }],
    });

    // Compiled fat JARs — written by CI once per build, read by CloudFormation
    // during Lambda deployment (Code.fromBucket).  Keeping these here instead of
    // relying on the CDK bootstrap staging bucket (cdk-hnb659fds-assets-*) means
    // we own the lifecycle and the bootstrap bucket never accumulates large JARs.
    // 30-day expiry is generous: a deploy is complete within minutes; old JARs
    // beyond 30 days are never needed again.
    this.deploymentsBucket = new s3.Bucket(this, `ComplAIDeploymentsBucket-${environment}`, {
      bucketName: `complai-deployments-${environment}`,
      // Always RETAIN — losing this bucket mid-deploy would break rollbacks.
      removalPolicy: cdk.RemovalPolicy.RETAIN,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      encryption: s3.BucketEncryption.S3_MANAGED,
      versioned: false,
      lifecycleRules: [{ expiration: cdk.Duration.days(30) }],
    });

    new cdk.CfnOutput(this, 'ComplAIProceduresBucketName', {
      value: this.proceduresBucket.bucketName,
      description: `S3 bucket for procedures corpus (${environment})`,
    });

    new cdk.CfnOutput(this, 'ComplAIEventsBucketName', {
      value: this.eventsBucket.bucketName,
      description: `S3 bucket for events corpus (${environment})`,
    });

    new cdk.CfnOutput(this, 'ComplAINewsBucketName', {
      value: this.newsBucket.bucketName,
      description: `S3 bucket for news corpus (${environment})`,
    });

    new cdk.CfnOutput(this, 'ComplAICityInfoBucketName', {
      value: this.cityInfoBucket.bucketName,
      description: `S3 bucket for city-info corpus (${environment})`,
    });

    new cdk.CfnOutput(this, 'ComplAIComplaintsBucketName', {
      value: this.complaintsBucket.bucketName,
      description: `S3 bucket for generated complaint PDFs (${environment})`,
    });

    new cdk.CfnOutput(this, 'ComplAIFeedbackBucketName', {
      value: this.feedbackBucket.bucketName,
      description: `S3 bucket for user feedback JSON files (${environment})`,
    });

    new cdk.CfnOutput(this, 'ComplAIDeploymentsBucketName', {
      value: this.deploymentsBucket.bucketName,
      description: `S3 bucket for fat JAR deployments (${environment})`,
    });
  }
}

