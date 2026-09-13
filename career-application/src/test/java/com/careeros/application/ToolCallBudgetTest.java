package com.careeros.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ToolCallBudgetTest {

    @Test void allowsExactlyTheGrantedNumberOfCalls() {
        var budget = ToolCallBudget.of(3);

        assertThat(budget.tryConsume()).isTrue();
        assertThat(budget.tryConsume()).isTrue();
        assertThat(budget.tryConsume()).isTrue();
        assertThat(budget.tryConsume()).isFalse();
        assertThat(budget.spent()).isEqualTo(3);
    }

    /** 耗尽之后再问多少次都还是耗尽，计数也不再增长——否则"用了多少"会失真。 */
    @Test void staysExhaustedWithoutOverCounting() {
        var budget = ToolCallBudget.of(1);
        budget.tryConsume();

        assertThat(budget.tryConsume()).isFalse();
        assertThat(budget.tryConsume()).isFalse();
        assertThat(budget.exhausted()).isTrue();
        assertThat(budget.spent()).isEqualTo(1);
    }

    @Test void isNotExhaustedBeforeItIsSpent() {
        var budget = ToolCallBudget.of(2);
        assertThat(budget.exhausted()).isFalse();
        budget.tryConsume();
        assertThat(budget.exhausted()).isFalse();
    }

    /** 零额度的预算不是预算，是一个永远失败的调用点，应当在构造时就拒绝。 */
    @Test void aBudgetThatAllowsNothingIsRejected() {
        assertThatThrownBy(() -> ToolCallBudget.of(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToolCallBudget.of(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void theStandardBudgetIsBoundedAndReportsItsLimit() {
        var budget = ToolCallBudget.standard();
        assertThat(budget.limit()).isEqualTo(ToolCallBudget.DEFAULT_LIMIT);
        assertThat(ToolCallBudget.DEFAULT_LIMIT).isPositive();
    }
}
