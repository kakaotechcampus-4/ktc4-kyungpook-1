import { CardArea, CardTech } from '@/api/schemas';
import type { z } from 'zod';

export const areaLabels: Record<z.infer<typeof CardArea>, string> = {
  BACKEND: 'Backend', FRONTEND: 'Frontend', MOBILE: 'Mobile', DEVOPS: 'DevOps',
  AI: 'AI', DB: 'Database', TEST: 'Testing', DOCS: 'Documentation',
};
export const techLabels: Record<z.infer<typeof CardTech>, string> = {
  C: 'C', CPP: 'C++', CSHARP: 'C#', JAVA: 'Java', KOTLIN: 'Kotlin', PYTHON: 'Python', GO: 'Go', RUST: 'Rust',
  SWIFT: 'Swift', DART: 'Dart', TYPESCRIPT: 'TypeScript', JAVASCRIPT: 'JavaScript', SCALA: 'Scala', SPRING: 'Spring',
  JPA: 'JPA', NODEJS: 'Node.js', NESTJS: 'NestJS', EXPRESS: 'Express', DJANGO: 'Django', FASTAPI: 'FastAPI',
  FLASK: 'Flask', DOTNET: '.NET', REACT: 'React', NEXTJS: 'Next.js', VUE: 'Vue', ANGULAR: 'Angular',
  ANDROID: 'Android', IOS: 'iOS', FLUTTER: 'Flutter', REACT_NATIVE: 'React Native', SQL: 'SQL', MYSQL: 'MySQL',
  POSTGRESQL: 'PostgreSQL', ORACLE: 'Oracle', MONGODB: 'MongoDB', REDIS: 'Redis', ELASTICSEARCH: 'Elasticsearch',
  KAFKA: 'Kafka', RABBITMQ: 'RabbitMQ', SPARK: 'Spark', AIRFLOW: 'Airflow', HADOOP: 'Hadoop', DOCKER: 'Docker',
  KUBERNETES: 'Kubernetes', AWS: 'AWS', GCP: 'GCP', AZURE: 'Azure', TERRAFORM: 'Terraform', LINUX: 'Linux',
  NGINX: 'Nginx', CI_CD: 'CI/CD', GITHUB_ACTIONS: 'GitHub Actions', JENKINS: 'Jenkins', PROMETHEUS: 'Prometheus',
  GRAFANA: 'Grafana', LLM: 'LLM', PYTORCH: 'PyTorch', TENSORFLOW: 'TensorFlow', SCIKIT_LEARN: 'scikit-learn',
  LANGCHAIN: 'LangChain', GRAPHQL: 'GraphQL', GRPC: 'gRPC', MSA: 'MSA', UNITY: 'Unity', UNREAL: 'Unreal',
};
export const areaOptions = [{ value: 'ALL', label: '모든 분야' }, ...CardArea.options.map(value => ({ value, label: areaLabels[value] }))];
export const techOptions = [{ value: 'ALL', label: '모든 기술' }, ...CardTech.options.map(value => ({ value, label: techLabels[value] }))];

export type TaggedCard = { areas?: z.infer<typeof CardArea>[]; techs?: z.infer<typeof CardTech>[] };
/** AND between categories; missing tags are unknown, never inferred from STAR text. */
export function matchesTags(card: TaggedCard, area: string, tech: string): boolean {
  return (area === 'ALL' || (card.areas ?? []).some(value => value === area))
    && (tech === 'ALL' || (card.techs ?? []).some(value => value === tech));
}
