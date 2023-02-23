package ecnu.db.schema;

import ecnu.db.generator.constraintchain.filter.Parameter;
import ecnu.db.generator.constraintchain.filter.operation.CompareOperator;
import ecnu.db.utils.CommonUtils;
import ecnu.db.utils.exception.TouchstoneException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class Distribution {
    private static final BigDecimal reuseEqProbabilityLimit = BigDecimal.valueOf(0.15);

    private final Logger logger = LoggerFactory.getLogger(Distribution.class);
    private final NavigableMap<BigDecimal, List<Parameter>> pvAndPbList = new TreeMap<>();
    private final long range;
    // bound PV的偏移位置
    private SortedMap<BigDecimal, Long> offset2Pv = new TreeMap<>();
    private SortedMap<Long, BigDecimal> paraData2Probability = new TreeMap<>();
    private List<List<Integer>> idList = new ArrayList<>();

    public Distribution(BigDecimal nullPercentage, long range) {
        this.range = range;
        // 初始化pvAndPbList 插入pve
        Parameter pve = new Parameter();
        pve.setData(range);
        pve.setId(-1);
        pvAndPbList.put(BigDecimal.ONE.subtract(nullPercentage), new ArrayList<>(List.of(pve)));
        paraData2Probability.put(range, BigDecimal.ONE.subtract(nullPercentage));
    }

    public boolean hasConstraints() {
        return pvAndPbList.size() > 1 || pvAndPbList.lastEntry().getValue().size() > 1;
    }

    public BigDecimal getOffset(long dataIndex) {
        if (offset2Pv.isEmpty()) {
            return BigDecimal.ZERO;
        }
        for (Map.Entry<BigDecimal, Long> off2Pv : offset2Pv.entrySet()) {
            if (off2Pv.getValue() == dataIndex) {
                return off2Pv.getKey();
            }
        }
        Long lastPv = offset2Pv.get(offset2Pv.lastKey());
        BigDecimal lastRange = paraData2Probability.get(lastPv);
        return offset2Pv.lastKey().add(lastRange);
    }

    /**
     * 插入非等值约束概率
     *
     * @param probability 约束概率
     * @param operator    操作符
     * @param parameters  参数
     */
    private void insertNonEqProbability(BigDecimal probability, CompareOperator operator, List<Parameter> parameters) {
        if (operator == CompareOperator.GE || operator == CompareOperator.GT) {
            probability = pvAndPbList.lastKey().subtract(probability);
        }
        if (probability.compareTo(BigDecimal.ONE) < 0 && probability.compareTo(BigDecimal.ZERO) > 0) {
            adjustNullPercentage(probability, parameters);
            pvAndPbList.putIfAbsent(probability, new ArrayList<>());
            pvAndPbList.get(probability).addAll(parameters);
        } else {
            long dataIndex = probability.compareTo(BigDecimal.ZERO) <= 0 ? -1 : range + 1;
            for (Parameter parameter : parameters) {
                parameter.setData(dataIndex);
            }
        }
    }

    private BigDecimal subReuse(List<Integer> containIdList, BigDecimal probability, Parameter parameter) {
        for (Integer paraId : containIdList) {
            for (Map.Entry<BigDecimal, List<Parameter>> pb2pvList : pvAndPbList.entrySet()) {
                for (Parameter assignParameter : pb2pvList.getValue()) {
                    if (Objects.equals(assignParameter.getId(), paraId)) {
                        pb2pvList.getValue().add(parameter);
                        return probability.subtract(getRange(pb2pvList.getKey()));
                    }
                }
            }
        }
        return probability;
    }

    public BigDecimal reusePb(List<Parameter> tempParameterList, BigDecimal probability) {
        var parIter = tempParameterList.iterator();
        while (parIter.hasNext()) {
            Parameter parameter = parIter.next();
            int id = parameter.getId();
            List<Integer> containIdList = idList.stream().filter(list -> list.contains(id)).findAny().orElse(null);
            if (containIdList != null) {
                BigDecimal tempProbability = subReuse(containIdList, probability, parameter);
                if (tempProbability.compareTo(probability) < 0) {
                    probability = tempProbability;
                    parIter.remove();
                }
                if (BigDecimal.ZERO.compareTo(probability) == 0) {
                    break;
                }
            }
        }
        return probability;
    }

    /**
     * 如果合法请求概率超过了能提供的有效概率，则降低null的概率
     */
    private void adjustNullPercentage(BigDecimal probability, List<Parameter> parameters) {
        BigDecimal validCDFRange = pvAndPbList.lastKey();
        if (probability.compareTo(validCDFRange) > 0) {
            pvAndPbList.get(validCDFRange).removeIf(parameter -> parameter.getId() == -1);
            if (pvAndPbList.get(validCDFRange).isEmpty()) {
                pvAndPbList.remove(validCDFRange);
            }
            BigDecimal error = probability.subtract(validCDFRange);
            pvAndPbList.put(probability, new ArrayList<>(List.of(new Parameter(-1, null, null))));
            logger.error("参数{}请求range超过有效的CDF空间, 增加非Null概率，幅度为{}",
                    parameters.stream().mapToInt(Parameter::getId).boxed().toList(), error);
        }
    }


    private boolean canBeReused(List<Parameter> parameters, BigDecimal probability) {
        boolean status = true;
        for (Parameter parameter : parameters) {
            for (List<Integer> integers : idList) {
                if (integers.contains(parameter.getId())) {
                    status = false;
                }
            }
        }
        if (!isNonEqualRange(parameters) && probability.compareTo(reuseEqProbabilityLimit) <= 0) {
            status = false;
        }
        return status;
    }


    private void insertEqualProbability(BigDecimal probability, List<Parameter> parameters) throws TouchstoneException {
        adjustNullPercentage(probability, parameters);
        List<Parameter> tempParameterList = new LinkedList<>(parameters);
        // 如果没有剩余的请求，则返回
        if (probability.compareTo(BigDecimal.ZERO) == 0) {
            tempParameterList.forEach(parameter -> parameter.setData(-1));
            return;
        }
        // 如果有某个参数拒绝重用，则直接进行贪心寻range
        if (tempParameterList.stream().allMatch(Parameter::isCanMerge)) {
            probability = reusePb(tempParameterList, probability);
        }
        // 如果没有剩余的请求，则返回
        if (probability.compareTo(BigDecimal.ZERO) == 0) {
            tempParameterList.forEach(parameter -> parameter.setData(-1));
            return;
        }
        // 标记该参数为等值的参数
        tempParameterList.forEach(parameter -> parameter.setEqualPredicate(true));
        BigDecimal minRange = BigDecimal.TEN;
        BigDecimal minCDF = BigDecimal.ZERO;
        for (var currentCDF : pvAndPbList.entrySet()) {
            BigDecimal currentRange = getRange(currentCDF.getKey());
            // 当前空间可以被完全利用
            if (currentRange.compareTo(probability) == 0 && canBeReused(currentCDF.getValue(), probability)) {
                currentCDF.getValue().addAll(tempParameterList);
                return;
            } else {
                // 找到可以放置的最小空间
                boolean enoughCapacity = currentRange.compareTo(probability) > 0;
                boolean isMinRange = minRange.compareTo(currentRange) > 0;
                // 如果cdf中包含等值的参数 则该range为一个等值的range，不可分割
                boolean isNonEqualRange = isNonEqualRange(currentCDF.getValue());
                if (enoughCapacity && isMinRange && isNonEqualRange) {
                    minRange = currentRange;
                    minCDF = currentCDF.getKey();
                }
            }
        }
        // 如果没有找到
        if (minCDF.compareTo(BigDecimal.ZERO) == 0) {
            throw new TouchstoneException(String.format("不存在可以放置的range, 概率为%s", probability));
        } else if (tempParameterList.stream().anyMatch(Parameter::isCanMerge)) {
            BigDecimal eachPb = probability.divide(BigDecimal.valueOf(tempParameterList.size()), CommonUtils.BIG_DECIMAL_DEFAULT_PRECISION);
            for (Parameter parameter : tempParameterList) {
                BigDecimal remainCapacity = minRange.subtract(eachPb);
                BigDecimal eqCDf = minCDF.subtract(remainCapacity);
                pvAndPbList.put(eqCDf, new LinkedList<>(Collections.singletonList(parameter)));
                minRange = remainCapacity;
            }
        } else if (!tempParameterList.isEmpty()) {
            BigDecimal remainCapacity = minRange.subtract(probability);
            BigDecimal eqCDf = minCDF.subtract(remainCapacity);
            pvAndPbList.put(eqCDf, new LinkedList<>(tempParameterList));
        }
    }

    private boolean isNonEqualRange(List<Parameter> parameters) {
        return parameters.stream().noneMatch(Parameter::isEqualPredicate);
    }

    private BigDecimal getRange(BigDecimal currentCDF) {
        BigDecimal lastCDf = pvAndPbList.lowerKey(currentCDF);
        if (lastCDf == null) {
            lastCDf = BigDecimal.ZERO;
        }
        return currentCDF.subtract(lastCDf);
    }

    /**
     * @return 增加的基数
     */
    public long initAllParameters() {
        offset2Pv.clear();
        long addCardinality = 0;
        if (range <= 0) {
            return addCardinality;
        }
        long remainCardinality = range - pvAndPbList.size();
        BigDecimal remainRange = pvAndPbList.entrySet().stream()
                // search right bound of each no equal range
                .filter(e -> isNonEqualRange(e.getValue()))
                // compute the size of range
                .map(e -> getRange(e.getKey()))
                // sum all range
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cardinalityPercentage;
        if (remainRange.compareTo(BigDecimal.ZERO) > 0) {
            if (remainCardinality < 0) {
                addCardinality = -remainCardinality;
                remainCardinality = 0;
            }
            cardinalityPercentage = BigDecimal.valueOf(remainCardinality).divide(remainRange, CommonUtils.BIG_DECIMAL_DEFAULT_PRECISION);
        } else {
            if (remainCardinality > 0) {
                addCardinality = -remainCardinality;
            }
            cardinalityPercentage = BigDecimal.ZERO;
        }
        long dataIndex = 0;
        paraData2Probability.clear();
        for (var CDF2Parameters : pvAndPbList.entrySet()) {
            dataIndex++;
            BigDecimal rangeSize = getRange(CDF2Parameters.getKey());
            if (isNonEqualRange(CDF2Parameters.getValue())) {
                dataIndex += cardinalityPercentage.multiply(rangeSize).setScale(0, RoundingMode.HALF_UP).intValue();
            }
            paraData2Probability.put(dataIndex, rangeSize);
            for (Parameter parameter : CDF2Parameters.getValue()) {
                parameter.setData(dataIndex);
            }
        }
        return addCardinality;
    }

    public void applyUniVarConstraint(BigDecimal probability, CompareOperator operator, List<Parameter> parameters) throws TouchstoneException {
        switch (operator) {
            case NE, NOT_LIKE, NOT_IN ->
                    insertEqualProbability(pvAndPbList.lastKey().subtract(probability), parameters);
            case EQ, LIKE, IN -> insertEqualProbability(probability, parameters);
            case GT, LT, GE, LE -> insertNonEqProbability(probability, operator, parameters);
            default -> throw new UnsupportedOperationException();
        }
    }

    private long[] generateAttributeData(BigDecimal bSize) {
        // 如果全列数据为空，则不需要填充属性值
        if (paraData2Probability.size() == 1 && paraData2Probability.lastKey() == -1) {
            return new long[0];
        }
        // 生成为左开右闭，因此lastParaData始终比上一右边界大
        long lastParaData = 1;
        BigDecimal cumulativeError = BigDecimal.ZERO;
        List<Long> allBoundPvs = offset2Pv.values().stream().toList();
        List<long[]> rangeValues = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> data2Probability : paraData2Probability.entrySet()) {
            long currentParaData = data2Probability.getKey();
            if (!allBoundPvs.contains(currentParaData)) {
                BigDecimal bGenerateSize = bSize.multiply(data2Probability.getValue());
                var generateSizeAndCumulativeError = computeGenerateSize(bGenerateSize, cumulativeError);
                cumulativeError = generateSizeAndCumulativeError.getValue();
                long[] rangeValue = new long[generateSizeAndCumulativeError.getKey()];
                if (currentParaData == lastParaData) {
                    Arrays.fill(rangeValue, currentParaData);
                } else {
                    for (int i = 0; i < rangeValue.length; i++) {
                        rangeValue[i] = ThreadLocalRandom.current().nextLong(lastParaData, currentParaData + 1);
                    }
                }
                rangeValues.add(rangeValue);
            }
            // 移动到右边界+1
            lastParaData = currentParaData + 1;
        }
        // 存储符合CDF的属性值
        if (rangeValues.size() > 1) {
            int allValueLength = rangeValues.stream().mapToInt(v -> v.length).sum();
            long[] attributeData = new long[allValueLength];
            int rangeIndex = 0;
            for (long[] rangeValue : rangeValues) {
                System.arraycopy(rangeValue, 0, attributeData, rangeIndex, rangeValue.length);
                rangeIndex += rangeValue.length;
            }
            return attributeData;
        } else {
            return rangeValues.get(0);
        }
    }


    private Map.Entry<Integer, BigDecimal> computeGenerateSize(BigDecimal generateSize, BigDecimal cumulativeError) {
        BigDecimal roundOffset = generateSize.setScale(0, RoundingMode.HALF_UP);
        cumulativeError = cumulativeError.add(roundOffset.subtract(generateSize));
        int offset = roundOffset.intValue();
        if (cumulativeError.compareTo(BigDecimal.ONE) >= 0) {
            cumulativeError = cumulativeError.subtract(BigDecimal.ONE);
            offset--;
        } else if (cumulativeError.compareTo(BigDecimal.valueOf(-1)) <= 0) {
            cumulativeError = cumulativeError.add(BigDecimal.ONE);
            offset++;
        }
        return new AbstractMap.SimpleEntry<>(offset, cumulativeError);
    }

    /**
     * 在column中维护数据
     * todo 列内随机生成，且有NULL的部分不要随机
     *
     * @param size column内部需要维护的数据大小
     */
    public long[] prepareTupleData(int size) {
        BigDecimal bSize = BigDecimal.valueOf(size);
        long[] attributeData = generateAttributeData(bSize);
        // 将属性值与bound值组合
        int currentIndex = 0;
        int attributeIndex = 0;
        long[] columnData = new long[size];
        BigDecimal cumulativeOffsetError = BigDecimal.ZERO;
        for (Map.Entry<BigDecimal, Long> pv2Offset : offset2Pv.entrySet()) {
            // 确定bound的开始offset
            BigDecimal bOffset = bSize.multiply(pv2Offset.getKey());
            var offsetAndCumulativeError = computeGenerateSize(bOffset, cumulativeOffsetError);
            cumulativeOffsetError = offsetAndCumulativeError.getValue();
            while (currentIndex < offsetAndCumulativeError.getKey()) {
                columnData[currentIndex++] = attributeData[attributeIndex++];
            }
            // 确定bound的大小
            BigDecimal rangeProbability = paraData2Probability.get(pv2Offset.getValue());
            BigDecimal bPvSize = bSize.multiply(rangeProbability);
            var pvSizeAndCumulativeError = computeGenerateSize(bPvSize, cumulativeOffsetError);
            int generateSize = pvSizeAndCumulativeError.getKey();
            cumulativeOffsetError = pvSizeAndCumulativeError.getValue();
            Arrays.fill(columnData, currentIndex, currentIndex + generateSize, pv2Offset.getValue());
            currentIndex += generateSize;
        }
        // 复制最后部分
        int attributeRemain = attributeData.length - attributeIndex;
        int localRemain = size - currentIndex;
        attributeRemain = Math.min(attributeRemain, localRemain);
        for (int i = 0; i < attributeRemain; i++) {
            columnData[currentIndex++] = attributeData[attributeIndex++];
        }
        // 使用Long.MIN_VALUE标记结尾的null值
        if (currentIndex < size) {
            Arrays.fill(columnData, currentIndex, size, Long.MIN_VALUE);
        }
        return columnData;
    }

    public SortedMap<BigDecimal, Long> getOffset2Pv() {
        return offset2Pv;
    }

    public void setOffset2Pv(SortedMap<BigDecimal, Long> offset2Pv) {
        this.offset2Pv = offset2Pv;
    }

    public SortedMap<Long, BigDecimal> getParaData2Probability() {
        return paraData2Probability;
    }

    public void setParaData2Probability(SortedMap<Long, BigDecimal> paraData2Probability) {
        this.paraData2Probability = paraData2Probability;
    }


    public void setIdList(List<List<Integer>> idList) {
        this.idList = idList;
    }
}
