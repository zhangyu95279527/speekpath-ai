package com.speakpath.plan;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ContentItemRepository extends JpaRepository<ContentItem, UUID> {

    List<ContentItem> findByStatusAndItemTypeOrderByDifficultyAsc(String status, String itemType);

    List<ContentItem> findByStatusOrderByItemTypeAscDifficultyAsc(String status);
}
