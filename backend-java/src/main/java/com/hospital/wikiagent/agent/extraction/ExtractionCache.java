package com.hospital.wikiagent.agent.extraction;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 内存级数据抽取去重缓存，避免同一指标、同一时间范围重复触发源数据抽取。
 *
 * <p>职责边界：仅做"是否已抽取"的判定和过期淘汰；不执行抽取、不访问数据库。
 * Key = hospitalSoid|indicatorCode|startTime|endTime|caliberProfileId，
 * Value = 抽取完成时间戳 + 数据快照 ID。TTL = 30 分钟（与 SQL_TTL 一致）。</p>
 */
@Component
public class ExtractionCache {

    private static final Duration TTL = Duration.ofMinutes(30);

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * 查询缓存是否命中（未过期）。
     *
     * @return 命中时返回缓存条目，未命中或已过期返回 null
     */
    public CacheEntry get(
            String hospitalSoid,
            String indicatorCode,
            String startTime,
            String endTime,
            String caliberProfileId) {
        String key = buildKey(hospitalSoid, indicatorCode, startTime, endTime, caliberProfileId);
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired()) {
            cache.remove(key);
            return null;
        }
        return entry;
    }

    /**
     * 记录一次成功的抽取。
     */
    public void put(
            String hospitalSoid,
            String indicatorCode,
            String startTime,
            String endTime,
            String caliberProfileId,
            String extractionId,
            String snapshotId) {
        String key = buildKey(hospitalSoid, indicatorCode, startTime, endTime, caliberProfileId);
        cache.put(key, new CacheEntry(
                Instant.now(), extractionId, snapshotId));
    }

    /**
     * 当前缓存条目数（含可能已过期但尚未淘汰的）。
     */
    public int size() {
        return cache.size();
    }

    /**
     * 清除所有已过期条目。
     */
    public void evictExpired() {
        cache.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    private static String buildKey(
            String hospitalSoid,
            String indicatorCode,
            String startTime,
            String endTime,
            String caliberProfileId) {
        return safe(hospitalSoid) + "|"
                + safe(indicatorCode) + "|"
                + safe(startTime) + "|"
                + safe(endTime) + "|"
                + safe(caliberProfileId);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * 缓存条目：记录抽取完成时间和关联的快照 ID。
     */
    public record CacheEntry(
            Instant completedAt,
            String extractionId,
            String snapshotId) {

        public boolean isExpired() {
            return Instant.now().isAfter(completedAt.plus(TTL));
        }
    }
}
