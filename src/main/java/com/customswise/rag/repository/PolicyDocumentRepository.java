package com.customswise.rag.repository;

import com.customswise.rag.entity.PolicyDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface PolicyDocumentRepository extends JpaRepository<PolicyDocument, Long> {

    Page<PolicyDocument> findByDeletedFalse(Pageable pageable);

    Page<PolicyDocument> findByStatusAndDeletedFalse(String status, Pageable pageable);

    @Query("SELECT p FROM PolicyDocument p WHERE p.deleted = false " +
           "AND (:status IS NULL OR p.status = :status) " +
           "AND (:business IS NULL OR p.applicableBusiness LIKE %:business%)")
    Page<PolicyDocument> findByFilters(@Param("status") String status,
                                       @Param("business") String business,
                                       Pageable pageable);

    List<PolicyDocument> findByStatusAndDeletedFalse(String status);

    boolean existsByFileHash(String fileHash);
}
