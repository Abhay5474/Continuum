package io.continuum.persistence.repository;

import io.continuum.persistence.entity.CostRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CostRecordRepository extends JpaRepository<CostRecordEntity, Long> {

    List<CostRecordEntity> findByWorkflowId(String workflowId);

    Optional<CostRecordEntity> findByIdempotencyKey(String idempotencyKey);

    @Query("SELECT COALESCE(SUM(c.estimatedCostUsd), 0) FROM CostRecordEntity c")
    double totalCost();

    @Query("SELECT COALESCE(SUM(c.promptTokens + c.completionTokens), 0) FROM CostRecordEntity c")
    long totalTokens();

    @Query("SELECT c.provider AS provider, " +
           "COALESCE(SUM(c.promptTokens + c.completionTokens),0) AS tokens, " +
           "COALESCE(SUM(c.estimatedCostUsd),0) AS cost, COUNT(c) AS calls " +
           "FROM CostRecordEntity c GROUP BY c.provider")
    List<ProviderCostView> costByProvider();

    interface ProviderCostView {
        String getProvider();
        long getTokens();
        double getCost();
        long getCalls();
    }
}
