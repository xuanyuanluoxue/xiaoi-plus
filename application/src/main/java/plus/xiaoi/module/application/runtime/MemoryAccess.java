package plus.xiaoi.module.application.runtime;

/**
 * 记忆读写端口（P2-G）。
 *
 * <p>记忆文件在小爱进程的私有目录，设置 App 读不到；所以 UI 只能经 8771 的
 * {@code xiaoai_memory} 工具间接读写。实现放在 {@code :platform}（BridgeClient），
 * {@code :ui} 只认识这个接口。</p>
 */
public interface MemoryAccess {

    /**
     * 调用一次 {@code xiaoai_memory}。
     *
     * @param op      {@code get} / {@code append} / {@code replace}
     * @param section 小节路径；{@code get} 时可为空
     * @param text    写入内容；{@code get} 时可为空
     * @param limit   {@code get} 每小节条数；{@code <=0} 用默认值
     * @return 工具返回的 JSON 文本；桥不可达 / 工具不可用时返回 null
     */
    String call(String op, String section, String text, int limit);
}
