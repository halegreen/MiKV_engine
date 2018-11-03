package com.alibabacloud.polar_race.engine.core;

import com.alibabacloud.polar_race.engine.core.merge.Level0Merger;
import com.alibabacloud.polar_race.engine.core.merge.Level1Merger;
import com.alibabacloud.polar_race.engine.core.stats.DBStats;
import com.alibabacloud.polar_race.engine.core.stats.FileStatsCollector;
import com.alibabacloud.polar_race.engine.core.stats.Operations;
import com.alibabacloud.polar_race.engine.core.table.*;
import com.alibabacloud.polar_race.engine.core.utils.DateFormatter;
import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class LSMDB {

    static final Logger logger = LoggerFactory.getLogger(LSMDB.class);

    public static final int INMEM_LEVEL = -1;
    public static final int LEVEL0 = 0;
    public static final int LEVEL1 = 1;
    public static final int LEVEL2 = 2;
    public static final int MAX_LEVEL = 2;

    private volatile HashMapTable[] activeInMemTables;
    private Object[] activeInMemTableCreationLocks;
    private List<LevelQueue>[] levelQueueLists;
    private final DBStats stats = new DBStats();

    private String dir;
    private DBConfig config;
    private Level0Merger[] level0Mergers;
    private Level1Merger[] level1Mergers;
    private CountDownLatch[] countDownLatches;
    private FileStatsCollector fileStatsCollector;

    private boolean closed = false;
    public static final boolean DEBUG_ENABLE = false;

    private AtomicInteger putCounter = new AtomicInteger(0);

    public LSMDB(String dir) {
        this(dir, new DBConfig());
    }

    @SuppressWarnings("unchecked")
    public LSMDB(String dir, DBConfig config) {
        logger.info("初始化DB配置...，现在内存大小：" + Runtime.getRuntime().freeMemory() + "，时间：" + DateFormatter.formatCurrentDate());
        this.dir = dir;
        this.config = config;

        activeInMemTables = new HashMapTable[config.getShardNumber()];

        activeInMemTableCreationLocks = new Object[config.getShardNumber()];
        for(int i = 0; i < config.getShardNumber(); i++) {
            activeInMemTableCreationLocks[i] = new Object();
        }

        // initialize level queue list
        levelQueueLists = new ArrayList[config.getShardNumber()];
        for(int i = 0; i < config.getShardNumber(); i++) {
            levelQueueLists[i] = new ArrayList<LevelQueue>(MAX_LEVEL + 1);
            for(int j = 0; j <= MAX_LEVEL; j++) {
                levelQueueLists[i].add(new LevelQueue());
            }
        }

        try {
            this.loadMapTables();
        } catch (Exception ex) {
            throw new RuntimeException("Fail to load on disk map tables!", ex);
        }

        this.fileStatsCollector = new FileStatsCollector(stats, levelQueueLists);
        this.fileStatsCollector.start();

        this.startLevelMergers();
    }

    private void startLevelMergers() {
        countDownLatches = new CountDownLatch[this.config.getShardNumber()];
        for(int i = 0; i < this.config.getShardNumber(); i++) {
            countDownLatches[i] = new CountDownLatch(2);
        }
        level0Mergers = new Level0Merger[config.getShardNumber()];
        level1Mergers = new Level1Merger[config.getShardNumber()];

        for(short i = 0; i < this.config.getShardNumber(); i++) {
            level0Mergers[i] = new Level0Merger(this, this.levelQueueLists[i], countDownLatches[i], i, stats);
            level0Mergers[i].start();
            level1Mergers[i] = new Level1Merger(this, this.levelQueueLists[i], countDownLatches[i], i, stats);
            level1Mergers[i].start();
        }
    }

    private void loadMapTables() throws IOException, ClassNotFoundException {
        logger.info("加载磁盘的table信息...");
        File dirFile = new File(dir);
        if (!dirFile.exists())  {
            dirFile.mkdirs();
        }
        String fileNames[] = dirFile.list(new FilenameFilter() {

            @Override
            public boolean accept(File dir, String filename) {
                if (filename.endsWith(AbstractMapTable.INDEX_FILE_SUFFIX)) return true;
                return false;
            }

        });

        // new DB, setup new active map table
        if (fileNames == null || fileNames.length == 0) {
            for(short i = 0; i < this.config.getShardNumber(); i++) {
                this.activeInMemTables[i] = new HashMapTable(dir, i, LEVEL0, System.nanoTime());
                this.activeInMemTables[i].markUsable(true);
                this.activeInMemTables[i].markImmutable(false); // mutable
                this.activeInMemTables[i].setCompressionEnabled(this.config.isCompressionEnabled());
            }
            logger.info("新建引擎！不需要加载table信息");
            return;
        }

        PriorityQueue<AbstractMapTable> pq = new PriorityQueue<AbstractMapTable>();
        for(String fileName : fileNames) {
            int dotIndex = fileName.lastIndexOf(".");
            if (dotIndex > 0) {
                fileName = fileName.substring(0, dotIndex);
            }
            String[] parts = fileName.split("-");
            Preconditions.checkArgument(parts != null && parts.length == 3, "on-disk table file names corrupted!");
            int level = Integer.parseInt(parts[1]);
            if (level == LEVEL0) {
                pq.add(new HashMapTable(dir, fileName));
            } else if (level == LEVEL1) {
                pq.add(new MMFMapTable(dir, fileName));
            } else {
                pq.add(new FCMapTable(dir, fileName));
            }
        }

        Preconditions.checkArgument(pq.size() > 0, "on-disk table file names corrupted!");

        // setup active map table
        for(int i = 0; i < this.config.getShardNumber(); i++) {
            AbstractMapTable table = pq.poll();
            Preconditions.checkArgument(table.getLevel() == 0, "on-disk table file names corrupted, no level 0 map tables");
            this.activeInMemTables[table.getShard()] = (HashMapTable) table;
            this.activeInMemTables[table.getShard()].markUsable(true);
            this.activeInMemTables[table.getShard()].markImmutable(false); // mutable
            this.activeInMemTables[table.getShard()].setCompressionEnabled(this.config.isCompressionEnabled());
        }

        //初始化level的table信息
        while(!pq.isEmpty()) {
            AbstractMapTable table = pq.poll();
            if (table.isUsable()) {
                int level = table.getLevel();
                LevelQueue lq = levelQueueLists[table.getShard()].get(level);
                lq.addLast(table);
            } else { // garbage
                table.close();
                table.delete();
            }
        }

        logger.info("加载磁盘的table信息完成！");
    }

    public String getDir() {
        return this.dir;
    }

    public DBConfig getConfig() { return this.config; }

    public DBStats getStats() {
        return this.stats;
    }

    /**
     * Put key/value entry into the DB with no timeout
     *
     * @param key the map entry key
     * @param value the map entry value
     */
    public void put(byte[] key, byte[] value) {
        this.put(key, value, AbstractMapTable.NO_TIMEOUT, System.currentTimeMillis(), false);
    }

    /**
     * Put key/value entry into the DB with specific timeToLive
     *
     * @param key the map entry key
     * @param value the map entry value
     * @param timeToLive time to live
     */
    public void put(byte[] key, byte[] value, long timeToLive) {
        this.put(key, value, timeToLive, System.currentTimeMillis(), false);
    }

    private short getShard(byte[] key) {
        int keyHash = Arrays.hashCode(key);
        keyHash = Math.abs(keyHash);
        return (short) (keyHash % this.config.getShardNumber());
    }

    private void put(byte[] key, byte[] value, long timeToLive, long createdTime, boolean isDelete) {
        Preconditions.checkArgument(key != null && key.length > 0, "key is empty");
        Preconditions.checkArgument(value != null && value.length > 0, "value is empty");
        ensureNotClosed();

        long start = System.nanoTime();
        String operation = isDelete ? Operations.DELETE : Operations.PUT;
        try {
            short shard = this.getShard(key);
            boolean success = this.activeInMemTables[shard].put(key, value, timeToLive, createdTime, isDelete);

            if (!success) { // overflow
                synchronized(activeInMemTableCreationLocks[shard]) {
                    success = this.activeInMemTables[shard].put(key, value, timeToLive, createdTime, isDelete); // other thread may have done the creation work
                    if (!success) { // move to level queue 0
                        this.activeInMemTables[shard].markImmutable(true);
                        LevelQueue lq0 = this.levelQueueLists[shard].get(LEVEL0);
                        lq0.getWriteLock().lock();
                        try {
                            lq0.addFirst(this.activeInMemTables[shard]);
                            logger.info("queue0.size=" + lq0.size());
                        } finally {
                            lq0.getWriteLock().unlock();
                        }

                        @SuppressWarnings("resource")
                        HashMapTable tempTable = new HashMapTable(dir, shard, LEVEL0, System.nanoTime());
                        tempTable.markUsable(true);
                        tempTable.markImmutable(false); //mutable
                        tempTable.put(key, value, timeToLive, createdTime, isDelete);
                        // switch on
                        this.activeInMemTables[shard] = tempTable;
                        tempTable = null;
                    }
                }
            }
            putCounter.incrementAndGet();
            /*if (putCounter.get() % 10000 == 0) {
                logger.info("已写入" + putCounter.get() + "个数");
            }*/
            if (DEBUG_ENABLE) {
                logger.info("数据写入：key=" + new String(key));
            }
        } catch(IOException ioe) {
            stats.recordDBError(operation);
            if (isDelete) {
                throw new RuntimeException("Fail to delete key, IOException occurr", ioe);
            }
            throw new RuntimeException("Fail to put key & value, IOException occurr", ioe);
        } finally {
            stats.recordDBOperation(operation, INMEM_LEVEL, System.nanoTime() - start);
        }
    }

    /**
     * Get value in the DB with specific key
     *
     * @param key map entry key
     * @return non-null value if the entry exists, not deleted or expired.
     * null value if the entry does not exist, or exists but deleted or expired.
     */
    public byte[] get(byte[] key) {
        Preconditions.checkArgument(key != null && key.length > 0, "key is empty");
        ensureNotClosed();
        long start = System.nanoTime();
        int reachedLevel = INMEM_LEVEL;
        try {
            short shard = this.getShard(key);
            // check active hashmap table first
            Result result = this.activeInMemTables[shard].get(key);
            if (result.isFound()) {
                if (!result.isDeleted() && !result.isExpired()) {
                    return result.getValue();
                } else {
                    return null; // deleted or expired
                }
            } else {
                // check level0 hashmap tables
                reachedLevel = LEVEL0;
                LevelQueue lq0 = levelQueueLists[shard].get(LEVEL0);
                lq0.getReadLock().lock();
                try {
                    if (lq0 != null && lq0.size() > 0) {
                        for(AbstractMapTable table : lq0) {
                            result = table.get(key);
                            if (result.isFound()) break;
                        }
                    }
                } finally {
                    lq0.getReadLock().unlock();
                }

                if (result.isFound()) {
                    if (!result.isDeleted() && !result.isExpired()) {
                        if (result.getLevel() == LSMDB.LEVEL2 && this.config.isLocalityEnabled()) { // keep locality
                            this.put(key, result.getValue(), result.getTimeToLive(), result.getCreatedTime(), false);
                        }
                        return result.getValue();
                    } else {
                        return null; // deleted or expired
                    }
                }

                // check level 1-2 on disk sorted tables
                searchLevel12: {
                    for(int level = 1; level <= MAX_LEVEL; level++) {
                        reachedLevel = level;
                        LevelQueue lq = levelQueueLists[shard].get(level);
                        lq.getReadLock().lock();
                        try {
                            if (lq.size() > 0) {
                                for(AbstractMapTable table : lq) {
                                    result = table.get(key);
                                    if (result.isFound()) break searchLevel12;
                                }
                            }
                        } finally {
                            lq.getReadLock().unlock();
                        }
                    }
                }

                if (result.isFound()) {
                    if (!result.isDeleted() && !result.isExpired()) {
                        if (result.getLevel() == LSMDB.LEVEL2 && this.config.isLocalityEnabled()) { // keep locality
                            this.put(key, result.getValue(), result.getTimeToLive(), result.getCreatedTime(), false);
                        }
                        return result.getValue();
                    } else {
                        return null; // deleted or expired
                    }
                }
            }
        }
        catch(IOException ioe) {
            stats.recordDBError(Operations.GET);
            throw new RuntimeException("Fail to get value by key, IOException occurr", ioe);
        } finally {
            stats.recordDBOperation(Operations.GET, reachedLevel, System.nanoTime() - start);
        }

        return null; // no luck
    }

    public void close() throws IOException {
        logger.info("正在关闭存储引擎..." + "时间：" + DateFormatter.formatCurrentDate());
        if (closed) return;

        fileStatsCollector.setStop();

        for(int i = 0; i < config.getShardNumber(); i++) {
            this.activeInMemTables[i].close();
        }
        logger.info("activeInMemTable正常关闭！");

        for(int i = 0; i < config.getShardNumber(); i++) {
            this.level0Mergers[i].setStop();
            this.level1Mergers[i].setStop();
        }
        for(int i = 0; i < config.getShardNumber(); i++) {
            try {
                logger.info("Shard " + i + " waiting level 0 & 1 merge threads to exit...");
                this.countDownLatches[i].await(3000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                // ignore;
            }

        }

        for(int i = 0; i < config.getShardNumber(); i++) {
            for(int j = 0; j <= MAX_LEVEL; j++) {
                LevelQueue lq = this.levelQueueLists[i].get(j);
                for(AbstractMapTable table : lq) {
                    table.close();
                }

            }
        }

        closed = true;
        logger.info("引擎正常关闭！" + "时间：" + DateFormatter.formatCurrentDate());
    }

    /**
     * Delete all back files;
     *
     */
    public void destory() {
        Preconditions.checkArgument(closed, "Can't delete DB in open status, please close first.");

        for(int i = 0; i < config.getShardNumber(); i++) {
            this.activeInMemTables[i].delete();
        }

        for(int i = 0; i < config.getShardNumber(); i++) {
            for(int j = 0; j <= MAX_LEVEL; j++) {
                LevelQueue lq = this.levelQueueLists[i].get(j);
                for(AbstractMapTable table : lq) {
                    table.delete();
                }

            }
        }
    }

    protected void ensureNotClosed() {
        if (closed) {
            throw new IllegalStateException("You can't work on a closed SDB.");
        }
    }
}

