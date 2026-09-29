package impl;

/**
 * Этап 4
 */
public final class DoubleBufferNoRecheckCollector extends DoubleBufferCollector {

    @Override
    int enterActiveBuffer(ThreadBuffers my) {
        int b = active;
        my.inside.set(b);
        return b;
    }
}
