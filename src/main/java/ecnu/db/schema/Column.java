package ecnu.db.schema;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import ecnu.db.constraintchain.filter.Parameter;
import ecnu.db.constraintchain.filter.operation.CompareOperator;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * @author wangqingshuai
 */
public class Column {
    private ColumnType columnType;
    private long min;
    private long range;
    //todo 根据输入动态变化
    private int specialValue;
    private float nullPercentage;
    private List<Pair<Long, BigDecimal>> bucketBound2FreeSpace = new LinkedList<>();

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private HashMap<Long, BigDecimal> eqConstraint2Probability = new HashMap<>();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private StringTemplate stringTemplate;

    @JsonIgnore
    private boolean hasBetweenConstraint = false;
    @JsonIgnore
    private final TreeMap<BigDecimal, List<Parameter>> eqRequest2ParameterIds = new TreeMap<>();
    @JsonIgnore
    private long[] columnData;
    @JsonIgnore
    private boolean columnData2ComputeData = false;
    @JsonIgnore
    private double[] computeData;

    public Column(ColumnType columnType) {
        this.columnType = columnType;
    }

    public void setMinAndRange(long min, long range) {
        this.min = min;
        this.range = range;
        bucketBound2FreeSpace.add(new ImmutablePair<>(min + range, BigDecimal.ONE));
    }

    public StringTemplate getStringTemplate() {
        return stringTemplate;
    }

    public void setStringTemplate(StringTemplate stringTemplate) {
        this.stringTemplate = stringTemplate;
    }

    public ColumnType getColumnType() {
        return columnType;
    }

    public HashMap<Long, BigDecimal> getEqConstraint2Probability() {
        return eqConstraint2Probability;
    }

    public void setEqConstraint2Probability(HashMap<Long, BigDecimal> eqConstraint2Probability) {
        this.eqConstraint2Probability = eqConstraint2Probability;
    }

    public List<Pair<Long, BigDecimal>> getBucketBound2FreeSpace() {
        return bucketBound2FreeSpace;
    }

    public void setBucketBound2FreeSpace(List<Pair<Long, BigDecimal>> bucketBound2FreeSpace) {
        this.bucketBound2FreeSpace = bucketBound2FreeSpace;
    }

    public int getSpecialValue() {
        return specialValue;
    }

    public void setSpecialValue(int specialValue) {
        this.specialValue = specialValue;
    }

    public long getMin() {
        return min;
    }

    public void setMin(long min) {
        this.min = min;
    }

    public long getRange() {
        return range;
    }

    public void setRange(long range) {
        this.range = range;
    }

    public boolean isHasBetweenConstraint() {
        return hasBetweenConstraint;
    }

    public void setHasBetweenConstraint(boolean hasBetweenConstraint) {
        this.hasBetweenConstraint = hasBetweenConstraint;
    }

    public float getNullPercentage() {
        return nullPercentage;
    }

    public void setNullPercentage(float nullPercentage) {
        this.nullPercentage = nullPercentage;
    }

    /**
     * 插入非等值约束概率
     *
     * @param probability 约束概率
     * @param operator    操作符
     * @param parameters  参数
     */
    private void insertNonEqProbability(BigDecimal probability, CompareOperator operator, List<Parameter> parameters) {
        long bound;
        if (operator == CompareOperator.LE || operator == CompareOperator.LT) {
            probability = BigDecimal.ONE.subtract(probability);
        }
        switch (operator) {
            case LT:
            case GE:
                bound = min + (long) probability.multiply(BigDecimal.valueOf(range)).doubleValue();
                break;
            case GT:
            case LE:
                bound = min + probability.multiply(BigDecimal.valueOf(range)).longValue();
                break;
            default:
                throw new UnsupportedOperationException();
        }
        parameters.parallelStream().forEach(parameter -> parameter.setData(bound));
        this.bucketBound2FreeSpace.add(new ImmutablePair<>(bound, probability));
    }

