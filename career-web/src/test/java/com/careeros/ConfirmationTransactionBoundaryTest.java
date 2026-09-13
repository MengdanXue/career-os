package com.careeros;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * 确认写入与重算不能合进一个事务。
 *
 * <p>这条曾经是真的坏的，而且坏得很隐蔽：重算内部的评估失败会把整个事务标成 rollback-only，
 * 服务层 catch 住异常、返回"已记录但未重算"之后，提交阶段仍然整体回滚，接口抛
 * {@code UnexpectedRollbackException} 返 500，用户的回答连同台账一起消失——
 * 而"失败恢复"的全部意义就是那条回答不能丢。
 *
 * <p>单元测试照不出来：假的评估器只是抛异常，没有事务参与，catch 之后一切正常。
 * 真机注入一次评估失败才暴露。
 *
 * <p>所以这里守的是结构，不是行为：控制器方法上不能有 {@code @Transactional}。
 * 行为侧的证据在 {@code docs/audits/2026-09-13-closed-loop-browser-acceptance.md}，
 * 由真实实例上的故障注入给出。这条用例只保证那个结构不被人无意中改回去。
 */
class ConfirmationTransactionBoundaryTest {

    @Test void theConfirmationEndpointDoesNotWrapBothPhasesInOneTransaction() throws Exception {
        Method endpoint = null;
        for (Method method : ProfileConfirmationController.class.getDeclaredMethods()) {
            if (method.getName().equals("record")) endpoint = method;
        }
        assertThat(endpoint).as("找不到确认接口方法").isNotNull();
        assertThat(endpoint.isAnnotationPresent(Transactional.class))
            .as("确认接口不能整体事务化：重算失败会把已提交的回答一起回滚")
            .isFalse();
        assertThat(ProfileConfirmationController.class.isAnnotationPresent(Transactional.class))
            .as("类级 @Transactional 同样会把重算拖进写入事务")
            .isFalse();
    }
}
