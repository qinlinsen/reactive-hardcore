package org.test.reactive;

import java.util.concurrent.atomic.AtomicLongFieldUpdater;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public class UnoptimizedArrayPublisher<T> implements Publisher<T> {

    private final T[] array;

    public UnoptimizedArrayPublisher(T[] array) {
        this.array = array;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        subscriber.onSubscribe(new UnoptimizedArraySubscription<T>(array, subscriber));
    }

    private static class UnoptimizedArraySubscription<T> implements Subscription {

        final T[] array;
        final Subscriber<? super T> subscriber;

        int index;

        volatile long requested;
        static final AtomicLongFieldUpdater<UnoptimizedArraySubscription> REQUESTED =
            AtomicLongFieldUpdater.newUpdater(UnoptimizedArraySubscription.class, "requested");

        volatile boolean cancelled;

        public UnoptimizedArraySubscription(T[] array, Subscriber<? super T> subscriber) {
            this.array = array;
            this.subscriber = subscriber;
        }

        @Override
        public void request(long n) {
            if (n <= 0 && !cancelled) {
                cancel();
                subscriber.onError(new IllegalArgumentException(
                    "§3.9 violated: positive request amount required but it was " + n
                ));
                return;
            }

            long initialRequested;

            do {
                initialRequested = requested;

                if (initialRequested == Long.MAX_VALUE) {
                    return;
                }

                n = initialRequested + n;

                if (n <= 0) {
                    n = Long.MAX_VALUE;
                }

            } while (!REQUESTED.compareAndSet(this, initialRequested, n));

            if (initialRequested > 0) {
                return;
            }

            final Subscriber<? super T> s = subscriber;
            final T[] arr = array;
            int sent = 0;
            int i = index;
            int length = arr.length;

            while (true) {
                for (; sent < n && i < length; sent++, i++) {
                    if (cancelled) {
                        return;
                    }

                    T element = arr[i];

                    if (element == null) {
                        s.onError(new NullPointerException());
                        return;
                    }

                    s.onNext(element);
                }

                if (cancelled) {
                    return;
                }

                if (i == length) {
                    s.onComplete();
                    return;
                }

                n = requested;
                if (n == sent) {
                    index = i;
                    if (REQUESTED.addAndGet(this, -sent) == 0) {
                        return;
                    }
                    sent = 0;
                }
            }
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }
}
