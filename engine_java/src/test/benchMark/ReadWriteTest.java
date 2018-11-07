package benchMark;

import com.alibabacloud.polar_race.engine.common.EngineRace;
import com.alibabacloud.polar_race.engine.lsmtree.LSMTree;
import org.apache.log4j.Logger;

import java.io.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;


public class ReadWriteTest {
    private static Logger logger = Logger.getLogger(BenchMark.class);

    private final static String DB_PATH = "/Users/shaw/shawdb";  //数据库目录
//    private final static int THREAD_NUM = Runtime.getRuntime().availableProcessors();  //8
    private final static int THREAD_NUM = 64;
    private final static int ENTRY_NUM = 10000;

    private static Map<byte[], byte[]> kvs = new ConcurrentHashMap<>();
    private static EngineRace engineRace = new EngineRace();
    private static ExecutorService pool = Executors.newFixedThreadPool(THREAD_NUM);
    private static CountDownLatch countDownLatch = new CountDownLatch(THREAD_NUM);

    private final static String kvFilePath = "/Users/shaw/kv.data";
    private static BufferedWriter bufferedWriter;
    private static BufferedReader bufferedReader;

    private static ReentrantLock lock = new ReentrantLock();


    private static void concurrentWrite() {
        for (int i = 0; i < THREAD_NUM; i++) {
            pool.submit(new Runnable() {
                @Override
                public void run() {
                    //logger.info("线程" + Thread.currentThread().getName() + "正在执行");
                    try {
                        for (int j = 0; j < ENTRY_NUM; j++) {
                            byte[] key = TestUtil.randomString(8).getBytes();
                            byte[] value = TestUtil.randomString(4096).getBytes();
                            kvs.put(key, value);
                            engineRace.write(key, value);
//                            lock.lock();
//                            bufferedWriter.write(new String(key) + "-" + new String(value) + "\n");
//                            bufferedWriter.flush();
//                            engineRace.write(key, value);
//                            lock.unlock();
//                            engineRace.write(TestUtil.randomString(8).getBytes(), TestUtil.randomString(4096).getBytes());
                        }
                    } catch (Exception e) {
                        logger.error(e);
                    } finally {
                        countDownLatch.countDown();
                    }
                }
            });
        }
        try {
            pool.shutdown();
            countDownLatch.await();
        } catch (InterruptedException e) {
            logger.error(e);
        }
    }

    private static void readmMap() {
        int cnt = 0, rightCnt = 0;
        for (byte[] key : kvs.keySet()) {
            try {
                byte[] readVal = engineRace.read(key);
                byte[] trueVal = kvs.get(key);
                if (readVal == null || readVal.length == 0) {
                    logger.error("没找到key=" + new String(key));
                    cnt++;
                } else if (!Arrays.equals(readVal, trueVal)) {
                    logger.error("查找出的value值错误");
                    cnt++;
                } else {
                    rightCnt++;
                    logger.info("找到key=" + new String(key));
                }
            } catch (Exception e) {
                e.printStackTrace();
                logger.error(e);
            }
        }
        logger.info("读写测试：没找到或值错误的value的个数：" + cnt + ", 找到:rightCnt=" + rightCnt);
    }

    private static void read() throws IOException {
        int cnt = 0, rightCnt = 0;
        String line;
         while ( (line = bufferedReader.readLine()) != null) {
            try {
                String skey = line.split("-")[0];
                String svalue = line.split("-")[1];
                if (skey == null || skey.length() == 0 || svalue.length() == 0) break;
                byte[] key = skey.getBytes();
                byte[] trueVal = svalue.getBytes();

                byte[] readVal = engineRace.read(key);
                if (readVal == null || readVal.length == 0) {
                    logger.error("没找到key=" + new String(key));
                    cnt++;
                } else if (!Arrays.equals(readVal, trueVal)) {
                    logger.error("查找出的value值错误");
                    cnt++;
                } else {
                    rightCnt++;
                    //logger.info("找到key=" + new String(key));
                }
            } catch (Exception e) {
                e.printStackTrace();
                logger.error(e);
            }
        }
        logger.info("读写测试：没找到或值错误的value的个数：" + cnt + ", 找到:rightCnt=" + rightCnt);
    }

    public static void main(String[] args) {

//        ///===============写================
        long start = System.currentTimeMillis();
        try {
            engineRace.open(DB_PATH);
            bufferedWriter = new BufferedWriter(new FileWriter(kvFilePath));
        } catch (Exception e) {
            e.printStackTrace();
        }

        concurrentWrite();
        try {
            bufferedWriter.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        engineRace.close();

        long cost = System.currentTimeMillis() - start;
        logger.info("耗时:" + cost + "ms");

        ///===============读================
        try {
            engineRace.open(DB_PATH);
            bufferedReader = new BufferedReader(new FileReader(kvFilePath));
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            readmMap();
            bufferedReader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        engineRace.close();
    }
}
