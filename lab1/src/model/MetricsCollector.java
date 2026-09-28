package model;

public interface MetricsCollector {

    void record(long value);

    Snapshot snapshot();

}