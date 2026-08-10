# ExecPlane WebSocket 测试端

## 使用低权限用户运行（推荐）

服务端命令继承 `ws-server.py` 进程的真实 Linux UID，不会自行提权。以 root 启动时可用 `--user` 永久降权：

```sh
useradd --create-home --shell /bin/bash minis 2>/dev/null || true
set -a; . /opt/execplane/server.env; set +a
/opt/execplane/venv/bin/python /opt/execplane/ws-server.py \
  --host 127.0.0.1 --port 8767 --user minis --workdir /home/minis
```

降权在开始监听前完成；之后即使命令被注入，也无法恢复 root。不要把 `minis` 加入 sudo/docker 等特权组，只给它需要访问的项目目录权限。


```sh
EXECPLANE_TOKEN='App 中复制的令牌' python3 tools/execplane/ws-agent.py
```

App 开启 `127.0.0.1:8765` 后，列表出现 `proot-test`。可在设置页显式发送命令。

## 正向连接：App 主动连接 WebSocket Server

```sh
EXECPLANE_TOKEN='自定义令牌' python3 tools/execplane/ws-server.py
```

App 中添加：

- 名称：`proot-server`
- URL：`ws://127.0.0.1:8766`
- Token：与上面一致

非本机部署必须使用 `wss://`；参考脚本本身不终止 TLS，生产测试应放在 Caddy/Nginx 后。

> 两个参考端都允许 shell 命令，只用于受信任测试环境。App 不会自动执行远端推送的命令，命令只能由用户在沙箱设置页主动发起。