    private void _insertEqualProbability(BigDecimal probability, Parameter parameter) {
        if (!eqRequest2ParameterIds.containsKey(probability)) {
            eqRequest2ParameterIds.put(probability, new LinkedList<>());
        }
        eqRequest2ParameterIds.get(probability).add(parameter);
    }

    private void insertEqualProbability(BigDecimal probability, List<Parameter> parameters) {
        BigDecimal tempProbability = new BigDecimal(probability.toString());
        TreeSet<BigDecimal> probabilityHistogram = new TreeSet<>(eqRequest2ParameterIds.keySet());
        while (tempProbability.compareTo(BigDecimal.ZERO) > 0 && probabilityHistogram.size() > 0) {
            BigDecimal lowerBound = probabilityHistogram.lower(tempProbability);
            probabilityHistogram.remove(lowerBound);
            tempProbability = tempProbability.subtract(lowerBound);
            eqRequest2ParameterIds.get(tempProbability).add(parameters.remove(0));
        }
        int parameterSize;
        while ((parameterSize = parameters.size()) > 0) {
            if (parameterSize > 1) {
                BigDecimal currentProbability = BigDecimal.valueOf(ThreadLocalRandom.current().nextDouble(tempProbability.doubleValue()));
                _insertEqualProbability(currentProbability, parameters.remove(0));
                tempProbability = tempProbability.subtract(currentProbability);
            } else {
                _insertEqualProbability(tempProbability, parameters.remove(0));
            }
        }
    }

    public void insertUniVarProbability(BigDecimal probability, CompareOperator operator, List<Parameter> parameters) {
        switch (operator) {
            case NE:
                probability = BigDecimal.ONE.subtract(probability);
            case EQ:
            case IN:
            case LIKE:
                insertEqualProbability(probability, parameters);
                break;
            case GT:
            case LE:
            case LT:
            case GE:
                insertNonEqProbability(probability, operator, parameters);
                break;
            default:
                throw new UnsupportedOperationException();
        }
    }

    /**
     * 插入between的概率
     * 目前如果列上存在between，则不会实例化等值约束
     *
     * @param probability       between的概率
     * @param lessParameters    代表between的小于条件的参数
     * @param greaterParameters 代表between的大于条件的参数
     */
    public void insertBetweenProbability(BigDecimal probability,
                                         CompareOperator lessOperator, List<Parameter> lessParameters,
                                         CompareOperator greaterOperator, List<Parameter> greaterParameters) {
        hasBetweenConstraint = true;
        long betweenRange = probability.multiply(BigDecimal.valueOf(range)).longValue();
        long start = min + ThreadLocalRandom.current().nextLong(range - betweenRange);
        long end = start + betweenRange;
        lessParameters.forEach(parameter -> parameter.setData(start - (greaterOperator == CompareOperator.GE ? 0 : 1)));
        greaterParameters.forEach(parameter -> parameter.setData(end + (lessOperator == CompareOperator.LE ? 0 : 1)));
    }

