package plus.xiaoi.module.application.memory;

/**
 * 记忆文件的读写端口（P2-D）。
 *
 * <p>只做「整文件读」与「整文件原子写」两件事：解析/分节/上限在
 * {@link MemoryService}，真正的文件系统操作在 `:infra` 的
 * {@code MemoryFileStore}。本接口刻意不出现 {@code java.io} 类型，
 * 让 `:application` 保持零 I/O。</p>
 */
public interface MemoryStore {

    /**
     * 读一个文件（相对记忆目录，例如 {@code MEMORY.md}）。
     *
     * @return 文件内容；不存在 / 不可读 / 读失败返回 null
     */
    String read(String name);

    /**
     * 原子写：先备份旧文件，再写临时文件并 rename 覆盖。失败时<strong>原文件必须保持原样</strong>。
     *
     * @return 成功返回备份文件名（没有旧文件时是空串）；失败返回 null
     */
    String write(String name, String content);
}
