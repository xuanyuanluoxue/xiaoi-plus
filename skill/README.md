# skill 目录

项目内可浏览的 skill 入口。

OpenCode 实际发现的是：

```text
.opencode/skills/<name>/SKILL.md
```

这里用软链接把同一份内容映射到项目根，方便查看和编辑，不复制第二份，避免内容漂移。

```text
skill/
└── xiaoi-release -> ../.opencode/skills/xiaoi-release
```

新增 skill 时：

1. 在 `.opencode/skills/<name>/SKILL.md` 写正式内容；
2. 在这里加同名软链接：
   `ln -s ../.opencode/skills/<name> skill/<name>`
3. 需要附带脚本/模板时放 `.opencode/skills/<name>/{scripts,references,assets}/`。

Windows 文件管理器如果不支持软链接，直接看 `.opencode/skills/` 下的实体文件；
Git 里保留软链接，Linux/WSL 下可正常编辑。
