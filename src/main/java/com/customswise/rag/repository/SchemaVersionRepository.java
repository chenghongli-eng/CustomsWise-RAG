package com.customswise.rag.repository;

import com.customswise.rag.entity.SchemaVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * SchemaVersion 仓储。
 *
 * 主键为 component（String），Spring Data JPA 自动生成 findById / save / deleteById 等方法。
 */
@Repository
public interface SchemaVersionRepository extends JpaRepository<SchemaVersion, String> {
}
