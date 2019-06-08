package qunar.tc.qmq.backup.service.impl;

import com.google.common.base.Throwables;
import org.hbase.async.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qunar.tc.qmq.backup.config.BackupConfig;
import qunar.tc.qmq.backup.service.BackupKeyGenerator;
import qunar.tc.qmq.backup.store.KvStore;
import qunar.tc.qmq.concurrent.NamedThreadFactory;
import qunar.tc.qmq.metrics.Metrics;
import qunar.tc.qmq.store.MessageQueryIndex;
import qunar.tc.qmq.utils.RetrySubjectUtils;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static qunar.tc.qmq.backup.config.DefaultBackupConfig.*;
import static qunar.tc.qmq.metrics.MetricsConstants.SUBJECT_ARRAY;

/**
 * @author xufeng.deng dennisdxf@gmail.com
 * @since 2018-12-10 16:53
 */
public class DeadRecordBatchBackup extends AbstractBatchBackup<MessageQueryIndex> {
    private static final Logger LOGGER = LoggerFactory.getLogger(DeadRecordBatchBackup.class);

    private final KvStore recordStore;
    private final ExecutorService executorService;
    private final BackupKeyGenerator keyGenerator;

    public DeadRecordBatchBackup(KvStore recordStore, BackupKeyGenerator keyGenerator, BackupConfig config) {
        super("deadRecordBackup", config);
        this.recordStore = recordStore;
        this.keyGenerator = keyGenerator;
        this.executorService = Executors.newFixedThreadPool(config.getDynamicConfig().getInt(DEAD_RECORD_BACKUP_THREAD_SIZE_CONFIG_KEY, DEFAULT_BACKUP_THREAD_SIZE)
                , new NamedThreadFactory("dead-record-backup"));
    }

    @Override
    protected void doStop() {
        try {
            executorService.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            LOGGER.error("close dead record backup interrupted.", e);
        }
        try {
            if (recordStore != null) recordStore.close();
        } catch (Exception e) {
            LOGGER.error("close {} failed.", recordStore);
        }
    }

    @Override
    protected void store(List<MessageQueryIndex> batch, Consumer<MessageQueryIndex> fi) {
        try {
            doStore(batch);
        } catch (RejectedExecutionException e) {
            LOGGER.error("dead record backup reject exec.", e);
            retry(batch);
        }
    }

    private void retry(List<MessageQueryIndex> batch) {
        batch.forEach(this::retry);
    }

    private void retry(MessageQueryIndex message) {
        final int tryStoreNum = message.getBackupRetryTimes();
        if (tryStoreNum < retryNum()) {
            monitorStoreRetry(message.getSubject());
            message.setBackupRetryTimes(tryStoreNum + 1);
            add(message, null);
        } else {
            monitorStoreDiscard(message.getSubject());
            LOGGER.warn("record backup store discard. subject={}, messageId={}", message.getSubject(), message.getMessageId());
        }
    }

    private void doStore(final List<MessageQueryIndex> batch) {
        executorService.execute(() -> {
            try {
                doBatchSaveBackupDeadRecord(batch);
            } catch (Exception e) {
                LOGGER.error("dead record backup store error.", e);
                retry(batch);
            }
        });
    }

    private void doBatchSaveBackupDeadRecord(List<MessageQueryIndex> messages) {
        final byte[][] recordKeys = new byte[messages.size()][];
        final byte[][][] recordValues = new byte[messages.size()][][];
        long currentTime = System.currentTimeMillis();
        try {
            for (int i = 0; i < messages.size(); ++i) {
                final MessageQueryIndex message = messages.get(i);
                try {
                    final String subject = message.getSubject();
                    final String realSubject = RetrySubjectUtils.getRealSubject(subject);
                    final String consumerGroup = RetrySubjectUtils.getConsumerGroup(subject);
                    final byte[] key = keyGenerator.generateDeadRecordKey(RetrySubjectUtils.buildDeadRetrySubject(realSubject), message.getMessageId(), consumerGroup);
                    final long createTime = System.currentTimeMillis();
                    final long sequence = message.getSequence();
                    final byte[] consumerGroupBytes = Bytes.UTF8(consumerGroup);
                    byte[] value = new byte[16 + consumerGroupBytes.length];
                    Bytes.setLong(value, sequence, 0);
                    Bytes.setLong(value, createTime, 8);
                    System.arraycopy(consumerGroupBytes, 0, value, 16, consumerGroupBytes.length);
                    byte[][] recordValue = new byte[][]{value};
                    recordKeys[i] = key;
                    recordValues[i] = recordValue;
                } catch (Exception e) {
                    LOGGER.error("batch backup dead record failed.");
                    monitorStoreDeadDeadError(message.getSubject());
                }
            }
            recordStore.batchSave(recordKeys, recordValues);
        } catch (Throwable e) {
            LOGGER.error("put backup dead record fail.", e);
            Throwables.propagate(e);
        } finally {
            Metrics.timer("BatchBackup.Store.Timer", TYPE_ARRAY, DEAD_RECORD_TYPE).update(System.currentTimeMillis() - currentTime, TimeUnit.MILLISECONDS);
        }
    }

    private int retryNum() {
        return config.getInt(RECORD_BACKUP_RETRY_NUM_CONFIG_KEY, DEFAULT_RETRY_NUM);
    }

    private void monitorStoreDiscard(String subject) {
        Metrics.counter("dead_record_backup_store_discard", SUBJECT_ARRAY, new String[]{subject}).inc();
    }

    private void monitorStoreDeadDeadError(String subject) {
        Metrics.counter("dead_record_store_error", SUBJECT_ARRAY, new String[]{subject}).inc();
    }

    private static void monitorStoreRetry(String subject) {
        Metrics.counter("dead_record_backup_store_retry", SUBJECT_ARRAY, new String[]{subject}).inc();
    }

    @Override
    protected int getBatchSize() {
        return config.getInt(RECORD_BATCH_SIZE_CONFIG_KEY, DEFAULT_BATCH_SIZE);
    }
}