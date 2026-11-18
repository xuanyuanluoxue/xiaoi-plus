package plus.xiaoi.module.application.runtime;

/**
 * 分享文件的 authority 常量（WP11）。
 *
 * <p>必须与 {@code AndroidManifest.xml} 里 {@code ShareProvider} 的
 * {@code android:authorities} 一致；一致性门禁会同时检查两处。</p>
 */
public final class FileShare {

    public static final String AUTHORITY = "plus.xiaoi.module.files";

    private FileShare() {}
}
