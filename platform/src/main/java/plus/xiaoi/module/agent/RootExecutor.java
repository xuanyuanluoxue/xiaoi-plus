package plus.xiaoi.module.agent;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import plus.xiaoi.module.Logs;

/**
 * root 兜底执行器：只执行 {@link RootCommands} 里固定的命令族。
 *
 * <p>支持的动作：
 * <ul>
 *   <li>{@code app.open}（仅 {@code package}）：{@code monkey -p <pkg> ... 1}</li>
 *   <li>{@code media.control} 的 play/pause/next/prev：
 *       {@code cmd media_session dispatch ...}</li>
 * </ul>
 * 不实现 alarm.set / notification.send / device.info / volume；其余一律
 * {@code NOT_AVAILABLE}，由组合执行器或调用方决定下一步。</p>
 *
 * <p>安全约束：命令文本由固定模板 + 白名单包名拼成，调用方无法注入任意 shell；
 * 每次执行都有 3s 超时与输出长度上限；并发串行化，避免同时弹多个 su 授权框。</p>
 *
 * <p>{@link #available()} 只读缓存的授权状态，绝不在主线程探测 su。首次真正
 * 需要 root 时才执行命令（可能触发授权弹窗），结果写入缓存供 capabilities 使用。</p>
 */
public final class RootExecutor implements ActionExecutor {

    public static final String NAME = "root";

    /** su 二进制的候选路径；按顺序尝试，避免依赖 App 进程的 PATH。 */
    private static final String[] SU_CANDIDATES = {
            "su", "/system/bin/su", "/system/xbin/su", "/sbin/su"
    };

    /** 缓存的 root 状态：UNKNOWN 时不阻塞探测，capabilities 先显示不可用。 */
    public enum Availability {
        UNKNOWN, AVAILABLE, DENIED
    }

    private final Object lock = new Object();
    private volatile Availability availability = Availability.UNKNOWN;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean available() {
        return availability == Availability.AVAILABLE;
    }

    @Override
    public ActionResult execute(ActionRequest request) throws Exception {
        if (request == null) {
            return ActionResult.failure("", NAME,
                    AgentError.of(AgentError.Code.INVALID_ARGUMENT, "request 不能为空"));
        }
        // su 调用串行化：避免并发授权框竞争，也避免同时跑两条 monkey。
        synchronized (lock) {
            return executeLocked(request);
        }
    }

    private ActionResult executeLocked(ActionRequest request) {
        String action = request.action;
        String command;
        String pkg = "";

        if ("app.open".equals(action)) {
            pkg = request.stringArg("package", "").trim();
            if (pkg.length() == 0) {
                return failure(action, AgentError.Code.NOT_AVAILABLE,
                        "root 兜底只支持 package 启动，不处理 intentUri");
            }
            command = RootCommands.appOpenCommand(pkg);
            if (command == null) {
                return failure(action, AgentError.Code.INVALID_ARGUMENT,
                        "package 不合法（只允许字母、数字、点和下划线）");
            }
        } else if ("media.control".equals(action)) {
            String media = request.stringArg("command", "").trim();
            command = RootCommands.mediaCommand(media);
            if (command == null) {
                return failure(action, AgentError.Code.NOT_AVAILABLE,
                        "root 兜底不支持该媒体命令：" + media);
            }
        } else {
            return failure(action, AgentError.Code.NOT_AVAILABLE,
                    "root 兜底未实现该动作：" + action);
        }

        Outcome outcome = runSu(command);
        if (outcome.notStarted) {
            availability = Availability.DENIED;
            Log.e("xiaoi-agent", "root su unavailable");
            return failure(action, AgentError.Code.PERMISSION_DENIED,
                    "当前 App 没有 root 授权或 su 不可用；请在 KernelSU/Magisk 中给本 App 授权，或改用 Shizuku");
        }

        String output = RootCommands.clip(outcome.output);
        AgentError error = RootCommands.evaluate(outcome.timedOut, outcome.exitCode, output);
        if (error != null) {
            if (error.code == AgentError.Code.PERMISSION_DENIED) {
                availability = Availability.DENIED;
            } else if (!outcome.timedOut) {
                // 命令真的跑起来过：su 本身可用；只是目标不存在/命令失败。
                availability = Availability.AVAILABLE;
            }
            Log.e("xiaoi-agent", "root failed " + error.code.wire);
            return failure(action, error);
        }

        availability = Availability.AVAILABLE;
        Log.i("xiaoi-agent", "root ok " + action);
        return success(action, request, pkg, outcome);
    }

