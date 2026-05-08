# APK Explorer & Editor MCP 功能

本分支在 APK 反编译/解包后启动一个仅绑定手机本机回环地址的 MCP/JSON-RPC 服务，方便同一台安卓手机上的 MCP 客户端直接访问 AEE 反编译项目文件。

## 连接

1. 在手机上打开 AEE，并至少反编译/Explore 一个 APK。
2. 在 APK Explore 页面底部点击 `M`，进入 MCP 设置页。
3. 开启 MCP 服务；如需修改端口，在设置页修改并点击“应用端口”。
4. 在同一台手机上的 MCP 客户端 App 中填写设置页显示的地址。默认：

```text
Streamable HTTP: http://127.0.0.1:8765/mcp
Legacy SSE: http://127.0.0.1:8765/sse
Health: http://127.0.0.1:8765/health
```

同机使用无需电脑或命令行操作。端口可在 AEE 的 MCP 服务设置页面自定义；如果修改端口，请在 MCP 客户端中同步修改 URL 端口。

## 支持的传输

- Streamable HTTP：`POST /mcp`；如果客户端请求头包含 `Accept: text/event-stream`，响应会以 SSE `message` 事件返回。
- Streamable HTTP server events：`GET /mcp` + `Accept: text/event-stream` 会保持事件流并发送 keepalive。
- Legacy SSE：`GET /sse` 会返回 `endpoint` 事件，客户端随后向 `/messages?sessionId=...` POST JSON-RPC 消息，响应通过 SSE `message` 事件返回。
- Plain JSON-RPC：`POST /mcp` 或 `POST /jsonrpc`，响应为 `application/json`。

## 支持的 JSON-RPC 方法

- `initialize`
- `tools/list`
- `tools/call`
- `resources/list`
- `resources/read`

## Tools

- `list_projects`：列出已反编译项目
- `get_project_info`：读取项目元信息
- `list_files`：列目录
- `read_file`：读取文本文件
- `write_file`：写入文本文件；写 `.smali` 会自动标记 `smali_edited=true`
- `search_files`：全文搜索小文本文件
- `replace_in_file`：字面量替换

所有文件访问都被限制在 AEE 内部缓存中的项目根目录，服务默认仅监听 `127.0.0.1:8765`。

## 示例

```bash
curl -s http://127.0.0.1:8765/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_projects","arguments":{}}}'

curl -s http://127.0.0.1:8765/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"read_file","arguments":{"project":"latest","path":"AndroidManifest.xml"}}}'
```


## Streamable HTTP / SSE 示例

Streamable HTTP，要求 SSE 响应：

```bash
curl -N http://127.0.0.1:8765/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
```

Legacy SSE：

```bash
# 终端 1：保持 SSE 连接，输出里会出现 /messages?sessionId=...
curl -N http://127.0.0.1:8765/sse

# 终端 2：把 JSON-RPC POST 到上一步输出的 endpoint
curl -s 'http://127.0.0.1:8765/messages?sessionId=<SESSION_ID>' \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
```
