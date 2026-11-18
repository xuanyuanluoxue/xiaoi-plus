package plus.xiaoi.module.application.runtime;

/** 模型探测端口（WP11）：UI 不认识 infra 的 ModelValidator。 */
public interface ModelProbe {

    final class Result {
        public final boolean ok;
        /** ok / auth / model / endpoint / timeout / http / empty */
        public final String code;
        public final String message;

        public Result(boolean ok, String code, String message) {
            this.ok = ok;
            this.code = code == null ? "" : code;
            this.message = message == null ? "" : message;
        }
    }

    Result probe(String baseUrl, String apiKey, String model);
}