    public void initEqParameter() {
        if (hasBetweenConstraint) {
            throw new UnsupportedOperationException("不支持为存在between约束的列分配等值");
        }
        //初始化每个bucket的剩余容量和等值容量
        Map<Long, AtomicInteger> bucketId2EqNum = new HashMap<>();
        bucketBound2FreeSpace.sort(Map.Entry.comparingByKey());
        BigDecimal lastBound = BigDecimal.ZERO;
        for (Pair<Long, BigDecimal> currentBound : bucketBound2FreeSpace) {
            currentBound.setValue(currentBound.getValue().subtract(lastBound));
            bucketId2EqNum.put(currentBound.getKey(), new AtomicInteger(0));
        }
        // 将等值的概率请求从大到小排序，首先为请求最大的安排空间，每次选择能放下的bucket中最小的。
        // 填充到bucket之后，重新调整剩余容量的记录treemap
        // 针对等值约束的赋值，采用逆序赋值法，即从bound开始，按照当前在bucket中的位置，分配对应的值，赋值最小从lowBound-1开始
        for (BigDecimal eqProbability : eqRequest2ParameterIds.descendingKeySet()) {
            bucketBound2FreeSpace.sort(Map.Entry.comparingByValue());
            Optional<Pair<Long, BigDecimal>> freeSpace2BucketIdOptional = bucketBound2FreeSpace.stream().
                    filter(bucket -> bucket.getValue().compareTo(eqProbability) > -1).findFirst();
            if (freeSpace2BucketIdOptional.isPresent()) {
                //重新调整freeSpace
                Pair<Long, BigDecimal> freeSpace2BucketId = freeSpace2BucketIdOptional.get();
                Long bucketBound = freeSpace2BucketId.getKey();
                bucketBound2FreeSpace.remove(freeSpace2BucketId);
                bucketBound2FreeSpace.add(new ImmutablePair<>(bucketBound, freeSpace2BucketId.getRight().subtract(eqProbability)));
                //顺序赋值
                long eqParameterData = bucketBound - bucketId2EqNum.get(bucketBound).incrementAndGet();
                for (Parameter parameter : eqRequest2ParameterIds.get(eqProbability)) {
                    parameter.setData(eqParameterData);
                }
            } else {
                throw new UnsupportedOperationException("等值约束冲突，无法实例化");
            }
        }
        // 重新调整lowBound2EqProbability，移除没有等值约束的bound
        bucketId2EqNum.values().removeIf(value -> value.get() != 0);
        LinkedList<Long> prepareToDelete = new LinkedList<>();
        for (int i = 0; i < bucketBound2FreeSpace.size() - 1; i++) {
            if (bucketId2EqNum.containsKey(bucketBound2FreeSpace.get(i).getKey()) &&
                    bucketId2EqNum.containsKey(bucketBound2FreeSpace.get(i + 1).getKey())) {
                prepareToDelete.add(bucketBound2FreeSpace.get(i).getKey());
                bucketBound2FreeSpace.get(i + 1).setValue(bucketBound2FreeSpace.get(i).getValue().add(bucketBound2FreeSpace.get(i + 1).getValue()));
            }
        }
        bucketBound2FreeSpace.removeIf(longBigDecimalPair -> prepareToDelete.contains(longBigDecimalPair.getKey()));
    }

    /**
     * 在column中维护数据
     *
     * @param size column内部需要维护的数据大小
     */
    public void prepareTupleData(int size) {
        columnData = new long[size];
        int nullSize = (int) (size * nullPercentage);
        int sizeWithoutNull = size - nullSize;
        // 使用Long.MIN_VALUE来代指null
        Arrays.fill(columnData, 0, nullSize, Long.MIN_VALUE);
        int currentIndex = nullSize;
        if (eqConstraint2Probability.size() > 0) {
            for (Map.Entry<Long, BigDecimal> entry : eqConstraint2Probability.entrySet()) {
                int eqSize = entry.getValue().multiply(BigDecimal.valueOf(sizeWithoutNull)).intValue();
                Arrays.fill(columnData, currentIndex, currentIndex += eqSize, entry.getKey());
            }
        }
        //赋值随机数据
        int i = 0;
        long lastBound = min;
        for (Pair<Long, BigDecimal> bucket2Probability : bucketBound2FreeSpace) {
            int randomSize;
            if (++i == bucketBound2FreeSpace.size()) {
                randomSize = size - currentIndex;
            } else {
                randomSize = BigDecimal.valueOf(sizeWithoutNull).multiply(bucket2Probability.getValue()).intValue();
            }
            long[] randomData = ThreadLocalRandom.current().longs(randomSize, lastBound, lastBound = bucket2Probability.getKey()).toArray();
            System.arraycopy(randomData, 0, columnData, currentIndex, randomSize);
            currentIndex += randomSize;
        }

        // shuffle数组
        long temp;
        int swapIndex;
        Random rnd = ThreadLocalRandom.current();
        for (int index = columnData.length - 1; index >= 0; index--) {
            swapIndex = rnd.nextInt(index);
            temp = columnData[swapIndex];
            columnData[swapIndex] = columnData[index];
            columnData[index] = temp;
        }
        columnData2ComputeData = false;
    }

