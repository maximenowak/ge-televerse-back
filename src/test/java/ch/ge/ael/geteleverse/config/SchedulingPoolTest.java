package ch.ge.ael.geteleverse.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les tâches {@code @Scheduled} (heartbeats SSE, balayage des sinks expirés, sonde
 * antivirus, moniteur /tmp, éviction du rate-limit) ne doivent pas partager un thread
 * unique — le défaut de Boot. Un balayage ralenti par Hazelcast y suspendait les
 * heartbeats, et les proxies coupaient alors tous les flux SSE du pod.
 */
@SpringBootTest(classes = SchedulingPoolTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SchedulingPoolTest {

    @SpringBootApplication(scanBasePackages = "ch.ge.ael.geteleverse.fake-package")
    @EnableScheduling
    static class App {}

    @Autowired
    ThreadPoolTaskScheduler taskScheduler;

    @Test
    void scheduledTasksGetADedicatedPoolLargerThanOneThread() {
        assertThat(taskScheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
                .isGreaterThanOrEqualTo(4);
    }
}
