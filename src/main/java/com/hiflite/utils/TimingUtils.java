package com.hiflite.utils;

import java.time.Duration;
import java.time.Instant;

import org.apache.commons.lang3.time.DurationFormatUtils;

public class TimingUtils {

    private Instant instantStart = Instant.now();
    private Instant instantStop = Instant.now();
    private Duration duration = Duration.between(instantStart, instantStop);
    private DurationFormatUtils dateFormatUtils = new DurationFormatUtils();


    public void timerStart() {
        instantStart = Instant.now();
    }

    public void timerStop() {
        instantStop = Instant.now();
    }

    public TimingUtils reset() {
        instantStart = instantStop = Instant.now();
        return this;
    }

    public Duration getTimeElapsed() {
        return  Duration.between(instantStart, Instant.now());
    }

    public Duration getTotalTimeElapsed() {
        return Duration.between(instantStart, instantStop);
    }

    public void reportElapsedTime() {
        Duration duration = getTimeElapsed();

        System.out.printf("time elapsed : %02d:%02d:%02d.%03d%n", duration.toHoursPart(),  duration.toMinutesPart(), duration.toSecondsPart(), duration.toNanosPart());
    }

    public void reportTotalElapsedTime() {
        Duration duration = getTotalTimeElapsed();

        System.out.printf("total time : %02d:%02d:%02d.%03d%n", duration.toHoursPart(),  duration.toMinutesPart(), duration.toSecondsPart(), duration.toNanosPart());
    }

}
