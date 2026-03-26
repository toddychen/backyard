# Backyard — Architecture & Tech Stack

> Just me, messing around with containers and clusters in my own backyard.

Personal learning project. 复现企业级 cluster 架构，从 local 开发到 GCP cloud 部署，monorepo 管理所有服务。

---

## 项目理念

- `playground` 是实验田，新想法、新 API、新技术先在这里试
- 功能成熟后，独立拆分成专属 service（如 `dory`）
- 每个 service 有自己的 Docker image、Helm chart、CI pipeline
- Local 开发和 Cloud 部署用同一套 K8s 配置，只换参数

---

## Repo 结构

```
backyard/
├── services/
│   ├── playground/              # 实验田，Spring Boot API service
│   │   ├── src/
│   │   │   └── main/
│   │   │       ├── java/com/backyard/playground/
│   │   │       │   ├── PlaygroundApplication.java
│   │   │       │   └── controller/
│   │   │       │       └── EchoController.java
│   │   │       └── resources/
│   │   │           ├── application.properties
│   │   │           └── logback-spring.xml
│   │   ├── Dockerfile
│   │   └── pom.xml
│   │
│   ├── dory/                    # [以后] Reminder service
│   └── ...                      # [以后] 其他独立 service
│
├── mobile/                      # [以后] Mobile app
│   ├── ios/
│   └── android/
│
├── web/                         # [以后] Web frontend
│   └── dashboard/
│
├── infra/
│   ├── helm/
│   │   ├── playground/          # Helm chart for playground
│   │   │   ├── Chart.yaml
│   │   │   ├── values.yaml          # local defaults
│   │   │   ├── values-gcp.yaml      # GCP overrides
│   │   │   └── templates/
│   │   │       ├── deployment.yaml
│   │   │       ├── service.yaml
│   │   │       └── ingress.yaml
│   │   └── dory/                # [以后]
│   │
│   ├── kind/
│   │   └── cluster-config.yaml  # kind cluster 配置
│   │
│   └── terraform/               # [以后] GCP 资源 provisioning
│       ├── main.tf
│       ├── variables.tf
│       └── outputs.tf
│
├── .github/
│   └── workflows/
│       ├── playground.yml       # CI/CD for playground
│       └── dory.yml             # [以后]
│
├── docs/
│   └── architecture.md          # 本文件
│
└── README.md
```

---

## Tech Stack

### Application

| 层 | 选型 | 说明 |
|---|---|---|
| 语言 | Java 21 | Virtual threads 支持 |
| 框架 | Spring Boot 3 + Spring MVC | 生产标配，生态最完整 |
| 日志 | SLF4J + Logback | Spring Boot 默认内建 |
| 日志格式 | logstash-logback-encoder | JSON 结构化输出 |
| 健康检查 | Spring Boot Actuator | K8s liveness/readiness probe |
| 构建 | Maven | |
| Dev 热重载 | spring-boot-devtools | 1-3 秒 restart，本地开发用 |

### Local 开发环境

| 工具 | 用途 | 说明 |
|---|---|---|
| Cursor | 代码编辑 | AI 辅助写代码 |
| Docker Desktop | Container runtime | kind 依赖 Docker |
| kind | Local Kubernetes cluster | 轻量，支持多 node，贴近 GKE |
| kubectl | 管理 K8s cluster | |
| Helm | 部署和管理 K8s 应用 | 模板 + 包管理 + 生命周期 |
| Grafana + Loki | 本地 log 收集和查询 | 替代 Splunk，架构兼容 |
| Promtail | Log 采集 agent | 收集 pod stdout 转发到 Loki |

### CI / CD

| 工具 | 用途 |
|---|---|
| GitHub | 代码托管，monorepo |
| GitHub Actions | build → test → docker build → push image |
| GHCR（GitHub Container Registry） | Docker image 存储，免费 |
| Helm | 统一部署，local/GCP 只换 values |

GitHub Actions 按 path 触发，互相隔离：
```yaml
on:
  push:
    paths:
      - 'services/playground/**'
```

### GCP Cloud

| 服务 | 用途 |
|---|---|
| GKE Autopilot | Managed K8s，按 pod 计费，无需管 node |
| Artifact Registry | [可选] 替代 GHCR 存储 Docker image |
| GCP Load Balancer | 外部流量入口 |
| GCP Cloud Logging | 基础 log 收集（免费层） |
| Splunk Connect for K8s | 接入 Splunk，DaemonSet 方式部署 |
| Cloud SQL | [以后] Managed PostgreSQL |
| Cloud Memorystore | [以后] Managed Redis |
| Terraform | [以后] GCP 资源 provisioning |

---

## 开发工作流

### 日常写代码（高频）

```
Cursor 编辑代码
    ↓
spring-boot-devtools 监听变化（1-3 秒 restart）
    ↓
curl localhost:8080/api/v1/echo?message=hello  验证
```

不经过 Docker / K8s，快速迭代。

### 验证 K8s 行为（按需）

```
mvn package -DskipTests
    ↓
docker build -t playground:dev .
    ↓
kind load docker-image playground:dev
    ↓
helm upgrade --install playground ./infra/helm/playground
    ↓
kubectl get pods / kubectl logs
```

K8s 只用来验证部署配置、多 pod 行为、ingress 路由，不参与日常开发循环。

### CI/CD（push 触发）

```
git push
    ↓
GitHub Actions 触发（仅当 services/playground/** 有变动）
    ↓
mvn test
    ↓
docker build → push to ghcr.io/<username>/service-playground:<git-sha>
    ↓
helm upgrade（deploy to GKE）
```

---

## Image 管理策略

