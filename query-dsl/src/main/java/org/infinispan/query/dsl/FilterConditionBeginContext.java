package org.infinispan.query.dsl;

/**
 * The beginning context of an incomplete condition. It exposes methods for specifying the left hand side of the
 * condition.
 *
 * @author anistor@redhat.com
 * @since 6.0
 * @deprecated since 10.1. See deprecation note on {@link QueryBuilder}.
 */
@Deprecated
public interface FilterConditionBeginContext {

   FilterConditionEndContext having(String attributePath);

   FilterConditionEndContext having(Expression expression);

   FilterConditionContextQueryBuilder not();

   FilterConditionContextQueryBuilder not(FilterConditionContext fcc);
}
