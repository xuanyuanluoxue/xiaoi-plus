package plus.xiaoi.module;

/** 桌面测试入口：javac + java 直接跑，不依赖任何测试框架。 */
public final class TestMain {

    private TestMain() {}

    public static void main(String[] args) {
        System.out.println("==> xiaoi-plus 纯层单元测试");
        int failed = 0;
        failed += JsonTest.run();
        try {
            failed += HttpTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL HttpTest 套件异常: " + t);
        }
        failed += McpTest.run();
        failed += LogsTest.run();
        failed += ModelValidatorTest.run();
        try {
            failed += ControlKeysTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL ControlKeysTest 套件异常: " + t);
        }
        try {
            failed += UidGuardTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL UidGuardTest 套件异常: " + t);
        }
        try {
            failed += BridgeRegistryTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL BridgeRegistryTest 套件异常: " + t);
        }
        try {
            failed += ControlLogRingTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL ControlLogRingTest 套件异常: " + t);
        }
        try {
            failed += ConfigCodecTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL ConfigCodecTest 套件异常: " + t);
        }
        try {
            failed += ConfigStoreTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL ConfigStoreTest 套件异常: " + t);
        }
        try {
            failed += BridgePusherTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL BridgePusherTest 套件异常: " + t);
        }
        try {
            failed += BridgeClientTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL BridgeClientTest 套件异常: " + t);
        }
        try {
            failed += StatusResolverTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL StatusResolverTest 套件异常: " + t);
        }
        try {
            failed += ThemeModeTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL ThemeModeTest 套件异常: " + t);
        }
        try {
            failed += UsageCounterTest.run();
        try {
            failed += LanTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL LanTest 套件异常: " + t);
        }
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL UsageCounterTest 套件异常: " + t);
        }
        System.out.println();
        if (failed == 0) {
            System.out.println("==> 全部通过");
        } else {
            System.out.println("==> 失败断言总数：" + failed);
        }
        System.exit(failed == 0 ? 0 : 1);
    }
}
