package org.deeplearning4j.datasets.iterator;

import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.dataset.api.MultiDataSet;
import org.nd4j.linalg.dataset.api.MultiDataSetPreProcessor;
import org.nd4j.linalg.dataset.api.iterator.MultiDataSetIterator;
import org.nd4j.linalg.factory.Nd4j;

import java.util.ConcurrentModificationException;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Async prefetching iterator wrapper for MultiDataSetIterator implementations
 *
 * PLEASE NOTE: If used together with CUDA backend, please use it with caution.
 *
 * @author Alex Black
 * @author raver119@gmail.com
 */
public class AsyncMultiDataSetIterator implements MultiDataSetIterator {

    private final MultiDataSetIterator iterator;
    private final LinkedBlockingQueue<MultiDataSet> queue;
    private AsyncMultiDataSetIterator.IteratorRunnable runnable;
    private Thread thread;

    public AsyncMultiDataSetIterator(MultiDataSetIterator iterator, int queueLength) {
        if(queueLength <= 0)
            throw new IllegalArgumentException("Queue size must be > 0");

        this.iterator = iterator;
        this.queue = new LinkedBlockingQueue<>(queueLength);

        runnable = new AsyncMultiDataSetIterator.IteratorRunnable();
        thread = new Thread(runnable);

        Integer deviceId = Nd4j.getAffinityManager().getDeviceForCurrentThread();
        Nd4j.getAffinityManager().attachThreadToDevice(thread, deviceId);

        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public MultiDataSet next(int num) {
        // TODO: why isn't supported? We could just check queue size
        throw new UnsupportedOperationException("Next(int) not supported for AsyncDataSetIterator");
    }

    @Override
    public void setPreProcessor(MultiDataSetPreProcessor preProcessor) {
        iterator.setPreProcessor(preProcessor);
    }

    @Override
    public void reset() {
        //Complication here: runnable could be blocking on either baseIterator.next() or blockingQueue.put()
        runnable.killRunnable = true;
        if(runnable.isAlive) {
            thread.interrupt();
        }
        //Wait for runnable to exit, but should only have to wait very short period of time
        //This probably isn't necessary, but is included as a safeguard against race conditions
        try{
            runnable.runCompletedSemaphore.tryAcquire(5, TimeUnit.SECONDS);
        } catch( InterruptedException e ){ }

        //Clear the queue, reset the base iterator, set up a new thread
        queue.clear();
        iterator.reset();
        runnable = new AsyncMultiDataSetIterator.IteratorRunnable();
        thread = new Thread(runnable);

        Integer deviceId = Nd4j.getAffinityManager().getDeviceForCurrentThread();
        Nd4j.getAffinityManager().attachThreadToDevice(thread, deviceId);

        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public boolean hasNext() {
        if(!queue.isEmpty())
            return true;

        if(runnable.isAlive) {
            //Empty blocking queue, but runnable is alive
            //(a) runnable is blocking on baseIterator.next()
            //(b) runnable is blocking on blockingQueue.put()
            //either way: there's at least 1 more element to come
            return true;
        } else {
            if(!runnable.killRunnable && runnable.exception != null ) {
                throw runnable.exception;   //Something went wrong
            }
            //Runnable has exited, presumably because it has fetched all elements
            return !queue.isEmpty();
        }
    }

    @Override
    public MultiDataSet next() {
        if(!hasNext()) {
            throw new NoSuchElementException();
        }

        if(runnable.exception != null) {
            throw runnable.exception;
        }

        if(!queue.isEmpty()){
            return queue.poll();    //non-blocking, but returns null if empty
        }

        //Blocking queue is empty, but more to come
        //Possible reasons:
        // (a) runnable died (already handled - runnable.exception != null)
        // (b) baseIterator.next() hasn't returned yet -> wait for it
        try{
            //Normally: just do blockingQueue.take(), but can't do that here
            //Reason: what if baseIterator.next() throws an exception after
            // blockingQueue.take() is called? In this case, next() will never return
            while(runnable.exception == null ){
                MultiDataSet ds = queue.poll(5, TimeUnit.SECONDS);
                if(ds != null) {
                    return ds;
                }
                if(runnable.killRunnable){
                    //should never happen
                    throw new ConcurrentModificationException("Reset while next() is waiting for element?");
                }
            }
            //exception thrown while getting data from base iterator
            throw runnable.exception;
        }catch(InterruptedException e ){
            throw new RuntimeException(e);  //Shouldn't happen under normal circumstances
        }
    }

    @Override
    public void remove() {
        // no-op
    }

    /**
     *
     * Shut down the async data set iterator thread
     * This is not typically necessary if using a single AsyncDataSetIterator
     * (thread is a daemon thread and so shouldn't block the JVM from exiting)
     * Behaviour of next(), hasNext() etc methods after shutdown of async iterator is undefined
     */
    public void shutdown() {
        if(thread.isAlive()) {
            runnable.killRunnable = true;
            thread.interrupt();
        }
    }

    private class IteratorRunnable implements Runnable {
        private volatile boolean killRunnable = false;
        private volatile boolean isAlive = true;
        private volatile RuntimeException exception;
        private Semaphore runCompletedSemaphore = new Semaphore(0);
        @Override
        public void run() {
            try {
                while (!killRunnable && iterator.hasNext()) {
                    queue.put(iterator.next());
                }
            } catch( InterruptedException e ){
                //thread.interrupt() while put(DataSet) was blocking
                if(killRunnable) {
                    return;
                }
                else exception = new RuntimeException("Runnable interrupted unexpectedly",e); //Something else interrupted
            } catch(RuntimeException e ){
                exception = e;
            } finally {
                isAlive = false;
                runCompletedSemaphore.release();
            }
        }
    }
}
