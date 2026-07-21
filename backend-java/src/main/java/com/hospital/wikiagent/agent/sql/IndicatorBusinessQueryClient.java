package com.hospital.wikiagent.agent.sql;

import java.util.List;
import java.util.Map;

public interface IndicatorBusinessQueryClient {
    List<Map<String, Object>> execute(String sql);

    String sourceId();
}
