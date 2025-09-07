#### 单体服务嵌套检测
```java
import com.alibaba.ttl.TransmittableThreadLocal;
import com.alibaba.ttl.threadpool.TtlExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 跨接口线程池嵌套调用监控装饰器 (修正版)
 * 使用分布式追踪traceId关联跨接口的线程池使用情况
 */
public class MonitoredThreadPoolTaskExecutor extends ThreadPoolTaskExecutor {
    private static final Logger logger = LoggerFactory.getLogger(MonitoredThreadPoolTaskExecutor.class);

    // 全局注册表，存储所有线程池实例
    private static final Map<String, MonitoredThreadPoolTaskExecutor> threadPoolRegistry =
            new ConcurrentHashMap<>();

    // 使用TransmittableThreadLocal存储调用链上下文
    // 现在使用traceId作为key，存储每个trace的调用链信息
    private static final TransmittableThreadLocal<Map<String, CallChainContext>> traceCallChainContext =
            new TransmittableThreadLocal<Map<String, CallChainContext>>() {
                @Override
                protected Map<String, CallChainContext> initialValue() {
                    return new HashMap<>();
                }
            };

    // 线程池名称，用于标识不同的线程池
    private final String poolName;

    // 被TTL包装后的Executor，确保提交的任务能被正确装饰
    private final ExecutorService ttlWrappedExecutorService;

    // 记录线程池的使用统计信息
    private final ThreadPoolUsageStats usageStats = new ThreadPoolUsageStats();

    public MonitoredThreadPoolTaskExecutor(String poolName) {
        this.poolName = poolName;
        // 注册线程池实例
        threadPoolRegistry.put(poolName, this);
        // 初始化时先调用父类方法设置基本参数
        super.initialize();
        // 使用TTL装饰真正的线程池Executor
        this.ttlWrappedExecutorService = TtlExecutors.getTtlExecutorService(super.getThreadPoolExecutor());
    }

    @Override
    public void execute(Runnable task) {
        // 记录任务提交信息
        usageStats.recordTaskSubmission();
        // 提交给被TTL包装后的Executor执行
        MonitoredRunnable monitoredTask = new MonitoredRunnable(task, poolName);
        ttlWrappedExecutorService.execute(monitoredTask);
    }

    @Override
    public Future<?> submit(Runnable task) {
        usageStats.recordTaskSubmission();
        MonitoredRunnable monitoredTask = new MonitoredRunnable(task, poolName);
        return ttlWrappedExecutorService.submit(monitoredTask);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        usageStats.recordTaskSubmission();
        MonitoredCallable<T> monitoredTask = new MonitoredCallable<>(task, poolName);
        return ttlWrappedExecutorService.submit(monitoredTask);
    }

    @Override
    public void shutdown() {
        super.shutdown();
        ttlWrappedExecutorService.shutdown();
        // 从注册表中移除
        threadPoolRegistry.remove(poolName);
    }

    @Override
    public ThreadPoolExecutor getThreadPoolExecutor() throws IllegalStateException {
        return super.getThreadPoolExecutor();
    }

    /**
     * 获取线程池使用统计信息
     */
    public ThreadPoolUsageStats getUsageStats() {
        return usageStats;
    }

    /**
     * 获取所有已注册的线程池实例
     */
    public static Map<String, MonitoredThreadPoolTaskExecutor> getThreadPoolRegistry() {
        return Collections.unmodifiableMap(threadPoolRegistry);
    }

    /**
     * 调用链上下文类，记录完整的嵌套调用信息
     */
    private static class CallChainContext {
        // 存储每个线程池的调用深度
        private final Map<String, Integer> callDepthMap = new HashMap<>();

        // 存储每个线程池的调用堆栈链
        private final Map<String, List<CallStackRecord>> callStackChains = new HashMap<>();

        // 添加调用记录
        public void addCall(String executorName, StackTraceElement[] stackTrace, String traceId) {
            // 更新调用深度
            int depth = callDepthMap.getOrDefault(executorName, 0);
            callDepthMap.put(executorName, depth + 1);

            // 添加调用堆栈到链中
            List<CallStackRecord> stackChain = callStackChains.computeIfAbsent(
                    executorName, k -> new ArrayList<>());
            stackChain.add(new CallStackRecord(stackTrace, traceId, System.currentTimeMillis()));
        }

        // 移除调用记录
        public void removeCall(String executorName) {
            Integer depth = callDepthMap.get(executorName);
            if (depth != null) {
                if (depth <= 1) {
                    callDepthMap.remove(executorName);
                    callStackChains.remove(executorName);
                } else {
                    callDepthMap.put(executorName, depth - 1);

                    // 移除最后一个堆栈记录
                    List<CallStackRecord> stackChain = callStackChains.get(executorName);
                    if (stackChain != null && !stackChain.isEmpty()) {
                        stackChain.remove(stackChain.size() - 1);
                    }
                }
            }
        }

        // 获取调用深度
        public int getDepth(String executorName) {
            return callDepthMap.getOrDefault(executorName, 0);
        }

        // 获取调用链中的所有堆栈记录
        public List<CallStackRecord> getCallStackChain(String executorName) {
            return callStackChains.getOrDefault(executorName, Collections.emptyList());
        }

        // 检查跨接口嵌套调用
        public boolean checkCrossInterfaceNesting(String executorName, String currentTraceId) {
            List<CallStackRecord> stackChain = getCallStackChain(executorName);
            if (stackChain.isEmpty()) return false;

            // 检查是否有不同traceId的调用记录
            for (CallStackRecord record : stackChain) {
                if (!record.traceId.equals(currentTraceId)) {
                    return true;
                }
            }

            return false;
        }

        // 获取跨接口嵌套调用的详细信息
        public String getCrossInterfaceNestingDetails(String executorName, String currentTraceId) {
            List<CallStackRecord> stackChain = getCallStackChain(executorName);
            if (stackChain.isEmpty()) return "";

            StringBuilder details = new StringBuilder();
            details.append("检测到跨接口线程池嵌套调用:\n");
            details.append("线程池: ").append(executorName).append("\n");
            details.append("当前traceId: ").append(currentTraceId).append("\n");
            details.append("跨接口调用链详情:\n");

            for (int i = 0; i < stackChain.size(); i++) {
                CallStackRecord record = stackChain.get(i);
                if (!record.traceId.equals(currentTraceId)) {
                    details.append("第").append(i + 1).append("层调用 (traceId: ").append(record.traceId).append("):\n");
                    details.append(formatStackTrace(record.stackTrace));
                    details.append("调用时间: ").append(new Date(record.timestamp)).append("\n\n");
                }
            }

            return details.toString();
        }
    }

    /**
     * 调用堆栈记录类
     */
    private static class CallStackRecord {
        final StackTraceElement[] stackTrace;
        final String traceId;
        final long timestamp;

        CallStackRecord(StackTraceElement[] stackTrace, String traceId, long timestamp) {
            this.stackTrace = stackTrace;
            this.traceId = traceId;
            this.timestamp = timestamp;
        }
    }

    /**
     * 线程池使用统计类
     */
    public static class ThreadPoolUsageStats {
        private final AtomicLong totalTasksSubmitted = new AtomicLong(0);
        private final AtomicLong nestedCallsDetected = new AtomicLong(0);
        private final AtomicLong crossInterfaceNestedCalls = new AtomicLong(0);

        public void recordTaskSubmission() {
            totalTasksSubmitted.incrementAndGet();
        }

        public void recordNestedCall() {
            nestedCallsDetected.incrementAndGet();
        }

        public void recordCrossInterfaceNestedCall() {
            crossInterfaceNestedCalls.incrementAndGet();
        }

        public long getTotalTasksSubmitted() {
            return totalTasksSubmitted.get();
        }

        public long getNestedCallsDetected() {
            return nestedCallsDetected.get();
        }

        public long getCrossInterfaceNestedCalls() {
            return crossInterfaceNestedCalls.get();
        }

        public double getNestedCallPercentage() {
            if (totalTasksSubmitted.get() == 0) return 0;
            return (double) nestedCallsDetected.get() / totalTasksSubmitted.get() * 100;
        }
    }

    /**
     * 包装Runnable任务，添加监控逻辑
     */
    private class MonitoredRunnable implements Runnable {
        private final Runnable originalTask;
        private final String executorName;
        private final StackTraceElement[] submissionStack;
        private final String submissionTraceId;

        public MonitoredRunnable(Runnable originalTask, String executorName) {
            this.originalTask = originalTask;
            this.executorName = executorName;
            // 捕获提交任务时的调用栈
            this.submissionStack = Thread.currentThread().getStackTrace();
            // 捕获提交任务时的traceId
            this.submissionTraceId = TraceUtil.getTraceId();
        }

        @Override
        public void run() {
            // 获取当前执行时的traceId
            String currentTraceId = TraceUtil.getTraceId();

            // 获取当前trace的调用链上下文
            Map<String, CallChainContext> traceContexts = traceCallChainContext.get();
            CallChainContext context = traceContexts.computeIfAbsent(
                    currentTraceId, k -> new CallChainContext());

            // 获取当前线程池在当前trace中的调用深度
            int currentDepth = context.getDepth(executorName);

            // 添加当前调用记录
            context.addCall(executorName, submissionStack, submissionTraceId);

            try {
                // 检查同一线程池的嵌套调用
                if (currentDepth > 0) {
                    usageStats.recordNestedCall();

                    // 获取完整的调用链
                    List<CallStackRecord> callStackChain = context.getCallStackChain(executorName);

                    StringBuilder chainInfo = new StringBuilder();
                    chainInfo.append("检测到线程池嵌套调用:\n");
                    chainInfo.append("线程池: ").append(executorName).append("\n");
                    chainInfo.append("嵌套深度: ").append(currentDepth + 1).append("\n");
                    chainInfo.append("当前traceId: ").append(currentTraceId).append("\n");

                    // 检查是否是跨接口嵌套调用
                    if (!submissionTraceId.equals(currentTraceId)) {
                        usageStats.recordCrossInterfaceNestedCall();
                        chainInfo.append("跨接口调用检测:\n");
                        chainInfo.append("提交traceId: ").append(submissionTraceId).append("\n");
                        chainInfo.append("执行traceId: ").append(currentTraceId).append("\n");
                        chainInfo.append("提交堆栈:\n");
                        chainInfo.append(formatStackTrace(submissionStack));
                        chainInfo.append("\n");
                    } else {
                        chainInfo.append("完整调用链:\n");

                        // 添加调用链中每个层级的堆栈信息
                        for (int i = 0; i < callStackChain.size(); i++) {
                            CallStackRecord record = callStackChain.get(i);
                            chainInfo.append("第").append(i + 1).append("层调用 (traceId: ").append(record.traceId).append("):\n");
                            chainInfo.append(formatStackTrace(record.stackTrace));
                            chainInfo.append("\n");
                        }
                    }

                    logger.warn(chainInfo.toString());
                }

                // 执行原始任务
                originalTask.run();
            } finally {
                // 移除当前调用记录
                context.removeCall(executorName);

                // 如果当前trace的调用链为空，则移除整个trace的上下文
                boolean isEmpty = true;
                for (String pool : context.callDepthMap.keySet()) {
                    if (context.getDepth(pool) > 0) {
                        isEmpty = false;
                        break;
                    }
                }

                if (isEmpty) {
                    traceContexts.remove(currentTraceId);
                }
            }
        }
    }

    /**
     * 包装Callable任务，添加监控逻辑
     */
    private class MonitoredCallable<T> implements Callable<T> {
        private final Callable<T> originalTask;
        private final String executorName;
        private final StackTraceElement[] submissionStack;
        private final String submissionTraceId;

        public MonitoredCallable(Callable<T> originalTask, String executorName) {
            this.originalTask = originalTask;
            this.executorName = executorName;
            // 捕获提交任务时的调用栈
            this.submissionStack = Thread.currentThread().getStackTrace();
            // 捕获提交任务时的traceId
            this.submissionTraceId = TraceUtil.getTraceId();
        }

        @Override
        public T call() throws Exception {
            // 获取当前执行时的traceId
            String currentTraceId = TraceUtil.getTraceId();

            // 获取当前trace的调用链上下文
            Map<String, CallChainContext> traceContexts = traceCallChainContext.get();
            CallChainContext context = traceContexts.computeIfAbsent(
                    currentTraceId, k -> new CallChainContext());

            // 获取当前线程池在当前trace中的调用深度
            int currentDepth = context.getDepth(executorName);

            // 添加当前调用记录
            context.addCall(executorName, submissionStack, submissionTraceId);

            try {
                // 检查同一线程池的嵌套调用
                if (currentDepth > 0) {
                    usageStats.recordNestedCall();

                    // 获取完整的调用链
                    List<CallStackRecord> callStackChain = context.getCallStackChain(executorName);

                    StringBuilder chainInfo = new StringBuilder();
                    chainInfo.append("检测到线程池嵌套调用:\n");
                    chainInfo.append("线程池: ").append(executorName).append("\n");
                    chainInfo.append("嵌套深度: ").append(currentDepth + 1).append("\n");
                    chainInfo.append("当前traceId: ").append(currentTraceId).append("\n");

                    // 检查是否是跨接口嵌套调用
                    if (!submissionTraceId.equals(currentTraceId)) {
                        usageStats.recordCrossInterfaceNestedCall();
                        chainInfo.append("跨接口调用检测:\n");
                        chainInfo.append("提交traceId: ").append(submissionTraceId).append("\n");
                        chainInfo.append("执行traceId: ").append(currentTraceId).append("\n");
                        chainInfo.append("提交堆栈:\n");
                        chainInfo.append(formatStackTrace(submissionStack));
                        chainInfo.append("\n");
                    } else {
                        chainInfo.append("完整调用链:\n");

                        // 添加调用链中每个层级的堆栈信息
                        for (int i = 0; i < callStackChain.size(); i++) {
                            CallStackRecord record = callStackChain.get(i);
                            chainInfo.append("第").append(i + 1).append("层调用 (traceId: ").append(record.traceId).append("):\n");
                            chainInfo.append(formatStackTrace(record.stackTrace));
                            chainInfo.append("\n");
                        }
                    }

                    logger.warn(chainInfo.toString());
                }

                return originalTask.call();
            } finally {
                // 移除当前调用记录
                context.removeCall(executorName);

                // 如果当前trace的调用链为空，则移除整个trace的上下文
                boolean isEmpty = true;
                for (String pool : context.callDepthMap.keySet()) {
                    if (context.getDepth(pool) > 0) {
                        isEmpty = false;
                        break;
                    }
                }

                if (isEmpty) {
                    traceContexts.remove(currentTraceId);
                }
            }
        }
    }

    /**
     * 格式化堆栈跟踪信息
     */
    private static String formatStackTrace(StackTraceElement[] stackTrace) {
        StringBuilder sb = new StringBuilder();
        // 跳过前3个元素，通常是线程池内部调用
        for (int i = 3; i < stackTrace.length && i < 15; i++) {
            StackTraceElement element = stackTrace[i];
            // 过滤掉一些不重要的堆栈元素
            if (element.getClassName().startsWith("java.util.concurrent") ||
                    element.getClassName().startsWith("sun.reflect") ||
                    element.getClassName().startsWith("java.lang.reflect") ||
                    element.getClassName().startsWith("org.springframework")) {
                continue;
            }
            sb.append("    at ").append(element.toString()).append("\n");
        }
        return sb.toString();
    }
}
```

