package com.fabcontext.api.domain.knowledge;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

	List<KnowledgeDocument> findByLatestTrue();

	List<KnowledgeDocument> findByDocIdOrderByPublishedAtDesc(String docId);

	Optional<KnowledgeDocument> findByDocIdAndVersion(String docId, String version);

	Optional<KnowledgeDocument> findByDocIdAndLatestTrue(String docId);
}
