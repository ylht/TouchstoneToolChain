package ecnu.db.analyzer.online.adapter.tidb.stats;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * @author qingshuai.wang
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TidbStatsJsonObject {
    int count;
    Map<String, Distribution> columns;

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public Map<String, Distribution> getColumns() {
        return columns;
    }

    public void setColumns(Map<String, Distribution> columns) {
        this.columns = columns;
    }

    public float getNullProbability(String name) {
        return (float) columns.get(name).nullCount / count;
    }

    public BigDecimal getAvgLength(String name) {
        if (count == 0) {
            return new BigDecimal(0);
        }
        BigDecimal totalSize = BigDecimal.valueOf(columns.get(name).totalColSize);
        BigDecimal tableSize = BigDecimal.valueOf(count);
        return totalSize.divide(tableSize, 4, RoundingMode.HALF_UP).subtract(BigDecimal.valueOf(2));
    }


    public int getNdv(String name) {
        return columns.get(name).getHistogram().getNdv();
    }
}






