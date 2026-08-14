package com.careeros.infrastructure.extraction;

import com.careeros.application.ExtractionPorts.UnitOfWork;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public final class SpringTransactionUnitOfWork implements UnitOfWork {
    private final TransactionTemplate transactions;

    public SpringTransactionUnitOfWork(PlatformTransactionManager transactionManager) {
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    }

    @Override
    public <T> T execute(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        return transactions.execute(status -> operation.get());
    }
}