    private ActionResult success(String action, ActionRequest request, String pkg,
                                 Outcome outcome) {
        java.util.Map<String, Object> result = plus.xiaoi.module.Json.obj();
        java.util.List<String> warnings = new java.util.ArrayList<String>();

        if ("app.open".equals(action)) {
            result.put("launched", Boolean.TRUE);
            result.put("package", pkg);
            // root 路径不解析组件，宁可留空也不编造。
            result.put("component", "");
            warnings.add("通过 root monkey 启动；未解析实际组件");
        } else {
            String media = request.stringArg("command", "").trim();
            result.put("command", media);
            warnings.add("root 路径未读取音量；实际效果取决于当前媒体会话");
        }

        return new ActionResult(true, action, NAME, result,
                warnings, null, outcome.elapsedMs, "", null);
    }

    private ActionResult failure(String action, AgentError.Code code, String message) {
        return ActionResult.failure(action, NAME, AgentError.of(code, message));
    }

    private ActionResult failure(String action, AgentError error) {
        return ActionResult.failure(action, NAME, error);
    }

    // ------------------------------------------------------------- su 调用

    private Outcome runSu(String command) {
        for (String su : SU_CANDIDATES) {
            Process process;
            try {
                process = new ProcessBuilder(su, "-c", command)
                        .redirectErrorStream(true)
                        .start();
            } catch (Throwable t) {
                // 只记录异常类型，不记录命令/输出；用于诊断 App 是否能执行 su。
                Log.e("xiaoi-agent", "root start failed " + su + " "
                        + t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : (" " + t.getMessage())));
                continue; // 该路径没有 su，试下一个
            }
            return waitFor(process);
        }
        return Outcome.notStarted();
    }

    private Outcome waitFor(Process process) {
        long started = System.nanoTime();
        boolean finished = false;
        try {
            finished = process.waitFor(RootCommands.TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException t) {
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
        }
        long elapsed = (System.nanoTime() - started) / 1000000L;
        if (!finished) {
            try {
                process.destroyForcibly();
            } catch (Throwable ignored) {
            }
            closeQuietly(process);
            return Outcome.timeout(elapsed);
        }
        int exit = -1;
        try {
            exit = process.exitValue();
        } catch (Throwable ignored) {
        }
        String output = readLimited(process);
        closeQuietly(process);
        return Outcome.exit(exit, output, elapsed);
    }

    /** 读输出：进程已结束，读取到 EOF；最多保留 4KB，超出部分丢弃。 */
    private String readLimited(Process process) {
        InputStream in = null;
        try {
            in = process.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[512];
            int total = 0;
            while (total < 4096) {
                int n = in.read(chunk);
                if (n <= 0) break;
                buf.write(chunk, 0, n);
                total += n;
            }
            return new String(buf.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void closeQuietly(Process process) {
        if (process == null) return;
        try {
            process.getOutputStream().close();
        } catch (Throwable ignored) {
        }
        try {
            process.getErrorStream().close();
        } catch (Throwable ignored) {
        }
    }

    private static final class Outcome {
        final boolean notStarted;
        final boolean timedOut;
        final int exitCode;
        final String output;
        final long elapsedMs;

        private Outcome(boolean notStarted, boolean timedOut, int exitCode,
                        String output, long elapsedMs) {
            this.notStarted = notStarted;
            this.timedOut = timedOut;
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
            this.elapsedMs = elapsedMs;
        }

        static Outcome notStarted() {
            return new Outcome(true, false, -1, "", 0);
        }

        static Outcome timeout(long elapsedMs) {
            return new Outcome(false, true, -1, "", elapsedMs);
        }

        static Outcome exit(int code, String output, long elapsedMs) {
            return new Outcome(false, false, code, output, elapsedMs);
        }
    }
}
