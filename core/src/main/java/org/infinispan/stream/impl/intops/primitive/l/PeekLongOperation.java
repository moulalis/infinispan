package org.infinispan.stream.impl.intops.primitive.l;

import java.util.function.LongConsumer;
import java.util.stream.LongStream;

import org.infinispan.factories.ComponentRegistry;
import org.infinispan.stream.CacheAware;
import org.infinispan.stream.impl.intops.IntermediateOperation;

import io.reactivex.rxjava3.core.Flowable;

/**
 * Performs peek operation on a {@link LongStream}
 */
public class PeekLongOperation implements IntermediateOperation<Long, LongStream, Long, LongStream> {
   private final LongConsumer consumer;

   public PeekLongOperation(LongConsumer consumer) {
      this.consumer = consumer;
   }

   @Override
   public LongStream perform(LongStream stream) {
      return stream.peek(consumer);
   }

   public LongConsumer getConsumer() {
      return consumer;
   }

   @Override
   public void handleInjection(ComponentRegistry registry) {
      if (consumer instanceof CacheAware) {
         ((CacheAware) consumer).injectCache(registry.getCache().running());
      } else {
         registry.wireDependencies(consumer);
      }
   }

   @Override
   public Flowable<Long> mapFlowable(Flowable<Long> input) {
      return input.doOnNext(consumer::accept);
   }
}
