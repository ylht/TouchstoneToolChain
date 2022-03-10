package ecnu.db.analyzer.online.adapter.pg;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.ReadContext;
import net.minidev.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PgJsonReader {
    private static ReadContext readContext;

    private PgJsonReader() {
    }

    static void setReadContext(String plan) {
        Configuration conf = Configuration.defaultConfiguration()
                .addOptions(Option.DEFAULT_PATH_LEAF_TO_NULL)
                .addOptions(Option.SUPPRESS_EXCEPTIONS);
        PgJsonReader.readContext = JsonPath.using(conf).parse(plan);
    }

    // read node type

    static String readNodeType(StringBuilder path) {
        return readContext.read(path + "['Node Type']");
    }

    // move cursor in json

    static StringBuilder move2LeftChild(StringBuilder path) {
        return new StringBuilder(path).append("['Plans'][0]");
    }

    static StringBuilder move2RightChild(StringBuilder path) {
        return new StringBuilder(path).append("['Plans'][1]");
    }

    static StringBuilder move3ThirdChild(StringBuilder path) {
        return new StringBuilder(path).append("['Plans'][2]");
    }

    static StringBuilder skipNodes(StringBuilder path) {
        //找到第一个可以处理的节点
        while (new PgNodeTypeInfo().isPassNode(readNodeType(path))) {
            path = move2LeftChild(path);
        }
        return path;
    }

    // read schema info

    static String readTableName(String path) {
        return readContext.read(path + "['Schema']") + "." + readContext.read(path + "['Relation Name']");
    }

    static String readAlias(String path) {
        return readContext.read(path + "['Alias']");
    }


    // deal with subPlan

    static boolean hasInitPlan(StringBuilder path) {
        List<String> subPlanTags = readContext.read(path + "['Plans'][*]['Subplan Name']");
        subPlanTags.removeAll(Collections.singleton(null));
        if (!subPlanTags.isEmpty()) {
            return subPlanTags.stream().anyMatch(subPlanTag -> subPlanTag.contains("InitPlan"));
        }
        return false;
    }

    static int readPlansCount(StringBuilder path) {
        if (readContext.read(path + "['Plans']") == null) {
            return 0;
        }
        return readContext.read(path + "['Plans'].length()");
    }

    static String readPlan(StringBuilder path, int index) {
        LinkedHashMap<String, Object> data = readContext.read(path + "['Plans'][" + index + "]");
        return JSONObject.toJSONString(data);
    }

    static String readSubPlanIndex(StringBuilder path) {
        String subPlanName = readContext.read(path + "['Subplan Name']");
        if (subPlanName != null && subPlanName.contains("InitPlan")) {
            Pattern returnRegex = Pattern.compile("returns \\$[0-9]+");
            Matcher matcher = returnRegex.matcher(subPlanName);
            if (matcher.find()) {
                return matcher.group().replace("returns ", "");
            }
        }
        return null;
    }

    static List<String> readOutput(StringBuilder path) {
        return readContext.read(path + "['Output']");
    }

    // read aggregation info

    static int readAggGroup(StringBuilder path) {
        String childSelection = path + "['Plans'][0]";
        double actualRows = readContext.read(childSelection + "['Actual Rows']");
        int actualLoops = readActualLoops(new StringBuilder(childSelection));
        return (int) (actualLoops / actualRows);
    }

    static List<String> readGroupKey(StringBuilder path) {
        return readContext.read(path + "['Group Key']");
    }


    // read filter info
    static String readFilterInfo(StringBuilder path) {
        return readContext.read(path + "['Filter']");
    }


    // read join info

    // read join condition

    static String readJoinFilter(StringBuilder path) {
        return readContext.read(path + "['Join Filter']");
    }

    static String readJoinCond(StringBuilder path) {
        return readContext.read(path + "['Hash Cond']");
    }


    // read physical join operator

    static String readIndexJoin(StringBuilder path) {
        String joinFilter = readJoinFilter(path);
        path = skipNodes(move2RightChild(path));
        String indexCond = readContext.read(path + "['Index Cond']");
        String recheckCond = readContext.read(path + "['Recheck Cond']");
        if (indexCond == null) {
            if (recheckCond != null) {
                indexCond = "Recheck Cond: " + recheckCond;
            } else if (joinFilter != null) {
                indexCond = "joinFilter Cond: " + joinFilter;
            } else {
                throw new UnsupportedOperationException();
            }
        } else {
            if (joinFilter != null) {
                indexCond = "joinFilter Cond: " + joinFilter;
            } else {
                indexCond = "Index Cond: " + indexCond;
            }
        }
        return indexCond;
    }

    static String readHashJoin(StringBuilder path) {
        StringBuilder joinInfo = new StringBuilder("Hash Cond: ").append((String) readContext.read(path + "['Hash Cond']"));
        String joinFilter;
        if ((joinFilter = readJoinFilter(path)) != null) {
            joinInfo.append(" Join Filter: ").append(joinFilter);
        }
        return joinInfo.toString();
    }

    static String readMergeJoin(StringBuilder path) {
        StringBuilder joinInfo = new StringBuilder("Merge Cond: ").append((String) readContext.read(path + "['Merge Cond']"));
        String joinFilter;
        if ((joinFilter = readJoinFilter(path)) != null) {
            joinInfo.append(" Join Filter: ").append(joinFilter);
        }
        return joinInfo.toString();
    }

    // read logical join operator

    private static String readJoinType(StringBuilder path) {
        return readContext.read(path + "['Join Type']");
    }

    static boolean isOutJoin(StringBuilder path) {
        return isLeftOuterJoin(path) || isRightOuterJoin(path) || isFullOuterJoin(path);
    }

    static boolean isLeftOuterJoin(StringBuilder path){
        return readJoinType(path).equals("Left");
    }

    static boolean isRightOuterJoin(StringBuilder path){
        return readJoinType(path).equals("Right");
    }

    static boolean isFullOuterJoin(StringBuilder path){
        return readJoinType(path).equals("Full");
    }

    static boolean isSemiJoin(StringBuilder path) {
        return readJoinType(path).equals("Semi") || readJoinType(path).equals("Anti");
    }

    static boolean isAntiJoin(StringBuilder path) {
        return readJoinType(path).equals("Anti");
    }

    // read rows count functions

    static int readRowCount(StringBuilder path) {
        return readRowsCountAdaptive(path, "['Actual Rows']");
    }

    static int readRowsRemovedByJoinFilter(StringBuilder path) {
        return readRowsCountAdaptive(path, "['Rows Removed by Join Filter']");
    }

    static int readRowsRemoved(StringBuilder path) {
        return readRowsCountAdaptive(path, "['Rows Removed by Filter']");
    }

    static int readActualLoops(StringBuilder path) {
        return readContext.read(path + "['Actual Loops']");
    }

    private static int readRowsCountAdaptive(StringBuilder path, String tag) {
        double actualRows;
        int actualLoops = readActualLoops(path);
        Object rows = readContext.read(path + tag);
        if (rows instanceof Double temp) {
            actualRows = temp;
        } else if (rows instanceof Integer temp) {
            actualRows = temp.doubleValue();
        } else {
            throw new UnsupportedOperationException();
        }
        return (int) Math.ceil(actualRows * actualLoops);
    }
}
