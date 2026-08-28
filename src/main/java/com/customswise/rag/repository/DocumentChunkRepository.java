package com.customswise.rag.repository;

import com.customswise.rag.entity.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {

    List<DocumentChunk> findByDocumentId(Long documentId);

    long countByDocumentId(Long documentId);

    void deleteByDocumentId(Long documentId);
}
