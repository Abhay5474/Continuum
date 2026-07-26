package io.continuum.persistence.repository;

import io.continuum.persistence.entity.AccountMembershipEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccountMembershipRepository extends JpaRepository<AccountMembershipEntity, String> {

    List<AccountMembershipEntity> findByAccountDeveloperId(String accountDeveloperId);

    void deleteByAccountDeveloperId(String accountDeveloperId);
}
