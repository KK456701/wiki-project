package com.hospital.wikiagent.agent.batch;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 分子/分母患者明细的内存缓存，避免同一指标同一时间范围重复查询明细 SQL。
 *
 * <p>职责边界：仅做明细行缓存和过期淘汰；不执行 SQL、不访问数据库。
 * Key = indicatorCode|startTime|endTime|detailType(numerator/denominator)，
 * TTL = 30 分钟（与抽取缓存和 SQL_TTL 一致）。</p>
 */
@Component
public class DetailResultCache {

    private static final Duration TTL = Duration.ofMinutes(30);

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * 查询缓存的明细行。
     *
     * @return 命中时返回明细行列表，未命中或已过期返回 null
     */
    public List<Map<String, Object>> get(
            String indicatorCode,
            String startTime,
            String endTime,
            String detailType) {
        String key = buildKey(indicatorCode, startTime, endTime, detailType);
        CacheEntry entry = cache.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired()) {
            cache.remove(key);
            return null;
        }
        return entry.rows();
    }

    /**
     * 缓存明细查询结果。
     */
    public void put(
            String indicatorCode,
            String startTime,
            String endTime,
            String detailType,
            List<Map<String, Object>> rows) {
        String key = buildKey(indicatorCode, startTime, endTime, detailType);
        cache.put(key, new CacheEntry(Instant.now(), List.copyOf(rows)));
    }

    /**
     * 当前缓存条目数。
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
            String indicatorCode,
            String startTime,
            String endTime,
            String detailType) {
        return safe(indicatorCode) + "|"
                + safe(startTime) + "|"
                + safe(endTime) + "|"
                + safe(detailType);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    /**
     * 缓存条目：记录存入时间和明细行数据。
     */
    public record CacheEntry(
            Instant storedAt,
            List<Map<String, Object>> rows) {

        public boolean isExpired() {
            return Instant.now().isAfter(storedAt.plus(TTL));
        }
    }
}
