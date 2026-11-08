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
            failed += BridgePolicyTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL BridgePolicyTest 套件异常: " + t);
        }
        try {
            failed += LeaderElectionTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL LeaderElectionTest 套件异常: " + t);
        }
        try {
            failed += RateLimiterTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL RateLimiterTest 套件异常: " + t);
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
            failed += StateModelTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL StateModelTest 套件异常: " + t);
        }
        try {
            failed += BridgeStatusTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL BridgeStatusTest 套件异常: " + t);
        }
        try {
            failed += ThemeModeTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL ThemeModeTest 套件异常: " + t);
        }
        try {
            failed += UsageCounterTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL UsageCounterTest 套件异常: " + t);
        }
        try {
            failed += LanTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL LanTest 套件异常: " + t);
        }
        try {
            failed += ErrorCatalogTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL ErrorCatalogTest 套件异常: " + t);
        }
        try {
            failed += AlarmTimesTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL AlarmTimesTest 套件异常: " + t);
        }
        try {
            failed += AgentActionRegistryTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL AgentActionRegistryTest 套件异常: " + t);
        }
        try {
            failed += AgentActionExecutorsTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL AgentActionExecutorsTest 套件异常: " + t);
        }
        try {
            failed += AgentMcpTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL AgentMcpTest 套件异常: " + t);
        }
        try {
            failed += AgentSecurityTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL AgentSecurityTest 套件异常: " + t);
        }
        try {
            failed += AgentNotifyAskTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL AgentNotifyAskTest 套件异常: " + t);
        }
        try {
            failed += AgentLanPolicyTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL AgentLanPolicyTest 套件异常: " + t);
        }
        try {
            failed += AgentIdempotencyPersistenceTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL AgentIdempotencyPersistenceTest 套件异常: " + t);
        }
        try {
            failed += IssueResolverTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL IssueResolverTest 套件异常: " + t);
        }
        try {
            failed += CapabilityRegistryTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL CapabilityRegistryTest 套件异常: " + t);
        }
        try {
            failed += ActivityFeedTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL ActivityFeedTest 套件异常: " + t);
        }
        try {
            failed += AgentRuntimeStatusTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL AgentRuntimeStatusTest 套件异常: " + t);
        }
        try {
            failed += IssueInputFactoryTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL IssueInputFactoryTest 套件异常: " + t);
        }
        try {
            failed += NavStackTest.run();
        } catch (Throwable t) {
            failed++;
            System.out.println("  FAIL NavStackTest 套件异常: " + t);
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
