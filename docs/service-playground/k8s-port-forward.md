# Kubernetes Port Forward (开发调试)

本文档用于在 `Ingress` 还没就绪，或者只想本机快速调试时，通过 `kubectl port-forward` 访问集群里的 `playground-stage` 服务。

## 1) 基础命令

```bash
kubectl -n playground-stage port-forward svc/playground-stage 8080:8080
```

含义：
- `-n playground-stage`：目标命名空间
- `svc/playground-stage`：转发目标 Service
- `8080:8080`：`本机端口:集群服务端口`

执行后，本机访问：

```bash
curl "http://127.0.0.1:8080/api/v1/echo?message=hello&from=local"
```

## 2) 本机端口冲突时

如果本机 `8080` 已占用，改左边端口即可（右边服务端口通常不变）：

```bash
kubectl -n playground-stage port-forward svc/playground-stage 5000:8080
curl "http://127.0.0.1:5000/api/v1/echo?message=hello&from=local"
```

## 3) 是否必须一直运行

是。`port-forward` 是一条临时隧道，命令退出后通道就消失。

- 前台运行：最直观，`Ctrl + C` 停止
- 后台运行（可选）：

```bash
nohup kubectl -n playground-stage port-forward svc/playground-stage 5000:8080 > /tmp/pf-playground-stage.log 2>&1 &
```

查看是否在跑：

```bash
ps aux | grep "port-forward"
```

停止：

```bash
kill <pid>
```

## 4) 常见报错

- `address already in use`：本机端口被占用，换本机端口（如 `5000`）
- `service not found`：先检查服务名

```bash
kubectl -n playground-stage get svc
```

- 连接中断：网络波动/电脑休眠后常见，重新执行 `port-forward` 即可

## 5) 适用场景

- 快速验证 API、联调前后端
- Ingress / LoadBalancer 还未分配外网地址
- 不希望把服务暴露到公网

注意：`port-forward` 默认仅本机访问，不能当公网入口。

