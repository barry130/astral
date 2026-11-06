package com.astral.storage.service;

import com.astral.common.exception.BusinessException;
import com.astral.storage.config.StorageProperties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 上传准入控制（§11.1）：全实例最多 1 个并发接收/发送，单用户最多 1 个，不排队。
 *
 * <p>计数是「实例内」的软限制，真正的正确性仍由数据库状态机（CAS）保证：即使计数漂移，
 * 也只会多放行请求，不会让同一个 uploadId 被读两次正文。计数在请求结束（成功/失败/超时）后释放；
 * 超时不会提前释放，必须在旧请求真正停止后才归还（否则会立刻超卖）。</p>
 *
 * <p>{@code inflight} 还承担「每个 uploadId 每实例只允许一个执行句柄」的职责，恢复任务据此跳过
 * 正在本实例处理中的操作，不会与在线请求抢同一条记录。</p>
 */
@Slf4j
@Component
public class UploadAdmissionControl {

    private final StorageProperties properties;
    private final AtomicInteger globalInUse = new AtomicInteger();
    private final ConcurrentHashMap<String, AtomicInteger> userInUse = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> inflight = new ConcurrentHashMap<>();

    public UploadAdmissionControl(StorageProperties properties) {
        this.properties = properties;
    }

    /** 当前是否有本实例正在处理的上传（恢复任务用） */
    public boolean isInflight(String uploadId) {
        return inflight.containsKey(uploadId);
    }

    /**
     * 获取执行许可；拿不到直接 STORAGE042（不排队、不阻塞等待）。
     */
    public Permit acquire(String uploadId, String actorId) {
        StorageProperties.UploadOperation config = properties.getUploadOperation();
        int maxGlobal = Math.max(1, config.getMaxConcurrentUploads());
        int maxPerUser = Math.max(1, config.getMaxConcurrentPerUser());
        if (inflight.putIfAbsent(uploadId, actorId == null ? "" : actorId) != null) {
            throw new BusinessException("STORAGE042");
        }
        boolean acquired = false;
        try {
            if (globalInUse.incrementAndGet() > maxGlobal) {
                globalInUse.decrementAndGet();
                throw new BusinessException("STORAGE042");
            }
            int perUser = userInUse
                    .computeIfAbsent(actorId == null ? "" : actorId, key -> new AtomicInteger())
                    .incrementAndGet();
            if (perUser > maxPerUser) {
                releaseUserCounter(actorId);
                globalInUse.decrementAndGet();
                throw new BusinessException("STORAGE042");
            }
            acquired = true;
            return new Permit(this, uploadId, actorId);
        } finally {
            if (!acquired) {
                inflight.remove(uploadId);
            }
        }
    }

    private void releaseCounters(String uploadId, String actorId) {
        inflight.remove(uploadId);
        releaseUserCounter(actorId);
        globalInUse.decrementAndGet();
    }

    private void releaseUserCounter(String actorId) {
        String key = actorId == null ? "" : actorId;
        userInUse.computeIfPresent(key, (k, value) -> value.decrementAndGet() <= 0 ? null : value);
    }

    /** 执行许可；必须 close（建议 try-with-resources），重复 close 无副作用 */
    public static final class Permit implements AutoCloseable {

        private final UploadAdmissionControl owner;
        private final String uploadId;
        private final String actorId;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(UploadAdmissionControl owner, String uploadId, String actorId) {
            this.owner = owner;
            this.uploadId = uploadId;
            this.actorId = actorId;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                owner.releaseCounters(uploadId, actorId);
            }
        }
    }
}
