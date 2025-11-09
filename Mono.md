```java
import com.alibaba.ttl.TtlRunnable;
import org.reactivestreams.Publisher;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.Scannable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 用于包装 Reactor 原生 Scheduler，使其支持 TTL 上下文传递。
 */
public class TtlSchedulerWrapper {

    /**
     * 包装一个现有的 Scheduler，返回支持 TTL 上下文传递的 Scheduler。
     *
     * @param targetScheduler 需要包装的目标 Scheduler，例如 Schedulers.parallel()
     * @return 支持 TTL 的 Scheduler
     */
    public static Scheduler wrap(Scheduler targetScheduler) {
        return new TtlWrappedScheduler(targetScheduler);
    }

    private static class TtlWrappedScheduler implements Scheduler {
        private final Scheduler delegate;

        TtlWrappedScheduler(Scheduler delegate) {
            this.delegate = delegate;
        }

        @Override
        public Disposable schedule(Runnable task) {
            // 使用 TTL 包装 Runnable 任务
            Runnable ttlWrappedTask = TtlRunnable.get(task);
            return delegate.schedule(ttlWrappedTask);
        }

        @Override
        public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
            Runnable ttlWrappedTask = TtlRunnable.get(task);
            return delegate.schedule(ttlWrappedTask, delay, unit);
        }

        @Override
        public Disposable schedulePeriodically(Runnable task, long initialDelay, long period, TimeUnit unit) {
            Runnable ttlWrappedTask = TtlRunnable.get(task);
            return delegate.schedulePeriodically(ttlWrappedTask, initialDelay, period, unit);
        }

        @Override
        public Worker createWorker() {
            return new TtlWrappedWorker(delegate.createWorker());
        }

        @Override
        public long now(TimeUnit unit) {
            return delegate.now(unit);
        }

        @Override
        public void dispose() {
            delegate.dispose();
        }

        @Override
        public boolean isDisposed() {
            return delegate.isDisposed();
        }

        @Override
        public Object scanUnsafe(Scannable.Attr key) {
            return delegate.scanUnsafe(key);
        }

        @Override
        public String toString() {
            return "TtlWrappedScheduler(" + delegate + ")";
        }

        private class TtlWrappedWorker implements Worker {
            private final Worker delegateWorker;

            TtlWrappedWorker(Worker delegateWorker) {
                this.delegateWorker = delegateWorker;
            }

            @Override
            public Disposable schedule(Runnable task) {
                Runnable ttlWrappedTask = TtlRunnable.get(task);
                return delegateWorker.schedule(ttlWrappedTask);
            }

            @Override
            public Disposable schedule(Runnable task, long delay, TimeUnit unit) {
                Runnable ttlWrappedTask = TtlRunnable.get(task);
                return delegateWorker.schedule(ttlWrappedTask, delay, unit);
            }

            @Override
            public Disposable schedulePeriodically(Runnable task, long initialDelay, long period, TimeUnit unit) {
                Runnable ttlWrappedTask = TtlRunnable.get(task);
                return delegateWorker.schedulePeriodically(ttlWrappedTask, initialDelay, period, unit);
            }

            @Override
            public void dispose() {
                delegateWorker.dispose();
            }

            @Override
            public boolean isDisposed() {
                return delegateWorker.isDisposed();
            }

            @Override
            public Object scanUnsafe(Scannable.Attr key) {
                return delegateWorker.scanUnsafe(key);
            }
        }
    }
}
```



```java
import com.alibaba.ttl.TransmittableThreadLocal;
import reactor.core.publisher.Mono;

public class MonoWithTTLDemo {

    // 1. 定义 TTL 上下文
    private static final TransmittableThreadLocal<String> TTL_CONTEXT = new TransmittableThreadLocal<>();

    public static void main(String[] args) throws InterruptedException {
        // 2. 在主线程中设置上下文
        TTL_CONTEXT.set("Main-Thread-Context");

        // 3. 包装 Reactor 内置的调度器，例如 parallel
        Scheduler ttlParallelScheduler = TtlSchedulerWrapper.wrap(Schedulers.parallel());
        // 也可以包装 boundedElastic: wrap(Schedulers.boundedElastic())

        // 4. 使用包装后的调度器执行异步任务
        Mono<String> asyncTask = Mono.fromCallable(() -> {
                    // 这里是在 Reactor 内置的线程池中执行
                    System.out.println("Executing in thread: " + Thread.currentThread().getName());
                    // 现在可以获取到 TTL 上下文了！
                    String contextValue = TTL_CONTEXT.get();
                    return "Processed with context: " + contextValue;
                })
                .subscribeOn(ttlParallelScheduler); // 使用包装后的支持 TTL 的调度器

        // 5. 订阅并消费结果
        asyncTask.subscribe(result -> System.out.println("Result: " + result));

        // 主线程稍作等待，确保异步任务完成
        Thread.sleep(1000);

        // 6. 清理主线程的 TTL 上下文
        TTL_CONTEXT.remove();
    }
}
```



