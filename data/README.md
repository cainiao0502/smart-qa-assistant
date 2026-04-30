# data 目录说明

这个目录用于存放运行期会读取的本地数据。

当前约定分成两类：

1. 可提交的模板和示例
2. 仅本地使用的运行态数据

## 可提交内容

- `data/skills/codeagent/SKILL.md`
  - 随项目分发的示例可执行 Skill
- `data/mcp/custom-servers.example.json`
  - 自定义 MCP 配置模板

## 本地运行态内容

- `data/mcp/custom-servers.json`
  - 本地实际使用的 MCP 配置
  - 可能包含 API Key、工作目录、命令参数等敏感或机器相关信息
  - 已加入 `.gitignore`

- `data/skills/readme/`
  - 当前视为本地实验性或业务私有 Skill
  - 已加入 `.gitignore`

## 首次使用

如果你要启用自定义 MCP，请先复制模板：

```powershell
Copy-Item data/mcp/custom-servers.example.json data/mcp/custom-servers.json
```

然后按本机环境补充：

- `command`
- `args`
- `toolNamePrefix`
- `environment`
- API Key