| 环境 | Tag | 策略 |
|---|---|---|
| Local dev | `playground:dev` | 固定 tag 覆盖，`imagePullPolicy: Never` |
| CI build | `service-playground:<git-sha>` | 每个 commit 唯一，push 到 GHCR |
| GCP production | `service-playground:<git-sha>` | Helm values 指定具体 tag |

---

## K8s 部署结构

```
Ingress (nginx / GCP Load Balancer)
    │
    ▼
Service (ClusterIP)
    │
    ├── Pod: playground (replica 1, local)
    ├── Pod: playground (replica 2, GCP)
    └── Pod: playground (replica 3-5, GCP)
```

### Deployment 关键配置

```yaml
# values.yaml (local)
image: playground:dev
replicas: 1
imagePullPolicy: Never

resources:
  requests:
    memory: 256Mi
    cpu: 100m
  limits:
    memory: 512Mi
    cpu: 500m

livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 15
  periodSeconds: 5
```

```yaml
# values-gcp.yaml (GCP overrides)
image: ghcr.io/<username>/service-playground:<git-sha>
replicas: 3
imagePullPolicy: Always
```

---

## Log 架构

### 输出策略

Java 代码统一输出 **structured JSON log** 到 stdout，由 K8s / container runtime 收集。

```
Spring Boot stdout (JSON)
    ↓
K8s 收集 pod stdout
    ↓
Promtail (local) / Splunk Forwarder (GCP)
    ↓
Loki (local) / Splunk (GCP)
    ↓
Grafana (local) / Splunk Dashboard (GCP)
```

### Logback 配置

- `local` profile：普通可读文本，DEBUG level，方便开发
- 非 local（K8s）：JSON 格式，INFO level，方便 Loki / Splunk 解析

### Log Level 设计

| Level | 用途 |
|---|---|
| DEBUG | 原始入参、中间状态，local 开发用 |
| INFO | 核心业务动作，production 标准 level |
| WARN | 非预期但可恢复的情况 |
| ERROR | 需要关注的错误，触发告警 |

---

## 第一个 API：Echo

**Endpoint：** `GET /api/v1/echo`

**Parameters：**
- `message`（required）— 要 echo 的内容
- `from`（optional）— 来源标识

**示例：**
```bash
curl "localhost:8080/api/v1/echo?message=hello&from=yi"
```

**Response：**
```json
{
  "echo": "hello",
  "from": "yi",
  "timestamp": "2024-01-15T10:23:01Z"
}
```

**Log 输出（K8s JSON 格式）：**
```json
{"@timestamp":"...","level":"DEBUG","message":"Echo request received: message='hello', from='yi'"}
{"@timestamp":"...","level":"INFO","message":"Echo: message='hello' from='yi'"}
{"@timestamp":"...","level":"DEBUG","message":"Echo response: {echo=hello, from=yi, timestamp=...}"}
```

---

## Kind Cluster 配置

```yaml
# infra/kind/cluster-config.yaml
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
  - role: worker
  - role: worker
```

```bash
# 创建 cluster
kind create cluster --name backyard --config infra/kind/cluster-config.yaml

# 删除重建（30 秒）
kind delete cluster --name backyard && kind create cluster --name backyard --config infra/kind/cluster-config.yaml

# 载入本地 image
kind load docker-image playground:dev --name backyard
```

---

## 演进路线

```
Phase 1  ── 现在
           Cursor + Spring Boot devtools 本地跑
           Echo API，structured JSON log
           ↓
Phase 2  ── Local K8s
           Docker build，kind cluster
           Helm deploy，验证多 pod 行为
           Grafana + Loki 验证 log 收集
           ↓
Phase 3  ── CI/CD
           GitHub Actions pipeline
           GHCR image registry
           ↓
Phase 4  ── GCP
           GKE Autopilot，3-5 pods
           Splunk Connect for K8s
           ↓
Phase 5  ── 扩展
           独立 service（如 dory）
           数据库：PostgreSQL（Spring Data JPA）
           缓存：Redis（Spring Cache）
           调度：Spring Scheduler
           异步：Spring Async
           Terraform 管理 GCP 资源
           ↓
Phase 6  ── 更多可能
           gRPC（service 间通信）
           GraphQL（复杂数据聚合）
           Kafka（异步消息）
           Mobile app
           Web dashboard
```

---

## 以后扩展的 Spring 能力（按需引入）

| 功能 | Spring 模块 | 引入方式 |
|---|---|---|
| SQL 数据库 | Spring Data JPA | `spring-boot-starter-data-jpa` |
| Redis 缓存 | Spring Cache + Redis | `spring-boot-starter-data-redis` |
| 定时任务 | Spring Scheduler | `@EnableScheduling`，零依赖 |
| 异步执行 | Spring Async | `@EnableAsync`，零依赖 |
| Schema 迁移 | Flyway | `flyway-core` |
| gRPC | Spring gRPC | `spring-grpc-spring-boot-starter` |
| GraphQL | Spring GraphQL | `spring-boot-starter-graphql` |
| 消息队列 | Spring Kafka | `spring-kafka` |
| 安全认证 | Spring Security | `spring-boot-starter-security` |

---

## Dockerfile

```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

`eclipse-temurin:21-jre-alpine`：只含 JRE，不含 JDK，image 小，生产级别标准选择。

---

## 依赖总览（pom.xml 核心）

```xml
<!-- Web -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>

<!-- Dev hot reload（本地开发） -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-devtools</artifactId>
    <scope>runtime</scope>
    <optional>true</optional>
</dependency>

<!-- Structured JSON logging -->
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>7.4</version>
</dependency>

<!-- Health check / K8s probe -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

---

*文档最后更新：项目初始化阶段*