#### 集群嵌套检测
```java
import com.alibaba.ttl.TransmittableThreadLocal;
import com.alibaba.ttl.threadpool.TtlExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 集群环境下线程池嵌套调用监控装饰器
 * 使用Redis存储跨实例的调用链信息
 */
public class ClusterAwareThreadPoolTaskExecutor extends ThreadPoolTaskExecutor {
    private static final Logger logger = LoggerFactory.getLogger(ClusterAwareThreadPoolTaskExecutor.class);

    // Redis操作模板
    private static RedisTemplate<String, Object> redisTemplate;

    // 实例标识
    private static final String INSTANCE_ID = UUID.randomUUID().toString().substring(0, 8);

    // 使用TransmittableThreadLocal存储调用链上下文
    private static final TransmittableThreadLocal<Map<String, CallChainContext>> traceCallChainContext = new TransmittableThreadLocal<Map<String, CallChainContext>>() {
        @Override
        protected Map<String, CallChainContext> initialValue() {
            return new HashMap<>();
        }
    };

    // 线程池名称，用于标识不同的线程池
    private final String poolName;

    // 被TTL包装后的Executor，确保提交的任务能被正确装饰
    private final ExecutorService ttlWrappedExecutorService;

    // 记录线程池的使用统计信息
    private final ThreadPoolUsageStats usageStats = new ThreadPoolUsageStats();

    // 设置Redis模板的静态方法
    public static void setRedisTemplate(RedisTemplate<String, Object> redisTemplate) {
        ClusterAwareThreadPoolTaskExecutor.redisTemplate = redisTemplate;
    }

    public ClusterAwareThreadPoolTaskExecutor(String poolName) {
        this.poolName = poolName;
        // 初始化时先调用父类方法设置基本参数
        super.initialize();
        // 使用TTL装饰真正的线程池Executor
        this.ttlWrappedExecutorService = TtlExecutors.getTtlExecutorService(super.getThreadPoolExecutor());
    }

    @Override
    public void execute(Runnable task) {
        // 记录任务提交信息
        usageStats.recordTaskSubmission();
        // 提交给被TTL包装后的Executor执行
        MonitoredRunnable monitoredTask = new MonitoredRunnable(task, poolName);
        ttlWrappedExecutorService.execute(monitoredTask);
    }

    @Override
    public Future<?> submit(Runnable task) {
        usageStats.recordTaskSubmission();
        MonitoredRunnable monitoredTask = new MonitoredRunnable(task, poolName);
        return ttlWrappedExecutorService.submit(monitoredTask);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        usageStats.recordTaskSubmission();
        MonitoredCallable<T> monitoredTask = new MonitoredCallable<>(task, poolName);
        return ttlWrappedExecutorService.submit(monitoredTask);
    }

    @Override
    public void shutdown() {
        super.shutdown();
        ttlWrappedExecutorService.shutdown();
    }

    @Override
    public ThreadPoolExecutor getThreadPoolExecutor() throws IllegalStateException {
        return super.getThreadPoolExecutor();
    }

    /**
     * 获取线程池使用统计信息
     */
    public ThreadPoolUsageStats getUsageStats() {
        return usageStats;
    }

    /**
     * 调用链上下文类，记录完整的嵌套调用信息
     */
    private static class CallChainContext {
        // 存储每个线程池的调用深度
        private final Map<String, Integer> callDepthMap = new HashMap<>();

        // 存储每个线程池的调用堆栈链
        private final Map<String, List<CallStackRecord>> callStackChains = new HashMap<>();

        // 添加调用记录
        public void addCall(String executorName, StackTraceElement[] stackTrace, String traceId) {
            // 更新调用深度
            int depth = callDepthMap.getOrDefault(executorName, 0);
            callDepthMap.put(executorName, depth + 1);

            // 添加调用堆栈到链中
            List<CallStackRecord> stackChain = callStackChains.computeIfAbsent(executorName, k -> new ArrayList<>());
            stackChain.add(new CallStackRecord(stackTrace, traceId, System.currentTimeMillis()));
        }

        // 移除调用记录
        public void removeCall(String executorName) {
            Integer depth = callDepthMap.get(executorName);
            if (depth != null) {
                if (depth <= 1) {
                    callDepthMap.remove(executorName);
                    callStackChains.remove(executorName);
                } else {
                    callDepthMap.put(executorName, depth - 1);

                    // 移除最后一个堆栈记录
                    List<CallStackRecord> stackChain = callStackChains.get(executorName);
                    if (stackChain != null && !stackChain.isEmpty()) {
                        stackChain.remove(stackChain.size() - 1);
                    }
                }
            }
        }

        // 获取调用深度
        public int getDepth(String executorName) {
            return callDepthMap.getOrDefault(executorName, 0);
        }

        // 获取调用链中的所有堆栈记录
        public List<CallStackRecord> getCallStackChain(String executorName) {
            return callStackChains.getOrDefault(executorName, Collections.emptyList());
        }
    }

    /**
     * 调用堆栈记录类
     */
    private static class CallStackRecord {
        final StackTraceElement[] stackTrace;
        final String traceId;
        final long timestamp;

        CallStackRecord(StackTraceElement[] stackTrace, String traceId, long timestamp) {
            this.stackTrace = stackTrace;
            this.traceId = traceId;
            this.timestamp = timestamp;
        }
    }

    /**
     * 线程池使用统计类
     */
    public static class ThreadPoolUsageStats {
        private final AtomicLong totalTasksSubmitted = new AtomicLong(0);
        private final AtomicLong nestedCallsDetected = new AtomicLong(0);
        private final AtomicLong crossInstanceNestedCalls = new AtomicLong(0);

        public void recordTaskSubmission() {
            totalTasksSubmitted.incrementAndGet();
        }

        public void recordNestedCall() {
            nestedCallsDetected.incrementAndGet();
        }

        public void recordCrossInstanceNestedCall() {
            crossInstanceNestedCalls.incrementAndGet();
        }

        public long getTotalTasksSubmitted() {
            return totalTasksSubmitted.get();
        }

        public long getNestedCallsDetected() {
            return nestedCallsDetected.get();
        }

        public long getCrossInstanceNestedCalls() {
            return crossInstanceNestedCalls.get();
        }

        public double getNestedCallPercentage() {
            if (totalTasksSubmitted.get() == 0) return 0;
            return (double) nestedCallsDetected.get() / totalTasksSubmitted.get() * 100;
        }
    }

    /**
     * 检查跨实例线程池嵌套调用
     */
    private boolean checkCrossInstanceNesting(String traceId, String executorName, String submissionTraceId) {
        if (redisTemplate == null) {
            logger.warn("RedisTemplate未配置，无法检测跨实例线程池嵌套调用");
            return false;
        }

        try {
            // 生成Redis键名
            String redisKey = "threadpool:nesting:" + traceId + ":" + executorName;

            // 检查Redis中是否已有该线程池的使用记录
            Object existingRecord = redisTemplate.opsForValue().get(redisKey);
            if (existingRecord != null) {
                // 解析现有记录
                String[] parts = existingRecord.toString().split("\\|");
                String instanceId = parts[0];
                long timestamp = Long.parseLong(parts[1]);

                // 如果记录来自不同实例，则检测到跨实例嵌套
                if (!instanceId.equals(INSTANCE_ID)) {
                    return true;
                }
            }

            // 更新Redis记录
            String recordValue = INSTANCE_ID + "|" + System.currentTimeMillis();
            redisTemplate.opsForValue().set(redisKey, recordValue, 5, TimeUnit.MINUTES);

            return false;
        } catch (Exception e) {
            logger.error("检查跨实例线程池嵌套调用时发生错误", e);
            return false;
        }
    }

    /**
     * 获取跨实例嵌套调用的详细信息
     */
    private String getCrossInstanceNestingDetails(String traceId, String executorName) {
        if (redisTemplate == null) {
            return "RedisTemplate未配置，无法获取跨实例调用详情";
        }

        try {
            // 生成Redis键名
            String redisKey = "threadpool:nesting:" + traceId + ":" + executorName;

            // 获取Redis中的记录
            Object existingRecord = redisTemplate.opsForValue().get(redisKey);
            if (existingRecord != null) {
                // 解析现有记录
                String[] parts = existingRecord.toString().split("\\|");
                String instanceId = parts[0];
                long timestamp = Long.parseLong(parts[1]);

                return String.format("检测到跨实例线程池嵌套调用:\n" + "线程池: %s\n" + "当前实例: %s\n" + "调用实例: %s\n" + "调用时间: %s\n", executorName, INSTANCE_ID, instanceId, new Date(timestamp));
            }

            return "未找到跨实例调用详情";
        } catch (Exception e) {
            logger.error("获取跨实例线程池嵌套调用详情时发生错误", e);
            return "获取跨实例调用详情时发生错误: " + e.getMessage();
        }
    }

    /**
     * 包装Runnable任务，添加监控逻辑
     */
    private class MonitoredRunnable implements Runnable {
        private final Runnable originalTask;
        private final String executorName;
        private final StackTraceElement[] submissionStack;
        private final String submissionTraceId;

        public MonitoredRunnable(Runnable originalTask, String executorName) {
            this.originalTask = originalTask;
            this.executorName = executorName;
            // 捕获提交任务时的调用栈
            this.submissionStack = Thread.currentThread().getStackTrace();
            // 捕获提交任务时的traceId
            this.submissionTraceId = TraceUtil.getTraceId();
        }

        @Override
        public void run() {
            // 获取当前执行时的traceId
            String currentTraceId = TraceUtil.getTraceId();

            // 获取当前trace的调用链上下文
            Map<String, CallChainContext> traceContexts = traceCallChainContext.get();
            CallChainContext context = traceContexts.computeIfAbsent(currentTraceId, k -> new CallChainContext());

            // 获取当前线程池在当前trace中的调用深度
            int currentDepth = context.getDepth(executorName);

            // 添加当前调用记录
            context.addCall(executorName, submissionStack, submissionTraceId);

            try {
                // 检查同一线程池的嵌套调用
                if (currentDepth > 0) {
                    usageStats.recordNestedCall();

                    // 获取完整的调用链
                    List<CallStackRecord> callStackChain = context.getCallStackChain(executorName);

                    StringBuilder chainInfo = new StringBuilder();
                    chainInfo.append("检测到线程池嵌套调用:\n");
                    chainInfo.append("线程池: ").append(executorName).append("\n");
                    chainInfo.append("嵌套深度: ").append(currentDepth + 1).append("\n");
                    chainInfo.append("当前traceId: ").append(currentTraceId).append("\n");

                    // 检查是否是跨实例嵌套调用
                    boolean isCrossInstance = checkCrossInstanceNesting(currentTraceId, executorName, submissionTraceId);
                    if (isCrossInstance) {
                        usageStats.recordCrossInstanceNestedCall();
                        chainInfo.append(getCrossInstanceNestingDetails(currentTraceId, executorName));
                        chainInfo.append("\n");
                    }

                    // 检查是否是跨接口嵌套调用
                    if (!submissionTraceId.equals(currentTraceId)) {
                        chainInfo.append("跨接口调用检测:\n");
                        chainInfo.append("提交traceId: ").append(submissionTraceId).append("\n");
                        chainInfo.append("执行traceId: ").append(currentTraceId).append("\n");
                        chainInfo.append("提交堆栈:\n");
                        chainInfo.append(formatStackTrace(submissionStack));
                        chainInfo.append("\n");
                    } else {
                        chainInfo.append("完整调用链:\n");

                        // 添加调用链中每个层级的堆栈信息
                        for (int i = 0; i < callStackChain.size(); i++) {
                            CallStackRecord record = callStackChain.get(i);
                            chainInfo.append("第").append(i + 1).append("层调用 (traceId: ").append(record.traceId).append("):\n");
                            chainInfo.append(formatStackTrace(record.stackTrace));
                            chainInfo.append("\n");
                        }
                    }

                    logger.warn(chainInfo.toString());
                }

                // 执行原始任务
                originalTask.run();
            } finally {
                // 移除当前调用记录
                context.removeCall(executorName);

                // 如果当前trace的调用链为空，则移除整个trace的上下文
                boolean isEmpty = true;
                for (String pool : context.callDepthMap.keySet()) {
                    if (context.getDepth(pool) > 0) {
                        isEmpty = false;
                        break;
                    }
                }
                if (isEmpty) {
                    traceContexts.remove(currentTraceId);
                }
            }
        }
    }

    /**
     * 包装Callable任务，添加监控逻辑
     */
    private class MonitoredCallable<T> implements Callable<T> {
        private final Callable<T> originalTask;
        private final String executorName;
        private final StackTraceElement[] submissionStack;
        private final String submissionTraceId;

        public MonitoredCallable(Callable<T> originalTask, String executorName) {
            this.originalTask = originalTask;
            this.executorName = executorName;
            // 捕获提交任务时的调用栈
            this.submissionStack = Thread.currentThread().getStackTrace();
            // 捕获提交任务时的traceId
            this.submissionTraceId = TraceUtil.getTraceId();
        }

        @Override
        public T call() throws Exception {
            // 获取当前执行时的traceId
            String currentTraceId = TraceUtil.getTraceId();

            // 获取当前trace的调用链上下文
            Map<String, CallChainContext> traceContexts = traceCallChainContext.get();
            CallChainContext context = traceContexts.computeIfAbsent(currentTraceId, k -> new CallChainContext());

            // 获取当前线程池在当前trace中的调用深度
            int currentDepth = context.getDepth(executorName);

            // 添加当前调用记录
            context.addCall(executorName, submissionStack, submissionTraceId);

            try {
                // 检查同一线程池的嵌套调用
                if (currentDepth > 0) {
                    usageStats.recordNestedCall();
                    // 获取完整的调用链
                    List<CallStackRecord> callStackChain = context.getCallStackChain(executorName);
                    StringBuilder chainInfo = new StringBuilder();
                    chainInfo.append("检测到线程池嵌套调用:\n");
                    chainInfo.append("线程池: ").append(executorName).append("\n");
                    chainInfo.append("嵌套深度: ").append(currentDepth + 1).append("\n");
                    chainInfo.append("当前traceId: ").append(currentTraceId).append("\n");

                    // 检查是否是跨实例嵌套调用
                    boolean isCrossInstance = checkCrossInstanceNesting(currentTraceId, executorName, submissionTraceId);
                    if (isCrossInstance) {
                        usageStats.recordCrossInstanceNestedCall();
                        chainInfo.append(getCrossInstanceNestingDetails(currentTraceId, executorName));
                        chainInfo.append("\n");
                    }

                    // 检查是否是跨接口嵌套调用
                    if (!submissionTraceId.equals(currentTraceId)) {
                        chainInfo.append("跨接口调用检测:\n");
                        chainInfo.append("提交traceId: ").append(submissionTraceId).append("\n");
                        chainInfo.append("执行traceId: ").append(currentTraceId).append("\n");
                        chainInfo.append("提交堆栈:\n");
                        chainInfo.append(formatStackTrace(submissionStack));
                        chainInfo.append("\n");
                    } else {
                        chainInfo.append("完整调用链:\n");

                        // 添加调用链中每个层级的堆栈信息
                        for (int i = 0; i < callStackChain.size(); i++) {
                            CallStackRecord record = callStackChain.get(i);
                            chainInfo.append("第").append(i + 1).append("层调用 (traceId: ").append(record.traceId).append("):\n");
                            chainInfo.append(formatStackTrace(record.stackTrace));
                            chainInfo.append("\n");
                        }
                    }

                    logger.warn(chainInfo.toString());
                }

                return originalTask.call();
            } finally {
                // 移除当前调用记录
                context.removeCall(executorName);

                // 如果当前trace的调用链为空，则移除整个trace的上下文
                boolean isEmpty = true;
                for (String pool : context.callDepthMap.keySet()) {
                    if (context.getDepth(pool) > 0) {
                        isEmpty = false;
                        break;
                    }
                }

                if (isEmpty) {
                    traceContexts.remove(currentTraceId);
                }
            }
        }
    }

    /**
     * 格式化堆栈跟踪信息
     */
    private static String formatStackTrace(StackTraceElement[] stackTrace) {
        StringBuilder sb = new StringBuilder();
        // 跳过前3个元素，通常是线程池内部调用
        for (int i = 3; i < stackTrace.length && i < 15; i++) {
            StackTraceElement element = stackTrace[i];
            // 过滤掉一些不重要的堆栈元素
            if (element.getClassName().startsWith("java.util.concurrent") || element.getClassName().startsWith("sun.reflect") || element.getClassName().startsWith("java.lang.reflect") || element.getClassName().startsWith("org.springframework")) {
                continue;
            }
            sb.append("    at ").append(element.toString()).append("\n");
        }
        return sb.toString();
    }
}
```