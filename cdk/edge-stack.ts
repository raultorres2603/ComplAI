import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as wafv2 from 'aws-cdk-lib/aws-wafv2';
import * as cloudfront from 'aws-cdk-lib/aws-cloudfront';
import * as origins from 'aws-cdk-lib/aws-cloudfront-origins';
import { DeploymentEnvironment } from './deployment-environment';

export interface EdgeStackProps extends cdk.StackProps {
  readonly environment: DeploymentEnvironment;
  readonly httpApiId: string;
  readonly httpApiRegion: string;
}

export class EdgeStack extends cdk.Stack {
  readonly distributionDomainName: string;

  constructor(scope: Construct, id: string, props: EdgeStackProps) {
    super(scope, id, props);

    const { environment, httpApiId, httpApiRegion } = props;

    const webAcl = new wafv2.CfnWebACL(this, `ComplAIWebACL-${environment}`, {
      name: `ComplAIWebACL-${environment}`,
      scope: 'CLOUDFRONT',
      defaultAction: { block: {} },
      visibilityConfig: {
        cloudWatchMetricsEnabled: true,
        metricName: `ComplAIWaf-${environment}`,
        sampledRequestsEnabled: true,
      },
      rules: [
        {
          name: `AllowSpain-${environment}`,
          priority: 1,
          action: { allow: {} },
          statement: {
            geoMatchStatement: {
              countryCodes: ['ES'],
            },
          },
          visibilityConfig: {
            cloudWatchMetricsEnabled: true,
            metricName: `AllowSpain-${environment}`,
            sampledRequestsEnabled: true,
          },
        },
        {
          name: `RateLimit100per5min-${environment}`,
          priority: 2,
          action: { block: {} },
          statement: {
            rateBasedStatement: {
              limit: 100,
              evaluationWindowSec: 300,
              aggregateKeyType: 'IP',
            },
          },
          visibilityConfig: {
            cloudWatchMetricsEnabled: true,
            metricName: `RateLimit-${environment}`,
            sampledRequestsEnabled: true,
          },
        },
      ],
    });

    const origin = new origins.HttpOrigin(
      `${httpApiId}.execute-api.${httpApiRegion}.amazonaws.com`,
    );

    const distribution = new cloudfront.Distribution(this, `ComplAIDistribution-${environment}`, {
      comment: `ComplAI CloudFront - ${environment}`,
      webAclId: webAcl.attrArn,
      defaultBehavior: {
        origin,
        allowedMethods: cloudfront.AllowedMethods.ALLOW_ALL,
        cachePolicy: cloudfront.CachePolicy.CACHING_DISABLED,
        originRequestPolicy: cloudfront.OriginRequestPolicy.ALL_VIEWER_EXCEPT_HOST_HEADER,
      },
    });

    this.distributionDomainName = distribution.distributionDomainName;
  }
}
