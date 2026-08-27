package com.customswise.rag.repository;

import com.customswise.rag.entity.QaHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface QaHistoryRepository extends JpaRepository<QaHistory, Long> {

    Page<QaHistory> findBySessionIdOrderByCreatedAtDesc(String sessionId, Pageable pageable);

    Page<QaHistory> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
