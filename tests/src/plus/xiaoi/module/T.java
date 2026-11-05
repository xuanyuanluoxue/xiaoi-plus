package plus.xiaoi.module;

/**
 * 零依赖断言助手。用法：ok()/eq() 记账，report() 输出并返回失败数。
 * 刻意不引 JUnit：与 build.sh 的气质一致，javac + java 直接跑。
 */
public final class T {

    private T() {}

    private static int passed;
    private static int failed;

    public static void ok(String name, boolean cond) {
        if (cond) {
            passed++;
            System.out.println("  ok   " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name);
        }
    }

    public static void eq(String name, Object expected, Object actual) {
        boolean cond = expected == null ? actual == null : expected.equals(actual);
        if (cond) {
            passed++;
            System.out.println("  ok   " + name);
        } else {
            failed++;
            System.out.println("  FAIL " + name);
            System.out.println("        期望: " + expected);
            System.out.println("        实际: " + actual);
        }
    }

    /** 断言表达式抛 Throwable。 */
    public static void throwsIt(String name, Runnable r) {
        try {
            r.run();
            failed++;
            System.out.println("  FAIL " + name + "（未抛异常）");
        } catch (Throwable t) {
            passed++;
            System.out.println("  ok   " + name);
        }
    }

    /** 输出小节汇总并清零；返回本小节失败数。 */
    public static int report(String suite) {
        System.out.println(suite + ": " + passed + " 通过 / " + failed + " 失败");
        int f = failed;
        passed = 0;
        failed = 0;
        return f;
    }
}
