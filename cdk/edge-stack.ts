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
      {
        // The read timeout must match the Lambda timeout (60 s) so that long-running
        // requests like SSE streaming on /complai/ask are not terminated by CloudFront.
        readTimeout: cdk.Duration.seconds(60),
      },
    );

    // Custom cache policy: cache idempotent responses (GET, HEAD, OPTIONS) for 60 s.
    // POST / PUT / DELETE / PATCH requests are never cached by CloudFront regardless
    // of the cache policy — they always reach the origin.
    //
    // This means:
    //   - GET /, GET /health, GET /health/startup → cached for 60 s
    //   - POST /complai/ask, POST /complai/redact, POST /complai/feedback → NOT cached
    //   - OPTIONS preflight responses → cached for 60 s (keyed on Origin + CORS headers)
    const cachePolicy = new cloudfront.CachePolicy(this, `ComplAICachePolicy-${environment}`, {
      cachePolicyName: `ComplAICachePolicy-${environment}`,
      comment: `Caches GET/HEAD/OPTIONS for 60 s — ComplAI ${environment}`,
      defaultTtl: cdk.Duration.seconds(60),
      minTtl: cdk.Duration.seconds(0),
      maxTtl: cdk.Duration.seconds(60),
      cookieBehavior: cloudfront.CacheCookieBehavior.none(),
      // Include CORS-related headers in the cache key so that OPTIONS preflight
      // responses are cached correctly for each distinct CORS request.
      headerBehavior: cloudfront.CacheHeaderBehavior.allowList(
        'Origin',
        'Access-Control-Request-Method',
        'Access-Control-Request-Headers',
      ),
      queryStringBehavior: cloudfront.CacheQueryStringBehavior.all(),
      enableAcceptEncodingGzip: true,
      enableAcceptEncodingBrotli: true,
    });

    const distribution = new cloudfront.Distribution(this, `ComplAIDistribution-${environment}`, {
      comment: `ComplAI CloudFront - ${environment}`,
      webAclId: webAcl.attrArn,
      defaultBehavior: {
        origin,
        allowedMethods: cloudfront.AllowedMethods.ALLOW_ALL,
        cachePolicy,
        originRequestPolicy: cloudfront.OriginRequestPolicy.ALL_VIEWER_EXCEPT_HOST_HEADER,
      },
    });

    this.distributionDomainName = distribution.distributionDomainName;
  }
}
