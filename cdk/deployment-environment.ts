// Shared type used by every stack to identify which deployment environment it
// belongs to. Keeping it in one place avoids divergence between the files.
export type DeploymentEnvironment = 'development' | 'production';

