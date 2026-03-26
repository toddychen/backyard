# Session handoff（给下一次对话用）

> 复制本文件全文到新 Chat，或在新对话里 `@docs/service-playground/session-handoff-2026-03-18.md`，可快速接上上下文。

## 项目与目标

- Monorepo **backyard**：本地 **kind + Helm**，云上 **GKE + Argo CD + Helm**，`playground` 分 **stage / prod**（stage 1 副本，prod 2 副本）。
- GitOps：镜像 tag 由 CI 写进 `values-stage.yaml` / `values-prod.yaml`；stage 自动 sync，prod 手动 sync（Argo UI）。

## 今天已落实 / 已修改的仓库内容

### Ingress（GKE 无 Address 的根因与修复）

- **现象**：`kubectl get ingressclass` 为空；Ingress 使用 `spec.ingressClassName: gce` 时 **没有对应 IngressClass**，GCE 控制器不接管 → `status.loadBalancer` 长期 `{}`。
- **代码修复**：`infra/helm/playground/values-stage.yaml` 与 `values-prod.yaml` 改为：
  - `ingress.className: ""`
  - `ingress.annotations.kubernetes.io/ingress.class: "gce"`
- **建议 commit message**（若尚未提交）：`fix(playground): use GCE ingress annotation instead of ingressClassName`

### 开发调试：kubectl port-forward 文档

- 文档路径：**`docs/service-playground/k8s-port-forward.md`**（按服务分子目录）。
- 本机备用端口示例为 **`5000:8080`**（非集群端口；集群 Service 仍为 8080）。
- 根 **`README.md`** 中 GCP 小节已链接到上述文档。

### Argo CD「精简可选组件」（可选，用户曾说先不急着动集群）

- **`infra/argocd/helm/values-lean.yaml`**：Helm 安装时关 ApplicationSet（replicas 0）、Notifications、Dex、commit server 等。
- **`scripts/argocd-scale-down-optional.sh`**：对已 `kubectl apply` 安装的集群，scale 掉可选 Deployment。
- **`infra/argocd/README.md`**：说明与登录方式。

## 今天讨论清楚的概念

- **`kube-system` 大量 Pod**：多为 GKE 每节点 DaemonSet；**不是按「每个系统 Pod」单独计费**，Autopilot 上主要盯你自己 workload 的请求资源；Standard 则主要是节点 VM 费用。
- **配额 vs 账单**：SSD/CPU **quota** 会随节点数波动；曾出现节点增多再回落，SSD 用量随之变化。
- **`kubectl port-forward`**：经 **Kubernetes API** 的临时端口转发，**不是 SSH tunnel**；进程结束即失效；可用 `nohup ... &` 后台跑，但仍可能因网络/休眠断开。

## 明天可继续的方向（待办线索）

1. **验证 Ingress**：推送/同步后看 `kubectl -n playground-stage get ingress playground-stage -w` 是否出现 **ADDRESS**；若仍无，再查 `HttpLoadBalancing` addon、或改用 **Service LoadBalancer** 作过渡。
2. **确认 Argo sync**：`kubectl -n argocd get applications.argoproj.io -o wide` + `describe` / controller 日志。
3. 若需 **长期公网访问**：Ingress 稳定后补 **hostname / DNS**；或评估 **静态 IP** 与成本。

## 关键文件索引

| 用途 | 路径 |
|------|------|
| Stage / Prod 值（含 Ingress 注解修复） | `infra/helm/playground/values-stage.yaml`, `values-prod.yaml` |
| Port-forward 说明 | `docs/service-playground/k8s-port-forward.md` |
| Argo Application | `infra/argocd/applications/playground-stage.yaml`, `playground-prod.yaml` |
| Argo 精简 Helm / 脚本 | `infra/argocd/helm/values-lean.yaml`, `scripts/argocd-scale-down-optional.sh`, `infra/argocd/README.md` |

---
*上次更新：收工小结，供下一会话接续。*

