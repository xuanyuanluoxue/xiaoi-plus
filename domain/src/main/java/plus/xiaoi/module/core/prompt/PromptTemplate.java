package plus.xiaoi.module.core.prompt;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统提示词模板的变量替换（纯 JVM，无 I/O、无 Android）。
 *
 * <p>只支持四个占位符，且只在注入那一刻替换一次：</p>
 * <ul>
 *   <li>{@code {{date}}}  本地日期 {@code yyyy-MM-dd}</li>
 *   <li>{@code {{time}}}  本地时间 {@code HH:mm}</li>
 *   <li>{@code {{model}}} 当前生效的模型 id</li>
 *   <li>{@code {{device}}} 设备型号（来自 {@code Build.MODEL}，由调用方传入）</li>
 * </ul>
 *
 * <p><b>未知占位符原样保留</b>：用户写错变量名时应该能在预览里一眼看出来，
 * 而不是被静默吃掉。已知占位符在变量为空时替换为空串（例如未配置模型）。</p>
 */
public final class PromptTemplate {

    /** 日期占位符。 */
    public static final String DATE = "{{date}}";
    /** 时间占位符。 */
    public static final String TIME = "{{time}}";
    /** 模型占位符。 */
    public static final String MODEL = "{{model}}";
    /** 设备占位符。 */
    public static final String DEVICE = "{{device}}";

    private PromptTemplate() {}

    /** 变量快照；任一字段为 null 时按空串处理。 */
    public static final class Vars {
        public final String date;
        public final String time;
        public final String model;
        public final String device;

        public Vars(String date, String time, String model, String device) {
            this.date = date == null ? "" : date;
            this.time = time == null ? "" : time;
            this.model = model == null ? "" : model;
            this.device = device == null ? "" : device;
        }

        /** 全空变量（单测/预览用）。 */
        public static Vars empty() {
            return new Vars("", "", "", "");
        }
    }

    /**
     * 渲染模板：{@code template} 为 null 或空时返回空串。
     *
     * <p>替换顺序固定，用一次性扫描实现（先拼好的占位符不会被后一次替换再命中）：
     * 例如 {@code {{model}}} 的值里恰好包含 {@code {{date}}} 也不会被再次展开。</p>
     */
    public static String render(String template, Vars vars) {
        if (template == null || template.length() == 0) return "";
        Vars v = vars == null ? Vars.empty() : vars;
        Map<String, String> values = new LinkedHashMap<String, String>();
        values.put(DATE, v.date);
        values.put(TIME, v.time);
        values.put(MODEL, v.model);
        values.put(DEVICE, v.device);

        StringBuilder out = new StringBuilder(template.length() + 32);
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c != '{') {
                out.append(c);
                i++;
                continue;
            }
            String hit = matchAt(template, i, values);
            if (hit == null) {
                out.append(c);
                i++;
                continue;
            }
            out.append(values.get(hit));
            i += hit.length();
        }
        return out.toString();
    }

    /** 在 {@code pos} 处匹配已知占位符；返回占位符本身，未命中返回 null。 */
    private static String matchAt(String text, int pos, Map<String, String> values) {
        for (String key : values.keySet()) {
            if (text.startsWith(key, pos)) return key;
        }
        return null;
    }
}