    /**
     * 无运算比较，针对传入的参数，对于单操作符进行比较
     *
     * @param operator   运算操作符
     * @param parameters 待比较的参数
     * @param hasNot     是否包含not运算符
     * @return 运算结果
     */
    public boolean[] evaluate(CompareOperator operator, List<Parameter> parameters, boolean hasNot) {
        long value = parameters.get(0).getData();
        boolean[] ret = new boolean[columnData.length];
        switch (operator) {
            case LIKE:
            case EQ:
                IntStream.range(0, ret.length).parallel().forEach(index -> ret[index] = hasNot ^ columnData[index] == value);
                break;
            case NE:
                IntStream.range(0, ret.length).parallel().forEach(index -> ret[index] = hasNot ^ columnData[index] != value);
                break;
            case IN:
                HashSet<Long> parameterData = new HashSet<>();
                for (Parameter parameter : parameters) {
                    parameterData.add(parameter.getData());
                }
                IntStream.range(0, ret.length).parallel().forEach(index -> ret[index] = hasNot ^ parameterData.contains(columnData[index]));
                break;
            case LT:
                IntStream.range(0, ret.length).parallel().forEach(index -> ret[index] = hasNot ^ columnData[index] < value);
                break;
            case LE:
                IntStream.range(0, ret.length).parallel().forEach(index -> ret[index] = hasNot ^ columnData[index] <= value);
                break;
            case GT:
                IntStream.range(0, ret.length).parallel().forEach(index -> ret[index] = hasNot ^ columnData[index] > value);
                break;
            case GE:
                IntStream.range(0, ret.length).parallel().forEach(index -> ret[index] = hasNot ^ columnData[index] >= value);
                break;
            case ISNULL:
                IntStream.range(0, ret.length).parallel().forEach(index -> ret[index] = hasNot ^ columnData[index] == Long.MIN_VALUE);
                break;
            default:
                throw new UnsupportedOperationException();
        }
        return ret;
    }

    /**
     * @return 返回用于multi-var计算的一个double数组
     */
    public double[] calculate() {
        //lazy生成computeData
        if (!columnData2ComputeData) {
            computeData = Arrays.stream(columnData).parallel().mapToDouble(data -> (double) data / specialValue).toArray();
            columnData2ComputeData = true;
        }
        return computeData;
    }

    public String[] output() {
        switch (columnType) {
            case INTEGER:
                return Arrays.stream(columnData).parallel().mapToObj(Long::toString).toArray(String[]::new);
            case DECIMAL:
                return Arrays.stream(calculate()).parallel().mapToObj(Double::toString).toArray(String[]::new);
            case VARCHAR:
                return Arrays.stream(columnData).parallel().mapToObj(index -> stringTemplate.transferColumnData2Value(specialValue, index)).toArray(String[]::new);
            case DATE:
                DateTimeFormatter dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE;
                return Arrays.stream(columnData).parallel().mapToObj(index -> Instant.ofEpochMilli(index).atZone(ZoneId.systemDefault()).format(dateFormatter)).toArray(String[]::new);
            case DATETIME:
                DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ISO_DATE;
                return Arrays.stream(columnData).parallel().mapToObj(index -> Instant.ofEpochMilli(index).atZone(ZoneId.systemDefault()).format(dateTimeFormatter)).toArray(String[]::new);
            default:
                throw new UnsupportedOperationException();
        }
    }

    public int getNdv() {
        return (int) range;
    }
}
