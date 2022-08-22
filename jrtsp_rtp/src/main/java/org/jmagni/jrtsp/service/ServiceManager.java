package org.jmagni.jrtsp.service;

import lombok.extern.slf4j.Slf4j;
import org.jmagni.jrtsp.config.UserConfig;
import org.jmagni.jrtsp.rtsp.PortManager;
import org.jmagni.jrtsp.rtsp.netty.NettyChannelManager;
import org.jmagni.jrtsp.service.monitor.HaHandler;
import org.jmagni.jrtsp.service.scheduler.job.Job;
import org.jmagni.jrtsp.service.scheduler.job.JobBuilder;
import org.jmagni.jrtsp.service.scheduler.schedule.ScheduleManager;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardOpenOption.*;

@Slf4j
public class ServiceManager {

    private static final ServiceManager serviceManager = new ServiceManager(); // lazy initialization

    private final ScheduleManager scheduleManager = new ScheduleManager();

    public static final String MAIN_SCHEDULE_JOB = "MAIN";
    public static final String LONG_SESSION_REMOVE_SCHEDULE_JOB = "LONG_SESSION_REMOVE_JOB";

    public static final int DELAY = 1000;

    private final String tmpdir = System.getProperty("java.io.tmpdir");
    private final File lockFile = new File(tmpdir, System.getProperty("lock_file", "jrtsp_rtp.lock"));
    private FileChannel fileChannel;
    private FileLock lock;
    private boolean isQuit = false;

    private ServiceManager() {
        Runtime.getRuntime().addShutdownHook(new ShutDownHookHandler("ShutDownHookHandler", Thread.currentThread()));
    }
    
    public static ServiceManager getInstance ( ) {
        return serviceManager;
    }

    private boolean start () {
        systemLock();

        UserConfig userConfig = AppInstance.getInstance().getConfigManager().getUserConfig();

        PortManager.getInstance().initResource();

        NettyChannelManager.getInstance().openRtspChannel(
                userConfig.getLocalListenIp(),
                userConfig.getLocalRtspListenPort()
        );

        if (scheduleManager.initJob(MAIN_SCHEDULE_JOB, 10, 10 * 2)) {
            // FOR CHECKING the availability of this program
            Job haHandleJob = new JobBuilder()
                    .setScheduleManager(scheduleManager)
                    .setName(HaHandler.class.getSimpleName())
                    .setInitialDelay(0)
                    .setInterval(DELAY)
                    .setTimeUnit(TimeUnit.MILLISECONDS)
                    .setPriority(5)
                    .setTotalRunCount(1)
                    .setIsLasted(true)
                    .build();
            HaHandler haHandler = new HaHandler(haHandleJob);
            haHandler.init();
            if (scheduleManager.startJob(MAIN_SCHEDULE_JOB, haHandler.getJob())) {
                log.debug("[ServiceManager] [+RUN] HA Handler");
            } else {
                log.warn("[ServiceManager] [-RUN FAIL] HA Handler");
                return false;
            }
        }

        log.debug("| All services are opened.");
        return true;
    }
    
    public void stop() {
        PortManager.getInstance().releaseResource();

        NettyChannelManager.getInstance().deleteRtspChannel();

        scheduleManager.stopAll(MAIN_SCHEDULE_JOB);

        systemUnLock();

        isQuit = true;
        log.debug("| All services are closed.");
    }

    public void loop () {
        if (!start()) {
            log.error("Fail to start the program.");
            return;
        }

        TimeUnit timeUnit = TimeUnit.MILLISECONDS;
        while (!isQuit) {
            try {
                timeUnit.sleep(DELAY);
            } catch (InterruptedException e) {
                log.warn("| ServiceManager.loop.InterruptedException", e);
            }
        }
    }

    private void systemLock () {
        try {
            fileChannel = FileChannel.open(lockFile.toPath(), CREATE, READ, WRITE);
            lock = fileChannel.tryLock();
            if (lock == null) {
                log.error("DASH process is already running.");
                Thread.sleep(500L);
                System.exit(1);
            }
        } catch (Exception e) {
            log.error("ServiceManager.systemLock.Exception.", e);
        }
    }

    private void systemUnLock () {
        try {
            if (lock != null) {
                lock.release();
            }

            if (fileChannel != null) {
                fileChannel.close();
            }

            Files.delete(lockFile.toPath());
        } catch (IOException e) {
            //ignore
        }
    }

    /**
     * @class private static class ShutDownHookHandler extends Thread
     * @brief Graceful Shutdown 을 처리하는 클래스
     * Runtime.getRuntime().addShutdownHook(*) 에서 사용됨
     */
    private static class ShutDownHookHandler extends Thread {

        // shutdown 로직 후에 join 할 thread
        private final Thread target;

        public ShutDownHookHandler (String name, Thread target) {
            super(name);

            this.target = target;
            log.debug("| ShutDownHookHandler is initiated. (target={})", target.getName());
        }

        /**
         * @fn public void run ()
         * @brief 정의된 Shutdown 로직을 수행하는 함수
         */
        @Override
        public void run ( ) {
            try {
                shutDown();
                target.join();
                log.debug("| ShutDownHookHandler's target is finished successfully. (target={})", target.getName());
            } catch (Exception e) {
                log.warn("| ShutDownHookHandler.run.Exception", e);
            }
        }

        /**
         * @fn private void shutDown ()
         * @brief Runtime 에서 선언된 Handler 에서 사용할 서비스 중지 함수
         */
        private void shutDown ( ) {
            log.warn("| Process is about to quit. (Ctrl+C)");
            ServiceManager.getInstance().stop();
        }
    }
    
}
