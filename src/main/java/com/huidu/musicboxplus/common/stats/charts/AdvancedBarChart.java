package com.huidu.musicboxplus.common.stats.charts;

import com.huidu.musicboxplus.common.stats.json.JsonObjectBuilder;

import java.util.Map;
import java.util.concurrent.Callable;

public class AdvancedBarChart
extends CustomChart {
    private final Callable<Map<String, int[]>> callable;

    public AdvancedBarChart(String chartId, Callable<Map<String, int[]>> callable) {
        super(chartId);
        this.callable = callable;
    }

    @Override
    protected JsonObjectBuilder.JsonObject getChartData() throws Exception {
        JsonObjectBuilder valuesBuilder = new JsonObjectBuilder();
        Map<String, int[]> map = this.callable.call();
        if (map == null || map.isEmpty()) {
            return null;
        }
        boolean allSkipped = true;
        for (Map.Entry<String, int[]> entry : map.entrySet()) {
            if (entry.getValue().length == 0) continue;
            allSkipped = false;
            valuesBuilder.appendField(entry.getKey(), entry.getValue());
        }
        if (allSkipped) {
            return null;
        }
        return new JsonObjectBuilder().appendField("values", valuesBuilder.build()).build();
    }
}