```java
import com.alibaba.ttl.TransmittableThreadLocal;
import com.alibaba.ttl.TtlRunnable;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.Executors;

// 1. 定义上下文（如果需要传递上下文）
@Component
public class AsyncTaskContext {
    private static final TransmittableThreadLocal<String> REQUEST_CONTEXT = 
        new TransmittableThreadLocal<>();
    
    public static void setContext(String context) {
        REQUEST_CONTEXT.set(context);
    }
    
    public static String getContext() {
        return REQUEST_CONTEXT.get();
    }
    
    public static void clearContext() {
        REQUEST_CONTEXT.remove();
    }
}

// 2. 创建支持 TTL 的自定义调度器
@Component
public class TtlSchedulerConfig {
    
    private final Scheduler ttlScheduler;
    
    public TtlSchedulerConfig() {
        // 创建支持 TTL 的单个线程调度器，确保任务顺序执行
        this.ttlScheduler = Schedulers.fromExecutor(Executors.newSingleThreadExecutor(r -> {
            Runnable ttlRunnable = TtlRunnable.get(r);
            return new Thread(ttlRunnable, "async-task-thread");
        }));
    }
    
    public Scheduler getTtlScheduler() {
        return ttlScheduler;
    }
}

// 3. 业务服务类
@Service
public class AsyncDataProcessor {
    
    private final WebClient webClient;
    private final R2dbcEntityTemplate r2dbcEntityTemplate;
    private final Scheduler ttlScheduler;
    
    public AsyncDataProcessor(WebClient.Builder webClientBuilder,
                            R2dbcEntityTemplate r2dbcEntityTemplate,
                            TtlSchedulerConfig ttlSchedulerConfig) {
        this.webClient = webClientBuilder.baseUrl("https://api.thirdparty.com").build();
        this.r2dbcEntityTemplate = r2dbcEntityTemplate;
        this.ttlScheduler = ttlSchedulerConfig.getTtlScheduler();
    }
    
    /**
     * 启动异步处理任务
     * @param contextData 需要传递的上下文数据
     * @return Mono<Void> 表示异步任务已提交
     */
    public Mono<Void> startAsyncProcessing(String contextData) {
        return Mono.fromRunnable(() -> {
                // 设置上下文（如果需要）
                AsyncTaskContext.setContext(contextData);
            })
            .then(processDataPipeline()) // 执行处理管道
            .subscribeOn(ttlScheduler)   // 在专用线程中执行
            .doFinally(signal -> {
                // 清理上下文
                AsyncTaskContext.clearContext();
            })
            .then(); // 返回 Mono<Void>
    }
    
    /**
     * 数据处理管道
     */
    private Mono<String> processDataPipeline() {
        return queryThirdPartyApi()           // 1. 查询第三方接口
            .flatMap(apiResponse -> 
                queryDatabase(apiResponse)    // 2. 查询数据库
                    .flatMap(dbData -> 
                        updateDatabase(apiResponse, dbData) // 3. 更新数据库
                    )
            )
            .timeout(Duration.ofMinutes(5))   // 设置超时时间
            .doOnSuccess(result -> 
                log.info("异步任务执行成功: {}", result)
            )
            .doOnError(error -> 
                log.error("异步任务执行失败", error)
            );
    }
    
    /**
     * 1. 查询第三方接口
     */
    private Mono<String> queryThirdPartyApi() {
        return webClient.get()
            .uri("/data")
            .retrieve()
            .bodyToMono(String.class)
            .doOnNext(response -> 
                log.info("查询第三方接口成功: {}", response)
            )
            .doOnError(error -> 
                log.error("查询第三方接口失败", error)
            );
    }
    
    /**
     * 2. 查询数据库
     */
    private Mono<MyEntity> queryDatabase(String apiResponse) {
        // 这里可以根据 API 响应构造查询条件
        String contextValue = AsyncTaskContext.getContext(); // 获取上下文
        
        return r2dbcEntityTemplate
            .select(MyEntity.class)
            .matching(Query.query(where("someField").is(apiResponse)))
            .one()
            .doOnNext(entity -> 
                log.info("查询数据库成功, 上下文: {}, 数据: {}", contextValue, entity)
            )
            .doOnError(error -> 
                log.error("查询数据库失败", error)
            );
    }
    
    /**
     * 3. 根据接口响应更新数据库
     */
    private Mono<String> updateDatabase(String apiResponse, MyEntity dbData) {
        // 根据业务逻辑更新实体
        dbData.setProcessedData(apiResponse);
        dbData.setStatus("PROCESSED");
        
        String contextValue = AsyncTaskContext.getContext();
        
        return r2dbcEntityTemplate
            .update(dbData)
            .doOnNext(updated -> 
                log.info("更新数据库成功, 上下文: {}, 更新记录数: {}", contextValue, updated)
            )
            .thenReturn("处理完成: " + apiResponse);
    }
}

// 4. 实体类
@Data
@Table("my_table")
class MyEntity {
    @Id
    private Long id;
    private String someField;
    private String processedData;
    private String status;
}

// 5. 控制器
@RestController
public class TaskController {
    
    private final AsyncDataProcessor asyncDataProcessor;
    
    public TaskController(AsyncDataProcessor asyncDataProcessor) {
        this.asyncDataProcessor = asyncDataProcessor;
    }
    
    @PostMapping("/start-task")
    public Mono<ResponseEntity<String>> startTask(@RequestBody StartTaskRequest request) {
        return asyncDataProcessor.startAsyncProcessing(request.getContextData())
            .thenReturn(ResponseEntity.accepted().body("异步任务已启动"))
            .doOnSuccess(response -> 
                log.info("主线程继续执行，不受异步任务影响")
            );
    }
}

// 6. 请求DTO
@Data
class StartTaskRequest {
    private String contextData;
}
```

